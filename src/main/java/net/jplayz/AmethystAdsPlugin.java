package net.jplayz;

import net.jplayz.metrics.MetricsTracker;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AmethystAdsPlugin extends JavaPlugin implements Listener {

    private static final String DEFAULT_API_URL = "https://api.jplayz.net";
    private static final String WEBSITE_URL = "https://jplayz.net";
    private static final String AD_ITEM_NAME = "amethystads ad tool";
    private static final Material AD_ITEM_MATERIAL = Material.BLAZE_ROD;
    private static final long ROTATION_SLOT_MS = 20_000L;
    private static final double ATTENTION_DOT = 0.93;
    private static final double ATTENTION_MAX_DIST = 32.0;
    private static final int AD_SIZE = 256;
    private static final int QUADRANT_SIZE = 128;
    private static final String GITHUB_REPO = "jplayz2468/amethystads";
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final long UPDATE_CHECK_PERIOD_TICKS = 30L * 60L * 20L;

    private String serverId;
    private String serverSecret;
    private String apiUrl;

    private volatile List<String> currentAdIds = Collections.emptyList();
    private volatile String currentImageBase = "";
    private volatile long renderedRotationSlot = Long.MIN_VALUE;
    private volatile String lastPollFailure = "";
    private volatile boolean connected = false;
    private volatile boolean adsHidden = false;
    private final Map<String, MapView> mapCache = new ConcurrentHashMap<String, MapView>();
    private final Map<String, QuadRenderer> mapRenderers = new ConcurrentHashMap<String, QuadRenderer>();
    private volatile String lastImpressionFlushFailure = "";
    private volatile String latestReleaseVersion = "";
    private volatile boolean updateStaged = false;
    private volatile String lastUpdateCheckFailure = "";

    private static final class QuadRenderer extends MapRenderer {
        private final int srcX;
        private final int srcY;
        private volatile BufferedImage image;
        private volatile boolean drawn;
        QuadRenderer(int quadrant) {
            this.srcX = (quadrant == 1 || quadrant == 3) ? QUADRANT_SIZE : 0;
            this.srcY = (quadrant == 2 || quadrant == 3) ? QUADRANT_SIZE : 0;
        }
        void setImage(BufferedImage img) {
            this.image = img;
            this.drawn = false;
        }
        @Override public void render(MapView v, MapCanvas canvas, Player p) {
            BufferedImage img = image;
            if (!drawn && img != null) {
                canvas.drawImage(0, 0, img.getSubimage(srcX, srcY, QUADRANT_SIZE, QUADRANT_SIZE));
                drawn = true;
            }
        }
    }
    private final Map<UUID, String> frameAdAssignments = new ConcurrentHashMap<UUID, String>();
    private final Set<UUID> trackedFrames = Collections.synchronizedSet(new HashSet<UUID>());
    private final Map<UUID, Integer> frameQuadrant = new ConcurrentHashMap<UUID, Integer>();
    private final Map<UUID, UUID> frameAnchor = new ConcurrentHashMap<UUID, UUID>();
    private final Map<UUID, UUID[]> groupFrames = new ConcurrentHashMap<UUID, UUID[]>();
    private final Map<String, Long> slotByPlayerAd = new ConcurrentHashMap<String, Long>();
    private final Map<String, Integer> pendingImpressionCounts = new ConcurrentHashMap<String, Integer>();
    private MetricsTracker metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();

        serverId = cfg.getString("server-id", "");
        if (serverId == null || serverId.isEmpty()) {
            serverId = UUID.randomUUID().toString();
            cfg.set("server-id", serverId);
        }
        serverSecret = cfg.getString("server-secret", "");
        if (serverSecret == null || serverSecret.isEmpty()) {
            serverSecret = randomHex(32);
            cfg.set("server-secret", serverSecret);
        }
        apiUrl = cfg.getString("api-url", "");
        if (apiUrl == null || apiUrl.isEmpty()) apiUrl = DEFAULT_API_URL;
        adsHidden = cfg.getBoolean("ads-hidden", false);
        saveConfig();

        getLogger().info("amethystADS server-id " + serverId);
        loadPersistentFrames();
        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override public void run() { pollOnce(); }
        }.runTaskTimerAsynchronously(this, 20L, 100L);

        new BukkitRunnable() {
            @Override public void run() { flushImpressions(); }
        }.runTaskTimerAsynchronously(this, 600L, 600L);

        new BukkitRunnable() {
            @Override public void run() { scanAttention(); }
        }.runTaskTimer(this, 100L, 100L);

        new BukkitRunnable() {
            @Override public void run() { checkForUpdates(); }
        }.runTaskTimerAsynchronously(this, 100L, UPDATE_CHECK_PERIOD_TICKS);

        try {
            metrics = new MetricsTracker(this);
            metrics.start();
        } catch (Throwable t) {
            getLogger().warning("metrics disabled: " + t.getMessage());
            metrics = null;
        }
    }

    @Override
    public void onDisable() {
        savePersistentFrames();
        flushImpressions();
        if (metrics != null) {
            try { metrics.shutdown(); } catch (Throwable ignored) {}
        }
    }

    // ---------- accessors used by net.jplayz.metrics.MetricsTracker ----------

    public Map<UUID, UUID[]> getGroupFrames() { return groupFrames; }
    public String getFrameAdAssignment(UUID frameUuid) { return frameAdAssignments.get(frameUuid); }
    public String getServerId() { return serverId; }
    public String getServerSecret() { return serverSecret; }
    public String getApiUrl() { return apiUrl; }
    public boolean isAdsHidden() { return adsHidden; }
    public boolean isConnected() { return connected; }

    private static final String[][] SUBCOMMANDS = {
        {"help",     "list all amethystADS commands"},
        {"register", "get a registration token to link this server at jplayz.net"},
        {"give",     "receive the ad placement tool"},
        {"reload",   "clear image cache and reload ads"},
        {"status",   "show connection status and current ad info"},
        {"toggle",   "hide/show all ads on this server (hidden = no earnings)"},
        {"update",   "check github.com/" + GITHUB_REPO + " for a new release now"},
    };

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("amethystads")) return false;
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "help":     return handleHelp(sender);
            case "?":        return handleHelp(sender);
            case "register": return handleRegister(sender);
            case "give":     return handleGive(sender);
            case "reload":   return handleReload(sender);
            case "status":   return handleStatus(sender);
            case "toggle":   return handleToggle(sender);
            case "update":   return handleUpdate(sender);
            default:         return handleDefault(sender);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("amethystads")) return Collections.emptyList();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<String>();
            for (String[] sc : SUBCOMMANDS) {
                if (sc[0].startsWith(partial)) out.add(sc[0]);
            }
            return out;
        }
        return Collections.emptyList();
    }

    private boolean handleDefault(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        if (connected) {
            sender.sendMessage(ChatColor.GREEN + "amethystADS connected. " + ChatColor.GRAY
                    + "Use " + ChatColor.WHITE + "/aa give" + ChatColor.GRAY
                    + " for the ad tool, or sign in at jplayz.net to manage your account and view earnings.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "amethystADS is not connected yet.");
            if (!lastPollFailure.isEmpty())
                sender.sendMessage(ChatColor.GRAY + "  reason: " + ChatColor.RED + lastPollFailure);
            sender.sendMessage(ChatColor.GRAY + "  create an account at " + ChatColor.WHITE + "jplayz.net"
                    + ChatColor.GRAY + ", then run " + ChatColor.WHITE + "/aa register"
                    + ChatColor.GRAY + " and paste the token on the site to link this server.");
        }
        sendWebsiteLink(sender);
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "amethystADS status");
        sender.sendMessage(ChatColor.GRAY + "  connection: " + (connected
                ? ChatColor.GREEN + "connected"
                : ChatColor.RED + "not connected"));
        if (!connected && !lastPollFailure.isEmpty())
            sender.sendMessage(ChatColor.GRAY + "  last error: " + ChatColor.RED + lastPollFailure);
        sender.sendMessage(ChatColor.GRAY + "  api: " + ChatColor.WHITE + apiUrl);
        sender.sendMessage(ChatColor.GRAY + "  server-id: " + ChatColor.WHITE + serverId);
        sender.sendMessage(ChatColor.GRAY + "  ads visible: " + (adsHidden
                ? ChatColor.RED + "hidden (no earnings)"
                : ChatColor.GREEN + "shown"));
        sender.sendMessage(ChatColor.GRAY + "  active ads: " + ChatColor.WHITE + currentAdIds.size());
        sender.sendMessage(ChatColor.GRAY + "  placed ad groups: " + ChatColor.WHITE + groupFrames.size());
        int pending;
        synchronized (pendingImpressionCounts) {
            pending = 0;
            for (Integer v : pendingImpressionCounts.values()) pending += (v == null ? 0 : v);
        }
        sender.sendMessage(ChatColor.GRAY + "  pending impressions: " + ChatColor.WHITE + pending);
        if (!lastImpressionFlushFailure.isEmpty())
            sender.sendMessage(ChatColor.GRAY + "  last impression flush error: "
                    + ChatColor.RED + lastImpressionFlushFailure);
        sender.sendMessage(ChatColor.GRAY + "  version: " + ChatColor.WHITE
                + getDescription().getVersion()
                + (latestReleaseVersion.isEmpty() ? "" :
                        ChatColor.GRAY + " (latest on github.com/" + GITHUB_REPO + ": "
                        + ChatColor.WHITE + latestReleaseVersion + ChatColor.GRAY + ")"));
        if (updateStaged)
            sender.sendMessage(ChatColor.GOLD + "  update " + ChatColor.WHITE + latestReleaseVersion
                    + ChatColor.GOLD + " staged in plugins/update/ — restart the server to apply.");
        if (!lastUpdateCheckFailure.isEmpty())
            sender.sendMessage(ChatColor.GRAY + "  last update-check error: "
                    + ChatColor.RED + lastUpdateCheckFailure);
        if (!connected) {
            sender.sendMessage(ChatColor.GRAY + "  manage your account and link this server at "
                    + ChatColor.WHITE + "jplayz.net");
            sendWebsiteLink(sender);
        }
        return true;
    }

    private boolean handleUpdate(final CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        final String currentVersion = getDescription().getVersion();
        sender.sendMessage(ChatColor.YELLOW + "amethystADS: checking github.com/" + GITHUB_REPO + " for a newer release...");
        new BukkitRunnable() {
            @Override public void run() {
                final boolean wasStaged = updateStaged;
                final String prevLatest = latestReleaseVersion;
                checkForUpdates();
                Bukkit.getScheduler().runTask(AmethystAdsPlugin.this, new Runnable() {
                    @Override public void run() {
                        if (!lastUpdateCheckFailure.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "  update check failed: " + lastUpdateCheckFailure);
                            return;
                        }
                        if (updateStaged && (!wasStaged || !latestReleaseVersion.equals(prevLatest))) {
                            sender.sendMessage(ChatColor.GREEN + "  update " + ChatColor.WHITE + latestReleaseVersion
                                    + ChatColor.GREEN + " downloaded to plugins/update/ — restart the server to apply.");
                        } else if (updateStaged) {
                            sender.sendMessage(ChatColor.YELLOW + "  update " + ChatColor.WHITE + latestReleaseVersion
                                    + ChatColor.YELLOW + " is already staged — restart the server to apply.");
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "  up to date (current "
                                    + ChatColor.WHITE + currentVersion + ChatColor.GREEN
                                    + (latestReleaseVersion.isEmpty() ? "" :
                                            ", latest " + ChatColor.WHITE + latestReleaseVersion + ChatColor.GREEN)
                                    + ").");
                        }
                    }
                });
            }
        }.runTaskAsynchronously(this);
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "amethystADS commands");
        for (String[] sc : SUBCOMMANDS) {
            sender.sendMessage(ChatColor.AQUA + "  /aa " + sc[0]
                    + ChatColor.GRAY + " — " + sc[1]);
        }
        sender.sendMessage(ChatColor.GRAY + "manage your account at " + ChatColor.WHITE + WEBSITE_URL);
        return true;
    }

    private boolean handleToggle(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        adsHidden = !adsHidden;
        getConfig().set("ads-hidden", adsHidden);
        saveConfig();
        if (adsHidden) {
            clearAllPlacedFrames();
            sender.sendMessage(ChatColor.YELLOW + "amethystADS ads are now " + ChatColor.RED + "HIDDEN"
                    + ChatColor.YELLOW + ".");
            sender.sendMessage(ChatColor.GOLD + "  warning: " + ChatColor.GRAY
                    + "while hidden, this server will " + ChatColor.RED + "not earn any money"
                    + ChatColor.GRAY + " — no impressions or clicks are reported.");
            sender.sendMessage(ChatColor.GRAY + "  run " + ChatColor.WHITE + "/aa toggle"
                    + ChatColor.GRAY + " again to restore ads.");
        } else {
            long slot = System.currentTimeMillis() / ROTATION_SLOT_MS;
            updateAllFrames(currentAdIds, slot);
            sender.sendMessage(ChatColor.GREEN + "amethystADS ads are now SHOWN.");
            if (currentAdIds.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "  no ads loaded yet — they will appear after the next poll.");
            }
        }
        return true;
    }

    private void clearAllPlacedFrames() {
        for (UUID[] group : groupFrames.values()) {
            if (group == null) continue;
            for (UUID uid : group) {
                if (uid == null) continue;
                Entity ent = Bukkit.getEntity(uid);
                if (ent instanceof ItemFrame) ((ItemFrame) ent).setItem(null);
                frameAdAssignments.remove(uid);
            }
        }
    }

    private boolean handleRegister(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        String token = buildRegistrationToken();
        sender.sendMessage(ChatColor.YELLOW + "amethystADS registration token "
                + ChatColor.GRAY + "(create an account at " + ChatColor.WHITE + "jplayz.net"
                + ChatColor.GRAY + " and paste this token there to link this server):");
        if (sender instanceof Player) {
            TextComponent button = new TextComponent("[click to copy]");
            button.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            button.setUnderlined(true);
            button.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token));
            TextComponent spacer = new TextComponent("  ");
            TextComponent plain = new TextComponent(token);
            plain.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            ((Player) sender).spigot().sendMessage(button, spacer, plain);
        } else {
            sender.sendMessage(ChatColor.WHITE + token);
        }
        sendWebsiteLink(sender);
        return true;
    }

    private String buildRegistrationToken() {
        String payload = serverId + ":" + serverSecret + ":" + randomHex(8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private boolean handleGive(CommandSender sender) {
        if (!(sender instanceof Player)) { sender.sendMessage("Must be a player."); return true; }
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        if (!connected) {
            sender.sendMessage(ChatColor.YELLOW + "amethystADS is not set up yet — create an account at jplayz.net and link this server with /aa register first.");
            sendWebsiteLink(sender);
            return true;
        }
        ItemStack item = new ItemStack(AD_ITEM_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + AD_ITEM_NAME);
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "right-click a surface to place an ad");
            lore.add(ChatColor.GRAY + "sneak + right-click an ad to remove it");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        ((Player) sender).getInventory().addItem(item);
        sender.sendMessage(ChatColor.GREEN + "ad tool added to inventory");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        for (Map.Entry<String, MapView> e : mapCache.entrySet()) {
            QuadRenderer r = mapRenderers.get(e.getKey());
            if (r != null) try { e.getValue().removeRenderer(r); } catch (Exception ignored) { }
            if (r != null) r.image = null;
        }
        mapCache.clear();
        mapRenderers.clear();
        currentAdIds = Collections.emptyList();
        currentImageBase = "";
        renderedRotationSlot = Long.MIN_VALUE;
        sender.sendMessage(ChatColor.YELLOW + "amethystADS cache cleared, reloading...");
        new BukkitRunnable() {
            @Override public void run() { pollOnce(); }
        }.runTaskAsynchronously(this);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToolInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isAdItem(item)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);

        Player player = e.getPlayer();
        if (!player.isOp() && !player.hasPermission("amethystads.admin")) {
            player.sendMessage(ChatColor.RED + "Permission denied");
            return;
        }
        if (!connected) {
            player.sendMessage(ChatColor.YELLOW + "amethystADS is not set up yet.");
            sendWebsiteLink(player);
            return;
        }
        if (currentAdIds.isEmpty()) {
            player.sendMessage(ChatColor.RED + "ad not loaded yet — try again in a few seconds");
            return;
        }
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        BlockFace face = e.getBlockFace();
        if (face == BlockFace.SELF) return;

        BlockFace right = rightOf(face);
        Block targetBL = clicked.getRelative(face);
        Block targetBR = targetBL.getRelative(right);
        Block targetTL = targetBL.getRelative(BlockFace.UP);
        Block targetTR = targetBR.getRelative(BlockFace.UP);
        Block[] targets = {targetTL, targetTR, targetBL, targetBR};

        UUID[] group = new UUID[4];
        try {
            for (int q = 0; q < 4; q++) {
                Location spawnLoc = targets[q].getLocation().add(0.5, 0.5, 0.5);
                ItemFrame fr = (ItemFrame) clicked.getWorld().spawnEntity(spawnLoc, EntityType.ITEM_FRAME);
                fr.setFacingDirection(face, true);
                group[q] = fr.getUniqueId();
                trackedFrames.add(group[q]);
                frameQuadrant.put(group[q], q);
            }
        } catch (Exception ex) {
            for (int q = 0; q < 4; q++) {
                if (group[q] == null) continue;
                trackedFrames.remove(group[q]);
                frameQuadrant.remove(group[q]);
                Entity ent = Bukkit.getEntity(group[q]);
                if (ent != null) ent.remove();
            }
            player.sendMessage(ChatColor.RED + "couldn't place ad here: " + ex.getMessage());
            return;
        }
        UUID anchor = group[2];
        groupFrames.put(anchor, group);
        for (UUID uid : group) frameAnchor.put(uid, anchor);
        updateGroup(anchor, currentAdIds, System.currentTimeMillis() / ROTATION_SLOT_MS);
        savePersistentFrames();
        player.sendMessage(ChatColor.GREEN + "ad placed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame)) return;
        ItemFrame frame = (ItemFrame) e.getRightClicked();
        if (!trackedFrames.contains(frame.getUniqueId())) return;
        e.setCancelled(true);
        Player player = e.getPlayer();

        ItemStack held = e.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (player.isSneaking() && isAdItem(held)
                && (player.isOp() || player.hasPermission("amethystads.admin"))) {
            UUID clickedId = frame.getUniqueId();
            UUID anchor = frameAnchor.get(clickedId);
            UUID[] group = anchor != null ? groupFrames.get(anchor) : null;
            if (group != null) {
                for (UUID uid : group) {
                    if (uid == null) continue;
                    trackedFrames.remove(uid);
                    frameQuadrant.remove(uid);
                    frameAnchor.remove(uid);
                    frameAdAssignments.remove(uid);
                    Entity ent = Bukkit.getEntity(uid);
                    if (ent instanceof ItemFrame) ent.remove();
                }
                groupFrames.remove(anchor);
            } else {
                trackedFrames.remove(clickedId);
                frame.remove();
            }
            savePersistentFrames();
            player.sendMessage(ChatColor.YELLOW + "ad removed");
            return;
        }
        sendAdMessage(player, frame.getUniqueId());
        if (metrics != null) {
            try { metrics.onServerClick(frameAdAssignments.get(frame.getUniqueId())); }
            catch (Throwable ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ItemFrame)) return;
        if (!trackedFrames.contains(e.getEntity().getUniqueId())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player)
            sendAdMessage((Player) e.getDamager(), e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!(e.getEntity() instanceof ItemFrame)) return;
        if (!trackedFrames.contains(e.getEntity().getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (currentAdIds.isEmpty()) return;
        Set<UUID> anchorsToUpdate = new HashSet<UUID>();
        for (Entity ent : e.getChunk().getEntities()) {
            if (ent instanceof ItemFrame && trackedFrames.contains(ent.getUniqueId())) {
                UUID anchor = frameAnchor.get(ent.getUniqueId());
                if (anchor != null) anchorsToUpdate.add(anchor);
            }
        }
        long slot = System.currentTimeMillis() / ROTATION_SLOT_MS;
        for (UUID anchor : anchorsToUpdate) updateGroup(anchor, currentAdIds, slot);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!p.isOp() && !p.hasPermission("amethystads.admin")) return;
        new BukkitRunnable() {
            @Override public void run() {
                if (!connected) {
                    p.sendMessage(ChatColor.YELLOW + "amethystADS is not connected — create an account at jplayz.net and link this server (run /aa register):");
                    sendWebsiteLink(p);
                }
                if (updateStaged) {
                    p.sendMessage(ChatColor.GOLD + "[amethystADS] " + ChatColor.YELLOW
                            + "update " + ChatColor.WHITE + latestReleaseVersion
                            + ChatColor.YELLOW + " staged — restart the server to apply.");
                }
            }
        }.runTaskLater(this, 60L);
    }

    private boolean isAdItem(ItemStack item) {
        if (item == null || item.getType() != AD_ITEM_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        return ChatColor.stripColor(meta.getDisplayName()).equals(AD_ITEM_NAME);
    }

    private void sendAdMessage(Player p, UUID frameId) {
        if (adsHidden) return;
        String adId = frameAdAssignments.get(frameId);
        if (adId == null || adId.isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "no ad configured");
            return;
        }
        String url = makeClickUrl(p, adId);
        if (url == null) {
            p.sendMessage(ChatColor.RED + "could not build click link");
            return;
        }
        ClickEvent click = new ClickEvent(ClickEvent.Action.OPEN_URL, url);
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[] { new TextComponent("Click to open in your browser") });

        TextComponent header = new TextComponent("\u25AC\u25AC\u25AC\u25AC Sponsor link \u25AC\u25AC\u25AC\u25AC");
        header.setColor(net.md_5.bungee.api.ChatColor.DARK_AQUA);
        header.setBold(true);
        header.setClickEvent(click);
        header.setHoverEvent(hover);

        TextComponent thanks = new TextComponent("Thanks for checking this out!");
        thanks.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        thanks.setClickEvent(click);
        thanks.setHoverEvent(hover);

        TextComponent action = new TextComponent("  \u25B6 Click anywhere here to open it in your browser");
        action.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        action.setUnderlined(true);
        action.setClickEvent(click);
        action.setHoverEvent(hover);

        p.spigot().sendMessage(header);
        p.spigot().sendMessage(thanks);
        p.spigot().sendMessage(action);
    }

    private String makeClickUrl(Player player, String adId) {
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String pid = player.getUniqueId().toString();
            String nonce = randomHex(8);
            String canonical = serverId + "|" + pid + "|" + adId + "|" + nonce + "|" + ts;
            String sig = hmacSha256Hex(serverSecret, canonical);
            String tokenPayload = serverId + "|" + pid + "|" + adId + "|" + nonce + "|" + ts + "|" + sig;
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(tokenPayload.getBytes(StandardCharsets.UTF_8));
            return apiUrl + "/c/" + token;
        } catch (Exception e) {
            return null;
        }
    }

    private void pollOnce() {
        try {
            String body = fetchState();
            Map<String, String> map = parseForm(body);
            final List<String> adIds = parseAdIds(map.get("adIds"));
            final String imageBase = nz(map.get("imageBase"));
            final long slot = System.currentTimeMillis() / ROTATION_SLOT_MS;
            connected = true;
            lastPollFailure = "";

            if (adIds.isEmpty() || imageBase.isEmpty()) {
                if (!adIds.equals(currentAdIds)) {
                    final List<String> finalAdIds = adIds;
                    Bukkit.getScheduler().runTask(this, new Runnable() {
                        @Override public void run() {
                            evictStaleAds(finalAdIds);
                            currentAdIds = finalAdIds;
                        }
                    });
                }
                return;
            }

            List<String> needed = computeNeededAdIds(adIds, slot);
            if (adIds.equals(currentAdIds) && imageBase.equals(currentImageBase)
                    && renderedRotationSlot == slot && haveAllRendered(needed)) {
                return;
            }

            final Map<String, BufferedImage> downloaded = new HashMap<String, BufferedImage>();
            for (String adId : needed) {
                if (mapCache.containsKey(adId + "_0")) continue;
                downloaded.put(adId, downloadImage(imageBase + adId));
            }

            Bukkit.getScheduler().runTask(this, new Runnable() {
                @Override public void run() {
                    for (Map.Entry<String, BufferedImage> entry : downloaded.entrySet()) {
                        putAdImage(entry.getKey(), entry.getValue());
                    }
                    downloaded.clear();
                    evictStaleAds(adIds);
                    currentAdIds = adIds;
                    currentImageBase = imageBase;
                    renderedRotationSlot = slot;
                    updateAllFrames(adIds, slot);
                }
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            connected = false;
            if (!msg.equals(lastPollFailure)) {
                lastPollFailure = msg;
                getLogger().warning("amethystADS not connected (server-id " + serverId
                        + "): " + msg + " — set this server up at " + WEBSITE_URL);
            }
        }
    }

    private void updateAllFrames(List<String> adIds, long slot) {
        if (adIds.isEmpty()) return;
        for (UUID anchor : new ArrayList<UUID>(groupFrames.keySet())) {
            updateGroup(anchor, adIds, slot);
        }
    }

    private void updateGroup(UUID anchor, List<String> adIds, long slot) {
        UUID[] group = groupFrames.get(anchor);
        if (group == null) return;
        if (adsHidden) {
            for (UUID uid : group) {
                if (uid == null) continue;
                Entity ent = Bukkit.getEntity(uid);
                if (ent instanceof ItemFrame) ((ItemFrame) ent).setItem(null);
                frameAdAssignments.remove(uid);
            }
            return;
        }
        String adId = selectAdForFrame(anchor, adIds, slot);
        for (int q = 0; q < 4; q++) {
            if (group[q] == null) continue;
            Entity ent = Bukkit.getEntity(group[q]);
            if (!(ent instanceof ItemFrame)) continue;
            MapView view = mapCache.get(adId + "_" + q);
            if (view == null) continue;
            frameAdAssignments.put(group[q], adId);
            ((ItemFrame) ent).setItem(buildMapItem(view));
        }
    }

    private List<String> computeNeededAdIds(List<String> adIds, long slot) {
        if (adIds.isEmpty()) return Collections.emptyList();
        Set<String> needed = new HashSet<String>();
        Set<UUID> anchors = groupFrames.keySet();
        if (anchors.isEmpty()) {
            needed.add(adIds.get((int) Math.floorMod(slot, (long) adIds.size())));
        } else {
            for (UUID anchor : anchors) needed.add(selectAdForFrame(anchor, adIds, slot));
        }
        return new ArrayList<String>(needed);
    }

    private static BlockFace rightOf(BlockFace face) {
        switch (face) {
            case NORTH: return BlockFace.WEST;
            case SOUTH: return BlockFace.EAST;
            case EAST:  return BlockFace.NORTH;
            case WEST:  return BlockFace.SOUTH;
            default:    return BlockFace.EAST;
        }
    }

    private static String selectAdForFrame(UUID frameId, List<String> adIds, long slot) {
        if (adIds.isEmpty()) return "";
        long mixed = frameId.getMostSignificantBits() ^ frameId.getLeastSignificantBits();
        int offset = Math.floorMod((int) (mixed ^ (mixed >>> 32)), adIds.size());
        int index = Math.floorMod((int) (slot + offset), adIds.size());
        return adIds.get(index);
    }

    private static ItemStack buildMapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void evictStaleAds(List<String> activeAdIds) {
        Set<String> active = new HashSet<String>(activeAdIds);
        Set<String> seenAdIds = new HashSet<String>();
        for (String key : new ArrayList<String>(mapCache.keySet())) {
            int us = key.lastIndexOf('_');
            if (us < 0) continue;
            String adId = key.substring(0, us);
            if (active.contains(adId)) continue;
            seenAdIds.add(adId);
        }
        for (String adId : seenAdIds) {
            for (int q = 0; q < 4; q++) {
                String key = adId + "_" + q;
                MapView view = mapCache.remove(key);
                QuadRenderer r = mapRenderers.remove(key);
                if (view != null && r != null) {
                    try { view.removeRenderer(r); } catch (Exception ignored) { }
                }
                if (r != null) r.image = null;
            }
        }
    }

    private boolean haveAllRendered(List<String> adIds) {
        for (String adId : adIds) {
            if (!mapCache.containsKey(adId + "_0")) return false;
        }
        return true;
    }

    private void putAdImage(String adId, BufferedImage fullImage) {
        for (int q = 0; q < 4; q++) {
            String key = adId + "_" + q;
            QuadRenderer r = mapRenderers.get(key);
            if (r == null || mapCache.get(key) == null) {
                r = new QuadRenderer(q);
                World world = Bukkit.getWorlds().get(0);
                MapView view = Bukkit.createMap(world);
                for (MapRenderer existing : new ArrayList<MapRenderer>(view.getRenderers())) {
                    view.removeRenderer(existing);
                }
                view.addRenderer(r);
                mapCache.put(key, view);
                mapRenderers.put(key, r);
            }
            r.setImage(fullImage);
        }
    }

    private void scanAttention() {
        if (adsHidden) return;
        if (currentAdIds.isEmpty()) return;
        long currentSlot = System.currentTimeMillis() / ROTATION_SLOT_MS;
        for (UUID anchor : new ArrayList<UUID>(groupFrames.keySet())) {
            UUID[] group = groupFrames.get(anchor);
            if (group == null) continue;
            String adId = frameAdAssignments.get(anchor);
            if (adId == null || adId.isEmpty()) continue;
            double sumX = 0, sumY = 0, sumZ = 0;
            int cnt = 0;
            World world = null;
            for (UUID uid : group) {
                if (uid == null) continue;
                Entity ent = Bukkit.getEntity(uid);
                if (!(ent instanceof ItemFrame)) continue;
                if (world == null) world = ent.getWorld();
                sumX += ent.getLocation().getX();
                sumY += ent.getLocation().getY();
                sumZ += ent.getLocation().getZ();
                cnt++;
            }
            if (cnt == 0 || world == null) continue;
            Location center = new Location(world, sumX / cnt, sumY / cnt, sumZ / cnt);
            for (Player p : world.getPlayers()) {
                Location eye = p.getEyeLocation();
                double dist = eye.distance(center);
                if (dist > ATTENTION_MAX_DIST || dist < 0.5) continue;
                Vector toFrame = center.toVector().subtract(eye.toVector());
                if (toFrame.lengthSquared() < 1e-6) continue;
                toFrame.normalize();
                Vector look = eye.getDirection();
                if (look.lengthSquared() < 1e-6) continue;
                look.normalize();
                if (toFrame.dot(look) < ATTENTION_DOT) continue;
                double rayDist = dist - 0.6;
                if (rayDist > 0.1) {
                    try {
                        RayTraceResult hit = world.rayTraceBlocks(eye, look, rayDist);
                        if (hit != null) continue;
                    } catch (Exception ignored) { }
                }
                String key = p.getUniqueId().toString() + "|" + adId;
                Long seen = slotByPlayerAd.get(key);
                if (seen != null && seen == currentSlot) continue;
                slotByPlayerAd.put(key, currentSlot);
                synchronized (pendingImpressionCounts) {
                    Integer cur = pendingImpressionCounts.get(adId);
                    pendingImpressionCounts.put(adId, (cur == null ? 0 : cur) + 1);
                }
            }
        }
        long cutoff = currentSlot - 2;
        Iterator<Map.Entry<String, Long>> it = slotByPlayerAd.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) it.remove();
        }
    }

    private void flushImpressions() {
        if (!connected) return;
        Map<String, Integer> snapshot;
        synchronized (pendingImpressionCounts) {
            if (pendingImpressionCounts.isEmpty()) return;
            snapshot = new HashMap<String, Integer>(pendingImpressionCounts);
            pendingImpressionCounts.clear();
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"counts\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(jsonEsc(e.getKey())).append("\":").append(e.getValue());
        }
        json.append("}}");
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

        try {
            long ts = System.currentTimeMillis() / 1000L;
            URL url = new URL(apiUrl + "/impression");
            String bodyHash = sha256Hex(body);
            String canonical = serverId + "|" + ts + "|POST|/impression|" + bodyHash;
            String sig = hmacSha256Hex(serverSecret, canonical);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Server-Id", serverId);
            conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
            conn.setRequestProperty("X-Signature", sig);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            try { out.write(body); } finally { out.close(); }
            int status = conn.getResponseCode();
            if (status != 200) {
                String msg = "";
                InputStream err = conn.getErrorStream();
                if (err != null) msg = readAllText(err);
                if (status == 401) connected = false;
                String key = "HTTP " + status + ": " + msg;
                if (!key.equals(lastImpressionFlushFailure)) {
                    lastImpressionFlushFailure = key;
                    getLogger().warning("impression flush " + key);
                }
            } else {
                lastImpressionFlushFailure = "";
            }
        } catch (Exception e) {
            getLogger().fine("impression flush failed: " + e.getMessage());
        }
    }

    private void savePersistentFrames() {
        File f = new File(getDataFolder(), "frames.yml");
        YamlConfiguration cfg = new YamlConfiguration();
        List<String> groups = new ArrayList<String>();
        for (UUID[] group : groupFrames.values()) {
            groups.add(group[0] + "|" + group[1] + "|" + group[2] + "|" + group[3]);
        }
        cfg.set("groups", groups);
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            cfg.save(f);
        } catch (Exception e) {
            getLogger().warning("could not save frames: " + e.getMessage());
        }
    }

    private void loadPersistentFrames() {
        File f = new File(getDataFolder(), "frames.yml");
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        List<String> groups = cfg.getStringList("groups");
        int loaded = 0;
        for (String s : groups) {
            String[] parts = s.split("\\|");
            if (parts.length != 4) continue;
            UUID[] group = new UUID[4];
            try {
                for (int i = 0; i < 4; i++) group[i] = UUID.fromString(parts[i]);
            } catch (Exception ignored) { continue; }
            UUID anchor = group[2];
            groupFrames.put(anchor, group);
            for (int i = 0; i < 4; i++) {
                trackedFrames.add(group[i]);
                frameQuadrant.put(group[i], i);
                frameAnchor.put(group[i], anchor);
            }
            loaded++;
        }
        if (loaded > 0) getLogger().info("amethystADS loaded " + loaded + " ad group(s)");
    }

    private BufferedImage downloadImage(String imageUrl) throws Exception {
        long ts = System.currentTimeMillis() / 1000L;
        URL url = new URL(imageUrl);
        String canonical = serverId + "|" + ts + "|GET|" + signingPath(imageUrl);
        String sig = hmacSha256Hex(serverSecret, canonical);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("X-Server-Id", serverId);
        conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
        conn.setRequestProperty("X-Signature", sig);
        int status = conn.getResponseCode();
        if (status != 200) throw new RuntimeException("HTTP " + status);
        byte[] bytes = readAllBytes(conn.getInputStream());
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new RuntimeException("bad PNG");
        if (src.getWidth() == AD_SIZE && src.getHeight() == AD_SIZE) return src;
        BufferedImage scaled = new BufferedImage(AD_SIZE, AD_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, AD_SIZE, AD_SIZE, null);
        g.dispose();
        return scaled;
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private String fetchState() throws Exception {
        long ts = System.currentTimeMillis() / 1000L;
        URL url = new URL(apiUrl);
        String canonical = serverId + "|" + ts + "|GET|/";
        String sig = hmacSha256Hex(serverSecret, canonical);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("X-Server-Id", serverId);
        conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
        conn.setRequestProperty("X-Signature", sig);
        int status = conn.getResponseCode();
        if (status != 200) {
            InputStream err = conn.getErrorStream();
            String msg = (err == null) ? "" : readAllText(err);
            throw new RuntimeException("HTTP " + status + ": " + msg);
        }
        return readAllText(conn.getInputStream());
    }

    private static String readAllText(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        return sb.toString();
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> out = new HashMap<String, String>();
        if (s == null || s.isEmpty()) return out;
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static List<String> parseAdIds(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (String part : raw.split(",")) {
            String adId = nz(part).trim();
            if (!adId.isEmpty() && !out.contains(adId)) out.add(adId);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String urlEnc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String jsonEsc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private String signingPath(String fullUrl) throws Exception {
        String base = new URL(apiUrl).getPath();
        String path = new URL(fullUrl).getPath();
        if (!base.isEmpty() && !base.equals("/") && path.startsWith(base))
            path = path.substring(base.length());
        return path.isEmpty() ? "/" : path;
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

    private static String randomHex(int bytes) {
        SecureRandom rng = new SecureRandom();
        byte[] b = new byte[bytes];
        rng.nextBytes(b);
        return hex(b);
    }

    private void checkForUpdates() {
        String currentVersion = getDescription().getVersion();
        try {
            URL url = new URL(GITHUB_RELEASES_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "amethystads-plugin/" + currentVersion);
            int status = conn.getResponseCode();
            if (status != 200) {
                lastUpdateCheckFailure = "HTTP " + status;
                return;
            }
            String body = readAllText(conn.getInputStream());
            String tag = extractJsonString(body, "tag_name");
            if (tag == null) { lastUpdateCheckFailure = "no tag_name"; return; }
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            latestReleaseVersion = latest;
            lastUpdateCheckFailure = "";
            if (!isNewerVersion(latest, currentVersion)) return;
            if (updateStaged) { notifyAdminsOfUpdate(currentVersion, latest); return; }
            String assetUrl = findFirstJarAssetUrl(body);
            if (assetUrl == null) { lastUpdateCheckFailure = "no .jar asset"; return; }
            File updateDir = new File(getDataFolder().getParentFile(), "update");
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                lastUpdateCheckFailure = "cannot create plugins/update";
                return;
            }
            File target = new File(updateDir, getFile().getName());
            File tmp = new File(updateDir, getFile().getName() + ".part");
            downloadJarTo(assetUrl, tmp);
            if (target.exists() && !target.delete()) {
                lastUpdateCheckFailure = "cannot replace staged jar";
                return;
            }
            if (!tmp.renameTo(target)) {
                lastUpdateCheckFailure = "cannot rename staged jar";
                return;
            }
            updateStaged = true;
            getLogger().info("amethystADS update " + latest + " staged at "
                    + target.getAbsolutePath() + " — restart server to apply");
            notifyAdminsOfUpdate(currentVersion, latest);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastUpdateCheckFailure = msg;
            getLogger().fine("update check failed: " + msg);
        }
    }

    private void downloadJarTo(String assetUrl, File dest) throws Exception {
        URL u = new URL(assetUrl);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("User-Agent", "amethystads-plugin");
        int status = conn.getResponseCode();
        if (status != 200) throw new RuntimeException("HTTP " + status + " downloading update");
        InputStream in = conn.getInputStream();
        java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
            try { out.close(); } catch (Exception ignored) { }
        }
    }

    private void notifyAdminsOfUpdate(final String current, final String latest) {
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override public void run() {
                String msg = ChatColor.GOLD + "[amethystADS] " + ChatColor.YELLOW
                        + "update " + ChatColor.WHITE + latest + ChatColor.YELLOW
                        + " staged (currently " + ChatColor.WHITE + current + ChatColor.YELLOW
                        + ") — restart the server to apply.";
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp() || p.hasPermission("amethystads.admin")) p.sendMessage(msg);
                }
            }
        });
    }

    private static String extractJsonString(String json, String key) {
        String pat = "\"" + key + "\"";
        int idx = json.indexOf(pat);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pat.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = q1 + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return null;
    }

    private static String findFirstJarAssetUrl(String json) {
        int from = 0;
        while (true) {
            int idx = json.indexOf("\"browser_download_url\"", from);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return null;
            int q1 = json.indexOf('"', colon + 1);
            int q2 = q1 < 0 ? -1 : json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return null;
            String u = json.substring(q1 + 1, q2);
            if (u.toLowerCase().endsWith(".jar")) return u;
            from = q2 + 1;
        }
    }

    private static boolean isNewerVersion(String a, String b) {
        String[] sa = a.split("\\.");
        String[] sb = b.split("\\.");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            String pa = i < sa.length ? sa[i] : "0";
            String pb = i < sb.length ? sb[i] : "0";
            try {
                int ia = Integer.parseInt(pa.replaceAll("[^0-9].*", ""));
                int ib = Integer.parseInt(pb.replaceAll("[^0-9].*", ""));
                if (ia != ib) return ia > ib;
            } catch (NumberFormatException ex) {
                int cmp = pa.compareTo(pb);
                if (cmp != 0) return cmp > 0;
            }
        }
        return false;
    }

    private void sendWebsiteLink(CommandSender sender) {
        if (sender instanceof Player) {
            TextComponent link = new TextComponent("  ▶ Open " + WEBSITE_URL);
            link.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, WEBSITE_URL));
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{ new TextComponent(WEBSITE_URL) }));
            ((Player) sender).spigot().sendMessage(link);
        } else {
            sender.sendMessage(ChatColor.AQUA + WEBSITE_URL);
        }
    }
}
