package net.jplayz;

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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
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
import java.io.FileOutputStream;
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

    private static final String DEFAULT_API_URL = "https://us-central1-mcads-493820.cloudfunctions.net/edge-node";
    private static final String GITHUB_API = "https://api.github.com/repos/Jplayz2468/amethystads/releases/latest";
    private static final long UPDATE_CHECK_INTERVAL_TICKS = 6L * 60 * 60 * 20; // every 6 hours
    private static final long UPDATE_ALERT_INTERVAL_TICKS = 30L * 60 * 20;      // every 30 minutes
    private static final String AD_ITEM_NAME = "amethystads ad tool";
    private static final Material AD_ITEM_MATERIAL = Material.BLAZE_ROD;
    private static final String DASHBOARD_TITLE = ChatColor.DARK_AQUA + "amethystADS";
    private static final double CREDITS_PER_DOLLAR = 100.0;
    private static final long ROTATION_SLOT_MS = 20_000L;
    private static final long CACHE_EXPIRY_MS  = 10 * 60 * 1000L;
    private static final double ATTENTION_DOT = 0.93;
    private static final double ATTENTION_MAX_DIST = 32.0;
    private static final String ACCESS_DISCORD = "https://discord.gg/KkNfCQjczs";
    private static final int AD_SIZE = 256;
    private static final int QUADRANT_SIZE = 128;

    private String serverId;
    private String serverSecret;
    private String apiUrl;

    private volatile List<String> currentAdIds = Collections.emptyList();
    private volatile String currentImageBase = "";
    private volatile long renderedRotationSlot = Long.MIN_VALUE;
    private volatile String lastPollFailure = "";
    private volatile long lastSuccessfulPollMs = 0L;
    private volatile boolean accessBlocked = false;
    private volatile String accessBlockReason = "";
    private final Map<String, MapView> mapCache = new ConcurrentHashMap<String, MapView>();
    private final Map<String, Long> mapCacheTimestamps = new ConcurrentHashMap<String, Long>();
    private final Map<UUID, String> frameAdAssignments = new ConcurrentHashMap<UUID, String>();
    private final Set<UUID> trackedFrames = Collections.synchronizedSet(new HashSet<UUID>());
    private final Map<UUID, Integer> frameQuadrant = new ConcurrentHashMap<UUID, Integer>();
    private final Map<UUID, UUID> frameAnchor = new ConcurrentHashMap<UUID, UUID>();
    private final Map<UUID, UUID[]> groupFrames = new ConcurrentHashMap<UUID, UUID[]>();
    private final Map<String, Long> slotByPlayerAd = new ConcurrentHashMap<String, Long>();
    private final Map<String, Integer> pendingImpressionCounts = new ConcurrentHashMap<String, Integer>();
    private volatile double currentCredits = 0.0;
    private volatile boolean serverVerified = false;
    private final Map<UUID, Long> pendingRegisterReset = new ConcurrentHashMap<UUID, Long>();
    private boolean needsOnboarding = false;
    private volatile boolean adsEnabled = true;

    // Auto-update
    private volatile boolean updateAvailable = false;
    private volatile boolean updateDownloaded = false;
    private volatile String availableVersion = "";
    private volatile File pendingUpdateFile = null;

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
        saveConfig();

        if (!cfg.getBoolean("onboarded", false)) needsOnboarding = true;
        adsEnabled = cfg.getBoolean("ads-enabled", true);

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

        // Check for updates once at startup, then every 6 hours
        new BukkitRunnable() {
            @Override public void run() { checkForUpdate(); }
        }.runTaskLaterAsynchronously(this, 100L);
        new BukkitRunnable() {
            @Override public void run() { if (!updateDownloaded) checkForUpdate(); }
        }.runTaskTimerAsynchronously(this, UPDATE_CHECK_INTERVAL_TICKS, UPDATE_CHECK_INTERVAL_TICKS);

        // Periodically remind admins if an update is waiting
        new BukkitRunnable() {
            @Override public void run() {
                if (updateDownloaded) {
                    broadcastUpdateAlert(ChatColor.GREEN + "[amethystADS] v" + availableVersion
                            + " is downloaded — please restart the server to apply the update.");
                } else if (updateAvailable) {
                    broadcastUpdateAlert(ChatColor.YELLOW + "[amethystADS] v" + availableVersion
                            + " is available (current: v" + getDescription().getVersion() + ").");
                }
            }
        }.runTaskTimerAsynchronously(this, UPDATE_ALERT_INTERVAL_TICKS, UPDATE_ALERT_INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        savePersistentFrames();
        flushImpressions();
        applyPendingUpdate();
    }

    private static final String[][] SUBCOMMANDS = {
        {"register",  "get a registration token for the admin panel"},
        {"give",      "receive the ad placement tool"},
        {"status",    "show connection status and stats"},
        {"reload",    "clear image cache and reload ads"},
        {"dashboard", "open the GUI dashboard"},
        {"toggle",    "toggle all banner ads on or off"},
        {"update",    "check for plugin updates"},
        {"help",      "list all commands"},
    };

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("amethystads")) return false;
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "register":  return handleRegister(sender, args);
            case "give":      return handleGive(sender);
            case "status":    return handleStatus(sender);
            case "toggle":    return handleToggle(sender);
            case "reload":    return handleReload(sender);
            case "dashboard": return handleDashboard(sender);
            case "update":    return handleUpdate(sender);
            case "help":      return handleHelp(sender);
            default:          return handleDashboard(sender);
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
        if (args.length == 2 && args[0].equalsIgnoreCase("register")) {
            return "reset".startsWith(args[1].toLowerCase())
                    ? Collections.singletonList("reset")
                    : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private boolean handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.STRIKETHROUGH + "                                        ");
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + " amethystADS commands");
        sender.sendMessage("");
        for (String[] sc : SUBCOMMANDS) {
            sender.sendMessage(ChatColor.YELLOW + "  /aa " + sc[0] + ChatColor.GRAY + " — " + sc[1]);
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "  /aa register reset " + ChatColor.GRAY + "— generate a new server-id (destructive, requires confirmation)");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.STRIKETHROUGH + "                                        ");
        return true;
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        boolean isReset = args.length > 1 && args[1].equalsIgnoreCase("reset");
        if (isReset) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Run from in-game to confirm reset.");
                return true;
            }
            UUID pid = ((Player) sender).getUniqueId();
            Long pending = pendingRegisterReset.get(pid);
            if (pending == null || System.currentTimeMillis() - pending > 30_000L) {
                pendingRegisterReset.put(pid, System.currentTimeMillis());
                sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "WARNING: " + ChatColor.RED +
                        "This generates a brand-new server-id and secret, breaking your existing registration.");
                sender.sendMessage(ChatColor.YELLOW + "Run " + ChatColor.WHITE + "/aa register reset" +
                        ChatColor.YELLOW + " again within 30 seconds to confirm.");
                return true;
            }
            pendingRegisterReset.remove(pid);
            serverId = UUID.randomUUID().toString();
            serverSecret = randomHex(32);
            getConfig().set("server-id", serverId);
            getConfig().set("server-secret", serverSecret);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "New server-id generated. Use the token below to re-register in the admin panel.");
        }
        String token = buildRegistrationToken();
        getLogger().info("amethystADS registration token generated for server-id " + serverId);
        sender.sendMessage(ChatColor.YELLOW + "amethystADS registration token:");
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
        return true;
    }

    private String buildRegistrationToken() {
        String payload = serverId + ":" + serverSecret + ":" + randomHex(8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private boolean handleGive(CommandSender sender) {
        if (isAccessBlocked()) {
            sendAccessBlockedMessage(sender);
            return true;
        }
        if (!(sender instanceof Player)) { sender.sendMessage("Must be a player."); return true; }
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
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

    private boolean handleStatus(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "amethystADS status");
        sender.sendMessage(ChatColor.GRAY + "server-id: " + ChatColor.WHITE + serverId);
        sender.sendMessage(ChatColor.GRAY + "api-url: " + ChatColor.WHITE + apiUrl);
        sender.sendMessage(ChatColor.GRAY + "loaded ads: " + ChatColor.WHITE + currentAdIds.size());
        sender.sendMessage(ChatColor.GRAY + "tracked ads: " + ChatColor.WHITE + groupFrames.size());
        sender.sendMessage(ChatColor.GRAY + "cached maps: " + ChatColor.WHITE + mapCache.size());
        if (lastSuccessfulPollMs > 0L) {
            long ageS = Math.max(0L, (System.currentTimeMillis() - lastSuccessfulPollMs) / 1000L);
            sender.sendMessage(ChatColor.GRAY + "last successful poll: " + ChatColor.WHITE + ageS + "s ago");
        } else {
            sender.sendMessage(ChatColor.GRAY + "last successful poll: " + ChatColor.RED + "never");
        }
        if (lastPollFailure != null && !lastPollFailure.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "last poll failure: " + ChatColor.RED + lastPollFailure);
        } else {
            sender.sendMessage(ChatColor.GRAY + "last poll failure: " + ChatColor.GREEN + "none");
        }
        if (isAccessBlocked()) {
            sender.sendMessage(ChatColor.GRAY + "access: " + ChatColor.RED + "blocked");
            sender.sendMessage(ChatColor.GRAY + "contact: " + ChatColor.WHITE + ACCESS_DISCORD);
            if (accessBlockReason != null && !accessBlockReason.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "access reason: " + ChatColor.RED + accessBlockReason);
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + "access: " + ChatColor.GREEN + "ok");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        mapCache.clear();
        mapCacheTimestamps.clear();
        currentAdIds = Collections.emptyList();
        currentImageBase = "";
        renderedRotationSlot = Long.MIN_VALUE;
        sender.sendMessage(ChatColor.YELLOW + "amethystADS cache cleared, reloading...");
        new BukkitRunnable() {
            @Override public void run() { pollOnce(); }
        }.runTaskAsynchronously(this);
        return true;
    }

    private boolean handleToggle(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        adsEnabled = !adsEnabled;
        getConfig().set("ads-enabled", adsEnabled);
        saveConfig();
        if (adsEnabled) {
            long slot = System.currentTimeMillis() / ROTATION_SLOT_MS;
            updateAllFrames(currentAdIds, slot);
            sender.sendMessage(ChatColor.GREEN + "Banner ads enabled.");
        } else {
            clearAllFrameItems();
            sender.sendMessage(ChatColor.YELLOW + "Banner ads disabled.");
        }
        return true;
    }

    private void clearAllFrameItems() {
        for (UUID anchor : new ArrayList<UUID>(groupFrames.keySet())) {
            UUID[] group = groupFrames.get(anchor);
            if (group == null) continue;
            for (UUID uid : group) {
                if (uid == null) continue;
                Entity ent = Bukkit.getEntity(uid);
                if (ent instanceof ItemFrame) ((ItemFrame) ent).setItem(null);
            }
        }
    }

    private boolean handleDashboard(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        if (!(sender instanceof Player)) { sender.sendMessage("Must be a player."); return true; }
        openDashboard((Player) sender);
        return true;
    }

    private void openDashboard(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, DASHBOARD_TITLE);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) { fm.setDisplayName(" "); filler.setItemMeta(fm); }
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        boolean ok = !isAccessBlocked() && lastSuccessfulPollMs > 0 && lastPollFailure.isEmpty();
        List<String> statusLore = new ArrayList<String>();
        if (isAccessBlocked()) statusLore.add(ChatColor.RED + "access blocked — " + ACCESS_DISCORD);
        else if (!lastPollFailure.isEmpty()) statusLore.add(ChatColor.RED + lastPollFailure);
        if (lastSuccessfulPollMs > 0) {
            long ageS = (System.currentTimeMillis() - lastSuccessfulPollMs) / 1000L;
            statusLore.add(ChatColor.GRAY + "last poll: " + ageS + "s ago");
        }
        inv.setItem(4, dashItem(ok ? Material.EMERALD : Material.REDSTONE,
                ok ? ChatColor.GREEN + "connected" : ChatColor.RED + "disconnected", statusLore));

        inv.setItem(10, dashItem(Material.PAPER, ChatColor.WHITE + "server-id",
                Collections.singletonList(ChatColor.GRAY + serverId)));

        inv.setItem(12, dashItem(Material.FILLED_MAP, ChatColor.WHITE + "loaded ads",
                Collections.singletonList(ChatColor.AQUA + String.valueOf(currentAdIds.size()))));

        inv.setItem(14, dashItem(Material.ITEM_FRAME, ChatColor.WHITE + "tracked ads",
                Collections.singletonList(ChatColor.AQUA + String.valueOf(groupFrames.size()))));

        List<String> earnLore = new ArrayList<String>();
        earnLore.add(ChatColor.GRAY + String.format("%.3f credits", currentCredits));
        earnLore.add(ChatColor.GRAY + String.format("≈ $%.2f estimated", currentCredits / CREDITS_PER_DOLLAR));
        inv.setItem(16, dashItem(Material.GOLD_INGOT, ChatColor.GOLD + "earnings", earnLore));

        inv.setItem(20, dashItem(Material.REPEATER, ChatColor.YELLOW + "reload",
                Collections.singletonList(ChatColor.GRAY + "click to clear cache and reload")));

        inv.setItem(22, dashItem(
                adsEnabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                adsEnabled ? ChatColor.GREEN + "Ads: ON" : ChatColor.RED + "Ads: OFF",
                Collections.singletonList(ChatColor.GRAY + "click to toggle")));

        inv.setItem(24, dashItem(Material.BLAZE_ROD, ChatColor.AQUA + "get ad tool",
                Collections.singletonList(ChatColor.GRAY + "click to receive the ad tool")));

        p.openInventory(inv);
    }

    private static ItemStack dashItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        if (DASHBOARD_TITLE.equals(title)) {
            e.setCancelled(true);
            switch (e.getRawSlot()) {
                case 10:
                    p.closeInventory();
                    TextComponent btn = new TextComponent("[click to copy]");
                    btn.setColor(net.md_5.bungee.api.ChatColor.AQUA);
                    btn.setUnderlined(true);
                    btn.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, serverId));
                    TextComponent id = new TextComponent("  " + serverId);
                    id.setColor(net.md_5.bungee.api.ChatColor.WHITE);
                    p.spigot().sendMessage(btn, id);
                    break;
                case 20: p.closeInventory(); handleReload(p);  break;
                case 22: p.closeInventory(); handleToggle(p); break;
                case 24: p.closeInventory(); handleGive(p);   break;
            }
        }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToolInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isAdItem(item)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);

        if (isAccessBlocked()) { sendAccessBlockedMessage(e.getPlayer()); return; }
        Player player = e.getPlayer();
        if (!player.isOp() && !player.hasPermission("amethystads.admin")) {
            player.sendMessage(ChatColor.RED + "Permission denied");
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
        if (isAccessBlocked()) { sendAccessBlockedMessage(player); return; }

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
        if (!adsEnabled) return;
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
        Player p = e.getPlayer();
        if (!p.isOp() && !p.hasPermission("amethystads.admin")) return;
        if (needsOnboarding) {
            new BukkitRunnable() {
                @Override public void run() {
                    sendOnboardingMessage(p);
                    needsOnboarding = false;
                    getConfig().set("onboarded", true);
                    saveConfig();
                }
            }.runTaskLater(this, 20L);
        }
        if (updateDownloaded || updateAvailable) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (updateDownloaded) {
                        p.sendMessage(ChatColor.GREEN + "[amethystADS] v" + availableVersion
                                + " is downloaded — restart the server to apply the update.");
                    } else if (updateAvailable) {
                        p.sendMessage(ChatColor.YELLOW + "[amethystADS] v" + availableVersion
                                + " update available (current: v" + getDescription().getVersion() + ").");
                    }
                }
            }.runTaskLater(this, 60L);
        }
    }

    private void sendOnboardingMessage(Player p) {
        p.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.STRIKETHROUGH + "                                        ");
        p.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "  amethystADS installed!");
        p.sendMessage("");
        p.sendMessage(ChatColor.GRAY + "  To start earning from ads on your server:");
        p.sendMessage(ChatColor.AQUA + "  1. " + ChatColor.WHITE + "Run " + ChatColor.YELLOW + "/aa register"
                + ChatColor.WHITE + " and paste the token in the admin panel");
        p.sendMessage(ChatColor.AQUA + "  2. " + ChatColor.WHITE + "Join our Discord and contact "
                + ChatColor.LIGHT_PURPLE + "@jplayz2468" + ChatColor.WHITE + " to get verified");
        p.sendMessage(ChatColor.AQUA + "  3. " + ChatColor.WHITE + "Once verified, open "
                + ChatColor.YELLOW + "/aa" + ChatColor.WHITE + " → Earnings to track your balance");
        p.sendMessage(ChatColor.GRAY + "  Earn credits per ad click — 100 credits = $1.00 (manual payouts)");
        p.sendMessage("");

        TextComponent discord = new TextComponent("  ▶ Join the amethystADS Discord");
        discord.setColor(net.md_5.bungee.api.ChatColor.LIGHT_PURPLE);
        discord.setUnderlined(true);
        discord.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ACCESS_DISCORD));
        discord.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[]{ new TextComponent(ACCESS_DISCORD) }));
        p.spigot().sendMessage(discord);

        p.sendMessage("");
        p.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.STRIKETHROUGH + "                                        ");
    }

    private boolean handleUpdate(CommandSender sender) {
        if (!sender.isOp() && !sender.hasPermission("amethystads.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied");
            return true;
        }
        String current = getDescription().getVersion();
        if (updateDownloaded) {
            sender.sendMessage(ChatColor.GREEN + "[amethystADS] v" + availableVersion
                    + " is downloaded. Restart the server to apply it.");
        } else if (updateAvailable) {
            sender.sendMessage(ChatColor.YELLOW + "[amethystADS] v" + availableVersion
                    + " is available (current: v" + current + "). Downloading…");
        } else {
            sender.sendMessage(ChatColor.AQUA + "[amethystADS] Running v" + current + ". Checking for updates…");
            new BukkitRunnable() {
                @Override public void run() { checkForUpdate(); }
            }.runTaskAsynchronously(this);
        }
        return true;
    }

    private void checkForUpdate() {
        try {
            String current = getDescription().getVersion();
            URL url = new URL(GITHUB_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "AmethystAds-Updater/" + current);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return;
            String json = readAllText(conn.getInputStream());

            String tagName = extractJsonField(json, "tag_name");
            String downloadUrl = extractJsonField(json, "browser_download_url");
            if (tagName.isEmpty() || downloadUrl.isEmpty()) return;

            String latest = tagName.replaceFirst("^v", "");
            if (!isNewerVersion(latest, current)) {
                getLogger().info("amethystADS is up to date (v" + current + ").");
                return;
            }

            availableVersion = latest;
            updateAvailable = true;
            getLogger().info("amethystADS update available: v" + latest + " (current: v" + current + "). Downloading…");
            broadcastUpdateAlert(ChatColor.YELLOW + "[amethystADS] Update v" + latest
                    + " available (current: v" + current + "). Downloading automatically…");

            downloadUpdate(downloadUrl);

            updateDownloaded = true;
            getLogger().info("amethystADS v" + latest + " downloaded. Restart the server to apply.");
            broadcastUpdateAlert(ChatColor.GREEN + "[amethystADS] v" + latest
                    + " downloaded. Please restart the server to apply the update.");

        } catch (Exception e) {
            getLogger().warning("amethystADS update check failed: " + e.getMessage());
        }
    }

    private void downloadUpdate(String downloadUrl) throws Exception {
        File pluginDir = getFile().getParentFile();
        File updateFile = new File(pluginDir, getFile().getName() + ".update");
        // Follow redirects manually — GitHub release assets redirect to S3
        String urlStr = downloadUrl;
        for (int i = 0; i < 5; i++) {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "AmethystAds-Updater");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                urlStr = conn.getHeaderField("Location");
                conn.disconnect();
                continue;
            }
            if (code != 200) throw new RuntimeException("HTTP " + code);
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(updateFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            pendingUpdateFile = updateFile;
            return;
        }
        throw new RuntimeException("Too many redirects");
    }

    private void applyPendingUpdate() {
        if (pendingUpdateFile == null || !pendingUpdateFile.exists()) return;
        File target = getFile();
        boolean deleted = target.delete();
        if (deleted) {
            if (pendingUpdateFile.renameTo(target)) {
                getLogger().info("amethystADS update applied. Ready on next start.");
            } else {
                getLogger().warning("amethystADS update: could not rename update file to " + target.getName()
                        + ". Manually replace the JAR with " + pendingUpdateFile.getName());
            }
        } else {
            getLogger().warning("amethystADS update: could not replace JAR (file locked?). "
                    + "Manually replace " + target.getName() + " with " + pendingUpdateFile.getName());
        }
    }

    private void broadcastUpdateAlert(final String msg) {
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp() || p.hasPermission("amethystads.admin")) p.sendMessage(msg);
                }
            }
        });
        getLogger().info(ChatColor.stripColor(msg));
    }

    private static boolean isNewerVersion(String latest, String current) {
        try {
            String[] l = latest.replaceFirst("^v", "").split("\\.");
            String[] c = current.replaceFirst("^v", "").split("\\.");
            int len = Math.max(l.length, c.length);
            for (int i = 0; i < len; i++) {
                int lv = i < l.length ? Integer.parseInt(l[i].replaceAll("[^0-9].*", "")) : 0;
                int cv = i < c.length ? Integer.parseInt(c[i].replaceAll("[^0-9].*", "")) : 0;
                if (lv > cv) return true;
                if (lv < cv) return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractJsonField(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private boolean isAdItem(ItemStack item) {
        if (item == null || item.getType() != AD_ITEM_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        return ChatColor.stripColor(meta.getDisplayName()).equals(AD_ITEM_NAME);
    }

    private void sendAdMessage(Player p, UUID frameId) {
        if (isAccessBlocked()) {
            sendAccessBlockedMessage(p);
            return;
        }
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
            final double credits = parseDouble(nz(map.get("credits")));
            final boolean verified = "1".equals(map.get("verified"));
            final long slot = System.currentTimeMillis() / ROTATION_SLOT_MS;
            clearAccessBlocked();
            currentCredits = credits;
            serverVerified = verified;

            if (adIds.isEmpty() || imageBase.isEmpty()) return;

            List<String> needed = computeNeededAdIds(adIds, slot);
            long now = System.currentTimeMillis();
            boolean anyStale = false;
            for (String adId : needed) {
                Long ts = mapCacheTimestamps.get(adId);
                if (!mapCache.containsKey(adId + "_0") || ts == null || now - ts > CACHE_EXPIRY_MS) {
                    anyStale = true;
                    break;
                }
            }
            if (adIds.equals(currentAdIds) && imageBase.equals(currentImageBase) && renderedRotationSlot == slot && !anyStale) {
                return;
            }

            final Map<String, BufferedImage> downloaded = new HashMap<String, BufferedImage>();
            for (String adId : needed) {
                Long ts = mapCacheTimestamps.get(adId);
                if (mapCache.containsKey(adId + "_0") && ts != null && now - ts <= CACHE_EXPIRY_MS) continue;
                downloaded.put(adId, downloadImage(imageBase + adId));
            }

            Bukkit.getScheduler().runTask(this, new Runnable() {
                @Override public void run() {
                    long updateTime = System.currentTimeMillis();
                    for (Map.Entry<String, BufferedImage> entry : downloaded.entrySet()) {
                        String adId = entry.getKey();
                        BufferedImage full = entry.getValue();
                        for (int q = 0; q < 4; q++) {
                            mapCache.put(adId + "_" + q, createMapView(full, q));
                        }
                        mapCacheTimestamps.put(adId, updateTime);
                    }
                    currentAdIds = adIds;
                    currentImageBase = imageBase;
                    renderedRotationSlot = slot;
                    lastSuccessfulPollMs = System.currentTimeMillis();
                    lastPollFailure = "";
                    if (!downloaded.isEmpty())
                        getLogger().info("amethystADS poll ok: refreshed " + downloaded.size() + " image(s), " + adIds.size() + " ads total");
                    updateAllFrames(adIds, slot);
                }
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (isUnauthorizedFailure(msg)) {
                blockAccess(msg);
            }
            if (!msg.equals(lastPollFailure)) {
                lastPollFailure = msg;
                getLogger().warning("amethystADS poll failed for server-id " + serverId + ": " + msg);
            }
        }
    }

    private void updateAllFrames(List<String> adIds, long slot) {
        if (!adsEnabled) return;
        if (adIds.isEmpty()) return;
        for (UUID anchor : new ArrayList<UUID>(groupFrames.keySet())) {
            updateGroup(anchor, adIds, slot);
        }
    }

    private void updateGroup(UUID anchor, List<String> adIds, long slot) {
        UUID[] group = groupFrames.get(anchor);
        if (group == null) return;
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

    private MapView createMapView(final BufferedImage fullImage, final int quadrant) {
        final int srcX = (quadrant == 1 || quadrant == 3) ? QUADRANT_SIZE : 0;
        final int srcY = (quadrant == 2 || quadrant == 3) ? QUADRANT_SIZE : 0;
        World world = Bukkit.getWorlds().get(0);
        MapView view = Bukkit.createMap(world);
        for (MapRenderer r : new ArrayList<MapRenderer>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new MapRenderer() {
            boolean drawn = false;
            @Override public void render(MapView v, MapCanvas canvas, Player player) {
                if (!drawn) {
                    canvas.drawImage(0, 0, fullImage.getSubimage(srcX, srcY, QUADRANT_SIZE, QUADRANT_SIZE));
                    drawn = true;
                }
            }
        });
        return view;
    }

    private void scanAttention() {
        if (!adsEnabled) return;
        if (isAccessBlocked()) return;
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
        if (isAccessBlocked()) return;
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
                if (status == 401) blockAccess("HTTP 401: " + msg);
                getLogger().warning("impression flush HTTP " + status + ": " + msg);
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

    private boolean isAccessBlocked() {
        return accessBlocked;
    }

    private void blockAccess(String reason) {
        accessBlocked = true;
        accessBlockReason = nz(reason);
        currentAdIds = Collections.emptyList();
        currentImageBase = "";
        synchronized (pendingImpressionCounts) {
            pendingImpressionCounts.clear();
        }
    }

    private void clearAccessBlocked() {
        accessBlocked = false;
        accessBlockReason = "";
    }

    private void sendAccessBlockedMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "amethystADS access is blocked.");
        if (sender instanceof Player) {
            TextComponent link = new TextComponent("Join our Discord to request access.");
            link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ACCESS_DISCORD));
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{ new TextComponent(ACCESS_DISCORD) }));
            ((Player) sender).spigot().sendMessage(link);
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Join our Discord to request access: " + ACCESS_DISCORD);
        }
    }

    private static boolean isUnauthorizedFailure(String msg) {
        String lowered = nz(msg).toLowerCase();
        return lowered.contains("http 401") || lowered.contains("unauthorized");
    }
}
