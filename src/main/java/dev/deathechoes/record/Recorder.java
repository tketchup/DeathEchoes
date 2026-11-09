package dev.deathechoes.record;

import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.config.PluginConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

/**
 * Nimmt für jeden Online-Spieler die letzten Sekunden in einem {@link FrameBuffer} auf.
 *
 * <p>Ein einziger globaler Task sampelt alle {@code sample-interval-ticks} alle Spieler. Schläge und
 * Treffer werden per Event als Flag vorgemerkt und in den nächsten Frame geschrieben. Nichts davon
 * landet auf der Platte, solange niemand stirbt.</p>
 */
public final class Recorder implements Listener {

    /** Teleports weiter als das zerreißen die Aufnahme (sonst würde das Echo quer durch die Welt gleiten). */
    private static final double TELEPORT_RESET_DISTANCE_SQ = 16 * 16;
    /** Rüstung nur ungefähr jede Sekunde neu lesen, sie ändert sich selten. */
    private static final int ARMOR_REFRESH_SAMPLES = 10;

    private final DeathEchoesPlugin plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private BukkitTask task;
    private int capacity;
    private int interval;
    private long sampleCounter;

    /** Zustand pro Spieler. Wird beim Quit entfernt (kein Leak). */
    public static final class Track {
        final FrameBuffer buffer;
        final Material[] armor = new Material[4];
        UUID worldId;
        byte pendingFlags;
        long samples;

        Track(int capacity) {
            buffer = new FrameBuffer(capacity);
        }

        public FrameBuffer buffer() { return buffer; }
        public long samples() { return samples; }
        public UUID worldId() { return worldId; }
    }

    public Recorder(DeathEchoesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        PluginConfig cfg = plugin.settings();
        if (cfg.bufferCapacity() != capacity) {
            // Neue Größe: Puffer neu anlegen (die bisherigen paar Sekunden gehen verloren, das ist ok).
            tracks.clear();
        }
        capacity = cfg.bufferCapacity();
        interval = cfg.sampleIntervalTicks();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            tracks.computeIfAbsent(p.getUniqueId(), id -> new Track(capacity));
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sampleAll, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        tracks.clear();
    }

    private void sampleAll() {
        PluginConfig cfg = plugin.settings();
        sampleCounter++;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (!cfg.isWorldEnabled(p.getWorld())) continue;
            Track t = tracks.computeIfAbsent(p.getUniqueId(), id -> new Track(capacity));
            sample(p, t);
        }
    }

    private void sample(Player p, Track t) {
        Location loc = p.getLocation();
        UUID world = loc.getWorld().getUID();
        if (!world.equals(t.worldId)) {
            t.buffer.clear();
            t.worldId = world;
        }
        if (t.samples % ARMOR_REFRESH_SAMPLES == 0) {
            refreshArmor(p, t);
        }
        byte flags = t.pendingFlags;
        if (p.isSprinting()) flags |= Recording.FLAG_SPRINT;
        t.pendingFlags = 0;
        ItemStack hand = p.getInventory().getItemInMainHand();
        t.buffer.add(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                poseOf(p), flags, hand.getType().isAir() ? null : hand.getType());
        t.samples++;
    }

    private static void refreshArmor(Player p, Track t) {
        PlayerInventory inv = p.getInventory();
        t.armor[0] = typeOrNull(inv.getHelmet());
        t.armor[1] = typeOrNull(inv.getChestplate());
        t.armor[2] = typeOrNull(inv.getLeggings());
        t.armor[3] = typeOrNull(inv.getBoots());
    }

    private static Material typeOrNull(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.getType();
    }

    static byte poseOf(Player p) {
        return switch (p.getPose()) {
            case SNEAKING -> Recording.POSE_SNEAKING;
            case SWIMMING -> Recording.POSE_SWIMMING;
            case FALL_FLYING -> Recording.POSE_GLIDING;
            case SLEEPING -> Recording.POSE_SLEEPING;
            default -> Recording.POSE_STANDING;
        };
    }

    /**
     * Friert die Aufnahme ein. Vorher wird ein letzter Frame an der aktuellen Position geschrieben,
     * damit das Replay genau am Todesort endet. {@code clear=true} leert den Puffer danach (Tod).
     *
     * @return die Aufnahme oder null, wenn zu wenig Frames vorhanden sind
     */
    public Recording freeze(Player p, boolean clear) {
        Track t = tracks.get(p.getUniqueId());
        if (t == null) return null;
        if (!p.getWorld().getUID().equals(t.worldId)) {
            if (clear) t.buffer.clear();
            return null;
        }
        refreshArmor(p, t);
        sample(p, t);
        Recording rec = t.buffer.size() >= 2 ? t.buffer.freeze(interval, t.armor) : null;
        if (clear) t.buffer.clear();
        return rec;
    }

    public Track track(UUID player) {
        return tracks.get(player);
    }

    public int trackedPlayers() {
        return tracks.size();
    }

    public int capacity() {
        return capacity;
    }

    public long sampleCounter() {
        return sampleCounter;
    }

    // ------------------------------------------------------------------ Events (nur lesend)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        tracks.computeIfAbsent(e.getPlayer().getUniqueId(), id -> new Track(capacity));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        tracks.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Track t = tracks.get(e.getPlayer().getUniqueId());
        if (t != null) t.pendingFlags |= Recording.FLAG_SWING;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Track t = tracks.get(p.getUniqueId());
        if (t != null) t.pendingFlags |= Recording.FLAG_HURT;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > TELEPORT_RESET_DISTANCE_SQ) {
            Track t = tracks.get(e.getPlayer().getUniqueId());
            if (t != null) t.buffer.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Track t = tracks.get(e.getPlayer().getUniqueId());
        if (t != null) t.buffer.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Track t = tracks.get(e.getPlayer().getUniqueId());
        if (t != null) t.buffer.clear();
    }
}
