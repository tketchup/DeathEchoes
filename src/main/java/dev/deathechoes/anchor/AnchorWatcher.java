package dev.deathechoes.anchor;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.BlockPos;
import dev.deathechoes.echo.DeathCause;
import dev.deathechoes.echo.Echo;
import dev.deathechoes.echo.EchoManager;
import dev.deathechoes.record.Recording;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bindet Echoes an das Grab (GraveBox o. Ä.), ohne dessen Klassen zu kennen: es wird nur der
 * Weltzustand gelesen.
 *
 * <ul>
 *   <li>Tod: Container im Umkreis werden vorher (LOWEST, nur lesend) und {@code search-delay-ticks}
 *       danach verglichen. Ein neu entstandener Container ist der Anker.</li>
 *   <li>Verschwinden: Block-Abbau, Explosionen und Inventar-Schließen lösen eine Prüfung 1-2 Ticks
 *       später aus. Zusätzlich prüft ein periodischer Task alle Anker in geladenen Chunks; das fängt
 *       z. B. GraveBox ab, das die Kiste per setType(AIR) ohne Event entfernt.</li>
 *   <li>Chunks: beim Laden wird der Anker geprüft (nach Neustart), beim Entladen die Wiedergabe beendet.</li>
 * </ul>
 *
 * <p>Kein Listener verändert Events, Drops, Inventare oder Todesnachrichten.</p>
 */
public final class AnchorWatcher implements Listener {

    /** Inventar-Schließen prüft Anker in diesem Abstand zum Spieler. */
    private static final double CLOSE_CHECK_DISTANCE_SQ = 10 * 10;

    private final DeathEchoesPlugin plugin;
    private final Map<UUID, PreDeath> preDeath = new HashMap<>();
    private final Set<String> pendingChecks = new HashSet<>();
    private BukkitTask periodic;

    /** Container-Positionen im Umkreis vor dem Tod. */
    private record PreDeath(UUID world, int x, int y, int z, Set<Long> containers) { }

    /** Ergebnis einer Anker-Prüfung. */
    public enum State { PRESENT, GONE, EMPTY, UNKNOWN }

    public AnchorWatcher(DeathEchoesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        int interval = plugin.settings().anchorCheckIntervalTicks();
        periodic = plugin.getServer().getScheduler().runTaskTimer(plugin, this::periodicCheck, interval, interval);
    }

    public void stop() {
        if (periodic != null) {
            periodic.cancel();
            periodic = null;
        }
        pendingChecks.clear();
    }

    // ------------------------------------------------------------------ Tod

    /** Nur lesend: merkt sich, welche Container schon vor dem Tod da waren. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeathSnapshot(PlayerDeathEvent event) {
        Player p = event.getEntity();
        PluginConfig cfg = plugin.settings();
        if (!cfg.isWorldEnabled(p.getWorld())) return;
        Block center = p.getLocation().getBlock();
        preDeath.put(p.getUniqueId(), new PreDeath(p.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                scanContainers(p.getWorld(), center.getX(), center.getY(), center.getZ(), cfg)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        PreDeath pre = preDeath.remove(p.getUniqueId());
        PluginConfig cfg = plugin.settings();
        if (!cfg.isWorldEnabled(p.getWorld())) return;

        Recording rec = plugin.recorder().freeze(p, true);
        if (rec == null) {
            if (cfg.debug()) plugin.getLogger().info("Tod von " + p.getName() + ": keine Aufnahme vorhanden, kein Echo");
            return;
        }
        DeathCause cause = DeathCause.from(event.getDamageSource());
        Entity killerEntity = event.getDamageSource().getCausingEntity();
        String killer = killerEntity != null && killerEntity != p ? killerEntity.getName() : null;
        String skinValue = null, skinSig = null;
        for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) {
                skinValue = prop.getValue();
                skinSig = prop.getSignature();
            }
        }
        Location death = p.getLocation();
        World world = death.getWorld();
        Block center = death.getBlock();
        final String fSkinValue = skinValue, fSkinSig = skinSig;
        final Set<Long> before = pre != null && pre.world().equals(world.getUID()) ? pre.containers() : Set.of();
        final UUID owner = p.getUniqueId();
        final String ownerName = p.getName();
        final double dx = death.getX(), dy = death.getY(), dz = death.getZ();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                createFromDeath(owner, ownerName, fSkinValue, fSkinSig, world, dx, dy, dz,
                        center.getX(), center.getY(), center.getZ(), before, cause, killer, rec);
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Echo für " + ownerName + " konnte nicht angelegt werden", e);
            }
        }, cfg.anchorSearchDelayTicks());
    }

    private void createFromDeath(UUID owner, String ownerName, String skinValue, String skinSig, World world,
                                 double x, double y, double z, int bx, int by, int bz, Set<Long> before,
                                 DeathCause cause, String killer, Recording rec) {
        PluginConfig cfg = plugin.settings();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return;
        Block anchor = findNewContainer(world, bx, by, bz, before, cfg);
        long now = System.currentTimeMillis();
        List<BlockPos> anchors = new ArrayList<>();
        Material material = null;
        boolean hadContents = false;
        long expires;
        if (anchor != null) {
            anchors.add(BlockPos.of(anchor));
            Block other = otherChestHalf(anchor);
            if (other != null) anchors.add(BlockPos.of(other));
            material = anchor.getType();
            hadContents = hasContents(anchor);
            expires = cfg.lifetimeMillis() > 0 ? now + cfg.lifetimeMillis() : 0;
        } else if (cfg.echoWithoutGrave()) {
            expires = now + cfg.gravelessLifetimeMillis();
        } else {
            if (cfg.debug()) plugin.getLogger().info("Tod von " + ownerName + ": kein neues Grab gefunden, kein Echo");
            return;
        }
        Echo echo = new Echo(plugin.echoes().newId(), owner, ownerName, skinValue, skinSig,
                world.getUID(), world.getName(), x, y, z, anchors, material, hadContents,
                now, expires, cause, killer, rec, true);
        plugin.echoes().add(echo);
        plugin.getLogger().info("Echo " + echo.id() + " für " + ownerName + " bei " + world.getName() + " "
                + bx + " " + by + " " + bz + (anchor != null ? " (Anker " + material + " " + anchors + ")" : " (ohne Grab)"));
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static Set<Long> scanContainers(World w, int cx, int cy, int cz, PluginConfig cfg) {
        Set<Long> out = new HashSet<>();
        int r = cfg.anchorSearchRadius();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(w.getMinHeight(), cy - r); y <= Math.min(w.getMaxHeight() - 1, cy + r); y++) {
                    if (cfg.containerMaterials().contains(w.getBlockAt(x, y, z).getType())) out.add(pack(x, y, z));
                }
            }
        }
        return out;
    }

    /** Nächster Container im Umkreis, der vor dem Tod noch nicht da war. */
    private static Block findNewContainer(World w, int cx, int cy, int cz, Set<Long> before, PluginConfig cfg) {
        int r = cfg.anchorSearchRadius();
        Block best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(w.getMinHeight(), cy - r); y <= Math.min(w.getMaxHeight() - 1, cy + r); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!cfg.containerMaterials().contains(b.getType()) || before.contains(pack(x, y, z))) continue;
                    int d = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                    if (d < bestDist) {
                        bestDist = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    /** Zweite Hälfte einer Doppelkiste oder null. */
    static Block otherChestHalf(Block b) {
        BlockData data = b.getBlockData();
        if (!(data instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) return null;
        BlockFace facing = chest.getFacing();
        BlockFace dir = chest.getType() == Chest.Type.LEFT ? clockwise(facing) : counterClockwise(facing);
        Block other = b.getRelative(dir);
        return other.getType() == b.getType() ? other : null;
    }

    private static BlockFace clockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static BlockFace counterClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    private static boolean hasContents(Block b) {
        BlockState state = b.getState(false);
        return state instanceof Container c && !c.getInventory().isEmpty();
    }

    /** Anker an einer frei gewählten Position bestimmen (für /echoes debug spawn). */
    public Block findAnyContainer(World w, int x, int y, int z) {
        return findNewContainer(w, x, y, z, Set.of(), plugin.settings());
    }

    public static Block secondHalf(Block b) {
        return otherChestHalf(b);
    }

    public static boolean containerHasContents(Block b) {
        return hasContents(b);
    }

    // ------------------------------------------------------------------ Prüfung

    /** Prüft den Anker. Liest nur, lädt nie einen Chunk. */
    public State check(Echo e) {
        if (!e.hasAnchor()) return State.PRESENT;
        World w = plugin.getServer().getWorld(e.worldId());
        if (w == null) return State.UNKNOWN;
        boolean anyPresent = false;
        Block presentBlock = null;
        for (BlockPos p : e.anchors()) {
            if (!w.isChunkLoaded(p.chunkX(), p.chunkZ())) return State.UNKNOWN;
            Block b = w.getBlockAt(p.x(), p.y(), p.z());
            if (b.getType() == e.anchorMaterial()) {
                anyPresent = true;
                presentBlock = b;
            }
        }
        if (!anyPresent) return State.GONE;
        if (plugin.settings().removeWhenEmpty() && e.anchorHadContents() && !hasContents(presentBlock)) {
            return State.EMPTY;
        }
        return State.PRESENT;
    }

    /** Prüfen und bei Bedarf entfernen. */
    public void checkAndApply(Echo e, boolean farewell) {
        if (e.removed()) return;
        if (e.isExpired(System.currentTimeMillis())) {
            plugin.echoes().remove(e, EchoManager.Reason.EXPIRED, farewell);
            return;
        }
        switch (check(e)) {
            case GONE -> plugin.echoes().remove(e, farewell ? EchoManager.Reason.ANCHOR_GONE : EchoManager.Reason.STALE, farewell);
            case EMPTY -> plugin.echoes().remove(e, EchoManager.Reason.ANCHOR_EMPTY, farewell);
            default -> { }
        }
    }

    public void scheduleCheck(Echo e, int delayTicks) {
        if (e.removed() || !pendingChecks.add(e.id())) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingChecks.remove(e.id());
            checkAndApply(e, true);
        }, delayTicks);
    }

    private void periodicCheck() {
        for (Echo e : plugin.echoes().snapshot()) {
            try {
                checkAndApply(e, true);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Anker-Prüfung für " + e.id() + " fehlgeschlagen", ex);
            }
        }
    }

    /** Nach dem Laden aus der Persistenz: Echoes in bereits geladenen Chunks sofort prüfen. */
    public void validateLoaded() {
        for (Echo e : plugin.echoes().snapshot()) {
            World w = plugin.getServer().getWorld(e.worldId());
            if (w != null && w.isChunkLoaded(e.chunkX(), e.chunkZ())) checkAndApply(e, false);
        }
    }

    // ------------------------------------------------------------------ Events (nur lesend)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        for (Echo e : plugin.echoes().byAnchor(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ())) {
            scheduleCheck(e, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        onExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        onExplosion(event.blockList());
    }

    /**
     * Ob der Anker die Explosion überlebt, entscheidet das Grab-Plugin (GraveBox: explosion-protection
     * nimmt die Kiste aus der Blockliste). Wir prüfen einfach einen Tick später, was übrig ist.
     */
    private void onExplosion(List<Block> blocks) {
        for (Block b : blocks) {
            for (Echo e : plugin.echoes().byAnchor(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ())) {
                if (plugin.settings().debug()) plugin.getLogger().info("Anker von Echo " + e.id() + " liegt in einer Explosion");
                scheduleCheck(e, 1);
            }
        }
    }

    /**
     * GraveBox öffnet ein virtuelles Inventar und entfernt die Kiste beim Schließen, wenn sie leer ist.
     * Darum werden 2 Ticks nach jedem Schließen die Anker in der Nähe des Spielers geprüft.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Location l = event.getPlayer().getLocation();
        World w = l.getWorld();
        int pcx = l.getBlockX() >> 4, pcz = l.getBlockZ() >> 4;
        for (int cx = pcx - 1; cx <= pcx + 1; cx++) {
            for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
                for (Echo e : plugin.echoes().inChunk(w.getUID(), cx, cz)) {
                    if (!e.hasAnchor()) continue;
                    BlockPos a = e.anchors().getFirst();
                    double dx = a.x() + 0.5 - l.getX(), dy = a.y() + 0.5 - l.getY(), dz = a.z() + 0.5 - l.getZ();
                    if (dx * dx + dy * dy + dz * dz <= CLOSE_CHECK_DISTANCE_SQ) scheduleCheck(e, 2);
                }
            }
        }
    }

    /** Nach einem Neustart: Anker prüfen, sobald der Chunk geladen wird. Verschwundene Gräber still verwerfen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        List<Echo> echoes = plugin.echoes().inChunk(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        if (echoes.isEmpty()) return;
        // Einen Tick warten, bis der Chunk vollständig verfügbar ist
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Echo e : echoes) checkAndApply(e, false);
        });
    }

    /** Beim Entladen die Wiedergabe beenden, damit keine Entity im entladenen Chunk hängt. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Echo e : plugin.echoes().inChunk(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ())) {
            plugin.replay().detach(e, false);
        }
    }

    public void forget(UUID player) {
        preDeath.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }
}
