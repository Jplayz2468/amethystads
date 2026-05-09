package net.jplayz.metrics;

import net.jplayz.AmethystAdsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Self-contained daily metrics tracker. Wraps everything in try/catch — metrics
 * failures must never affect ad rendering.
 *
 * Design notes:
 *  - All counters are LongAdder, so the per-tick scan never contends with flush.
 *  - One DayBucket per UTC date; flush sends DELTAS and resets atomically only
 *    on a 2xx response, so partial failures roll forward into the next attempt.
 *  - Visibility scan runs every {@code scanPeriodTicks} on the main thread.
 *    Cheap-reject ladder (distance² → look-dot → frame-faces-player → ray-trace)
 *    keeps p99 cost negligible; an automatic backoff doubles the period if a
 *    single pass blows the watchdog budget.
 */
public final class MetricsTracker implements Listener {

    private static final long FLUSH_PERIOD_TICKS = 20L * 60L * 5L; // 5 min
    private static final long DEFAULT_SCAN_PERIOD_TICKS = 5L;      // 4 Hz
    private static final long MAX_SCAN_PERIOD_TICKS = 40L;         // 0.5 Hz floor
    private static final long VIEW_COOLDOWN_MS = 10_000L;
    private static final long IMPRESSION_GAZE_MS = 1_000L;
    private static final long LOOK_BREAK_MS = 250L;
    private static final long SCAN_BUDGET_NS = 2_000_000L;         // 2 ms
    private static final long EVICT_PERIOD_MS = 60_000L;
    private static final long EVICT_TTL_MS = 30_000L;

    private final AmethystAdsPlugin plugin;
    private volatile boolean enabled = true;
    private volatile boolean broken = false;
    private long scanPeriodTicks = DEFAULT_SCAN_PERIOD_TICKS;
    private long lastEvictMs = 0L;

    private final SimpleDateFormat dateFmt;

    // ad_image_id (parsed) -> per-day counters bucket. Day key is UTC YYYY-MM-DD.
    private final Map<String, DayBucket> buckets = new ConcurrentHashMap<String, DayBucket>();

    // Open sessions. UUID -> joinTimeMs.
    private final Map<UUID, Long> openSessions = new ConcurrentHashMap<UUID, Long>();

    // packed (player entityId, frame uuid lsb) -> last view ms
    private final Map<Long, Long> lastViewTs = new ConcurrentHashMap<Long, Long>();
    // same key -> look state
    private final Map<Long, LookState> lookStates = new ConcurrentHashMap<Long, LookState>();

    public MetricsTracker(AmethystAdsPlugin plugin) {
        this.plugin = plugin;
        this.dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        this.dateFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public void start() {
        if (!enabled) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Open sessions for anyone already online (e.g. /reload).
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) openSessions.putIfAbsent(p.getUniqueId(), now);

        scheduleScan();

        new BukkitRunnable() {
            @Override public void run() { tryFlush(); }
        }.runTaskTimerAsynchronously(plugin, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    public void shutdown() {
        // Finalize any open sessions before the final flush.
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : openSessions.entrySet()) {
            recordSessionClose(now, e.getValue());
        }
        openSessions.clear();
        tryFlush();
    }

    private void scheduleScan() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!enabled || broken) { cancel(); return; }
                long start = System.nanoTime();
                try {
                    scan();
                } catch (Throwable t) {
                    broken = true;
                    plugin.getLogger().warning("metrics scan disabled: " + t.getMessage());
                    cancel();
                    return;
                }
                long elapsed = System.nanoTime() - start;
                if (elapsed > SCAN_BUDGET_NS && scanPeriodTicks < MAX_SCAN_PERIOD_TICKS) {
                    long next = Math.min(MAX_SCAN_PERIOD_TICKS, scanPeriodTicks * 2);
                    plugin.getLogger().info(
                            "metrics scan over budget (" + (elapsed / 1_000_000L) + " ms), backing off "
                                    + scanPeriodTicks + "→" + next + " ticks");
                    scanPeriodTicks = next;
                    cancel();
                    scheduleScan();
                }
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastEvictMs > EVICT_PERIOD_MS) {
                    lastEvictMs = nowMs;
                    evictStale(nowMs);
                }
            }
        }.runTaskTimer(plugin, scanPeriodTicks, scanPeriodTicks);
    }

    // ---------- event hooks ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        openSessions.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!enabled) return;
        Long started = openSessions.remove(e.getPlayer().getUniqueId());
        if (started == null) return;
        recordSessionClose(System.currentTimeMillis(), started);
    }

    /** Called by the plugin when a player right-clicks (or attacks) a banner frame. */
    public void onServerClick(String adId) {
        if (!enabled || adId == null || adId.isEmpty()) return;
        int id = parseInt(adId);
        if (id <= 0) return;
        bucket(currentDate()).counters(id).serverClicks.increment();
    }

    // ---------- visibility scan ----------

    private void scan() {
        if (plugin.isAdsHidden()) return;
        Map<UUID, UUID[]> groupFrames = plugin.getGroupFrames();
        if (groupFrames.isEmpty()) return;
        long nowMs = System.currentTimeMillis();
        String today = currentDate();

        // Iterate placed groups; for each, compute center+normal once and scan all
        // players in that world. The cheap-reject ladder makes the inner loop nearly free.
        for (Map.Entry<UUID, UUID[]> entry : groupFrames.entrySet()) {
            UUID anchor = entry.getKey();
            UUID[] group = entry.getValue();
            if (group == null) continue;
            String adId = plugin.getFrameAdAssignment(anchor);
            if (adId == null || adId.isEmpty()) continue;
            int adImageId = parseInt(adId);
            if (adImageId <= 0) continue;

            // Center + normal from any live frame in the group.
            World world = null;
            double sumX = 0, sumY = 0, sumZ = 0;
            int cnt = 0;
            Vector normal = null;
            for (UUID uid : group) {
                if (uid == null) continue;
                Entity ent = Bukkit.getEntity(uid);
                if (!(ent instanceof ItemFrame)) continue;
                ItemFrame fr = (ItemFrame) ent;
                if (world == null) {
                    world = fr.getWorld();
                    normal = BannerVisibility.normalOf(fr.getFacing());
                }
                Location loc = fr.getLocation();
                sumX += loc.getX(); sumY += loc.getY(); sumZ += loc.getZ();
                cnt++;
            }
            if (cnt == 0 || world == null) continue;
            Location center = new Location(world, sumX / cnt, sumY / cnt, sumZ / cnt);

            DayBucket day = bucket(today);
            AdCounters counters = day.counters(adImageId);
            long anchorLsb = anchor.getLeastSignificantBits();

            for (Player p : world.getPlayers()) {
                Location eye = p.getEyeLocation();
                double dSq = BannerVisibility.distanceSq(eye, center);
                if (dSq > BannerVisibility.VIEW_MAX_DISTANCE_SQ) {
                    breakLook(p, anchorLsb, nowMs);
                    continue;
                }
                Vector look = eye.getDirection();
                if (look.lengthSquared() < 1e-6) continue;
                double dot = BannerVisibility.lookDot(look, eye, center);
                if (dot < BannerVisibility.VIEW_COS) {
                    breakLook(p, anchorLsb, nowMs);
                    continue;
                }
                if (!BannerVisibility.frameFacesPlayer(normal, eye, center)) {
                    breakLook(p, anchorLsb, nowMs);
                    continue;
                }
                double dist = Math.sqrt(dSq);
                if (!BannerVisibility.clearLineOfSight(p, center, dist)) {
                    breakLook(p, anchorLsb, nowMs);
                    continue;
                }

                long key = (((long) p.getEntityId()) << 32) ^ anchorLsb;

                // Views: gated by 10s cooldown per (player, banner).
                Long lastView = lastViewTs.get(key);
                if (lastView == null || nowMs - lastView >= VIEW_COOLDOWN_MS) {
                    lastViewTs.put(key, nowMs);
                    counters.views.increment();
                }

                // Impressions: tighter angle + sustained gaze >= 1s.
                if (dot >= BannerVisibility.IMPRESSION_COS) {
                    LookState ls = lookStates.get(key);
                    if (ls == null) {
                        ls = new LookState();
                        ls.startMs = nowMs;
                        lookStates.put(key, ls);
                    }
                    ls.lastSeenMs = nowMs;
                    if (!ls.recorded && nowMs - ls.startMs >= IMPRESSION_GAZE_MS) {
                        ls.recorded = true;
                        counters.impressions.increment();
                    }
                } else {
                    breakLook(p, anchorLsb, nowMs);
                }
            }
        }
    }

    private void breakLook(Player p, long anchorLsb, long nowMs) {
        long key = (((long) p.getEntityId()) << 32) ^ anchorLsb;
        LookState ls = lookStates.get(key);
        if (ls != null && nowMs - ls.lastSeenMs > LOOK_BREAK_MS) {
            lookStates.remove(key);
        }
    }

    private void evictStale(long nowMs) {
        Iterator<Map.Entry<Long, Long>> it = lastViewTs.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > EVICT_TTL_MS) it.remove();
        }
        Iterator<Map.Entry<Long, LookState>> it2 = lookStates.entrySet().iterator();
        while (it2.hasNext()) {
            if (nowMs - it2.next().getValue().lastSeenMs > EVICT_TTL_MS) it2.remove();
        }
    }

    // ---------- session bookkeeping ----------

    private void recordSessionClose(long nowMs, long startMs) {
        long secs = Math.max(0L, (nowMs - startMs) / 1000L);
        String today = currentDate();
        DayBucket b = bucket(today);
        b.sessions.increment();
        b.sessionSecs.add(secs);
    }

    // ---------- flush ----------

    private void tryFlush() {
        try {
            flushOnce();
        } catch (Throwable t) {
            plugin.getLogger().fine("metrics flush failed: " + t.getMessage());
        }
    }

    private void flushOnce() throws Exception {
        if (!plugin.isConnected()) return;
        if (buckets.isEmpty()) return;

        // Snapshot + reset by removing exhausted buckets atomically. We snapshot
        // values first; if the POST fails we re-add the deltas back so they
        // accumulate into the next flush.
        Map<String, SnapshotBucket> snapshot = new HashMap<String, SnapshotBucket>();
        String today = currentDate();
        for (Map.Entry<String, DayBucket> e : buckets.entrySet()) {
            String date = e.getKey();
            DayBucket b = e.getValue();
            SnapshotBucket s = b.snapshotAndReset();
            if (s.isEmpty()) {
                if (!date.equals(today)) buckets.remove(date); // sealed empty older day
                continue;
            }
            snapshot.put(date, s);
        }
        if (snapshot.isEmpty()) return;

        String body = encode(snapshot);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L;
        String bodyHash = sha256Hex(bytes);
        String canonical = plugin.getServerId() + "|" + ts + "|POST|/metrics/daily|" + bodyHash;
        String sig = hmacSha256Hex(plugin.getServerSecret(), canonical);

        boolean ok = false;
        try {
            URL url = new URL(plugin.getApiUrl() + "/metrics/daily");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Server-Id", plugin.getServerId());
            conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
            conn.setRequestProperty("X-Signature", sig);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            try { out.write(bytes); } finally { out.close(); }
            int status = conn.getResponseCode();
            ok = (status >= 200 && status < 300);
            if (!ok) plugin.getLogger().warning("metrics flush HTTP " + status);
        } catch (Exception ex) {
            plugin.getLogger().fine("metrics flush IO: " + ex.getMessage());
        }

        if (!ok) reapply(snapshot);
    }

    private void reapply(Map<String, SnapshotBucket> snapshot) {
        for (Map.Entry<String, SnapshotBucket> e : snapshot.entrySet()) {
            DayBucket b = bucket(e.getKey());
            SnapshotBucket s = e.getValue();
            b.sessions.add(s.sessions);
            b.sessionSecs.add(s.sessionSecs);
            for (Map.Entry<Integer, long[]> ad : s.perAd.entrySet()) {
                AdCounters c = b.counters(ad.getKey());
                long[] v = ad.getValue();
                c.views.add(v[0]);
                c.impressions.add(v[1]);
                c.serverClicks.add(v[2]);
            }
        }
    }

    private static String encode(Map<String, SnapshotBucket> snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"buckets\":[");
        boolean first = true;
        for (Map.Entry<String, SnapshotBucket> e : snapshot.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            SnapshotBucket s = e.getValue();
            sb.append("{\"date\":\"").append(e.getKey()).append("\"");
            if (s.sessions != 0) sb.append(",\"sessions\":").append(s.sessions);
            if (s.sessionSecs != 0) sb.append(",\"sessionSeconds\":").append(s.sessionSecs);
            if (!s.perAd.isEmpty()) {
                sb.append(",\"perAd\":{");
                boolean first2 = true;
                for (Map.Entry<Integer, long[]> ad : s.perAd.entrySet()) {
                    if (!first2) sb.append(",");
                    first2 = false;
                    long[] v = ad.getValue();
                    sb.append("\"").append(ad.getKey()).append("\":{");
                    sb.append("\"views\":").append(v[0]);
                    sb.append(",\"impressions\":").append(v[1]);
                    sb.append(",\"serverClicks\":").append(v[2]);
                    sb.append("}");
                }
                sb.append("}");
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------- helpers ----------

    private DayBucket bucket(String date) {
        DayBucket b = buckets.get(date);
        if (b != null) return b;
        DayBucket nb = new DayBucket();
        DayBucket prev = buckets.putIfAbsent(date, nb);
        return prev != null ? prev : nb;
    }

    private String currentDate() {
        return dateFmt.format(new Date());
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static String hmacSha256Hex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return hex(raw);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return hex(d.digest(data));
    }

    private static String hex(byte[] raw) {
        StringBuilder h = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            h.append(Character.forDigit((b >> 4) & 0xF, 16));
            h.append(Character.forDigit(b & 0xF, 16));
        }
        return h.toString();
    }

    // ---------- inner data ----------

    private static final class DayBucket {
        final LongAdder sessions = new LongAdder();
        final LongAdder sessionSecs = new LongAdder();
        final Map<Integer, AdCounters> perAd = new ConcurrentHashMap<Integer, AdCounters>();

        AdCounters counters(int adImageId) {
            AdCounters c = perAd.get(adImageId);
            if (c != null) return c;
            AdCounters nc = new AdCounters();
            AdCounters prev = perAd.putIfAbsent(adImageId, nc);
            return prev != null ? prev : nc;
        }

        SnapshotBucket snapshotAndReset() {
            SnapshotBucket s = new SnapshotBucket();
            s.sessions = sessions.sumThenReset();
            s.sessionSecs = sessionSecs.sumThenReset();
            for (Iterator<Map.Entry<Integer, AdCounters>> it = perAd.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, AdCounters> e = it.next();
                AdCounters c = e.getValue();
                long v = c.views.sumThenReset();
                long i = c.impressions.sumThenReset();
                long sc = c.serverClicks.sumThenReset();
                if (v != 0 || i != 0 || sc != 0) {
                    s.perAd.put(e.getKey(), new long[] {v, i, sc});
                }
            }
            return s;
        }
    }

    private static final class AdCounters {
        final LongAdder views = new LongAdder();
        final LongAdder impressions = new LongAdder();
        final LongAdder serverClicks = new LongAdder();
    }

    private static final class SnapshotBucket {
        long sessions;
        long sessionSecs;
        final Map<Integer, long[]> perAd = new HashMap<Integer, long[]>();
        boolean isEmpty() { return sessions == 0 && sessionSecs == 0 && perAd.isEmpty(); }
    }

    private static final class LookState {
        long startMs;
        long lastSeenMs;
        boolean recorded;
    }
}
