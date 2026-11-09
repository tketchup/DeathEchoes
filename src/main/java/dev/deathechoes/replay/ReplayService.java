package dev.deathechoes.replay;

import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.Echo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Entscheidet, welche Echoes gerade laufen (Zuschauer im Radius, Limits), und tickt die laufenden.
 *
 * <p>Pausierte Echoes haben keine Entity und kosten nichts außer einem Abstandsvergleich alle
 * {@value #SCAN_INTERVAL} Ticks.</p>
 */
public final class ReplayService implements Listener {

    public static final String ENTITY_TAG = "deathechoes";
    private static final int SCAN_INTERVAL = 10;

    private final DeathEchoesPlugin plugin;
    private final List<EchoPlayback> active = new ArrayList<>();
    private final GhostTeam ghostTeam;
    private BukkitTask tickTask;
    private BukkitTask scanTask;

    public ReplayService(DeathEchoesPlugin plugin) {
        this.plugin = plugin;
        this.ghostTeam = new GhostTeam(plugin.getLogger());
    }

    public GhostTeam ghostTeam() {
        return ghostTeam;
    }

    public void start() {
        stop();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1, 1);
        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scan, 5, SCAN_INTERVAL);
    }

    /** Tasks stoppen und alle Entities entfernen. */
    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (scanTask != null) scanTask.cancel();
        tickTask = null;
        scanTask = null;
        for (EchoPlayback pb : active) {
            pb.stop();
            pb.echo().playback(null);
        }
        active.clear();
        ghostTeam.sync(java.util.Set.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        ghostTeam.release(event.getPlayer());
    }

    public int activeCount() {
        return active.size();
    }

    private void tickAll() {
        for (int k = 0; k < active.size(); k++) {
            EchoPlayback pb = active.get(k);
            try {
                pb.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Fehler bei der Wiedergabe von Echo " + pb.echo().id() + ", wird pausiert", e);
                pb.stop();
                pb.echo().playback(null);
                active.remove(k--);
            }
        }
    }

    /** Zuschauer bestimmen, Echoes aktivieren/pausieren, Limits anwenden. */
    private void scan() {
        PluginConfig cfg = plugin.settings();
        double r2 = cfg.viewRadius() * cfg.viewRadius();
        int total = 0;
        Map<Long, Integer> perChunk = new HashMap<>();
        Map<Echo, List<Player>> wanted = new java.util.LinkedHashMap<>();

        // Bereits laufende zuerst, damit sie bei vollen Limits nicht von neuen verdrängt werden
        List<Echo> order = new ArrayList<>();
        for (EchoPlayback pb : active) order.add(pb.echo());
        for (Echo e : plugin.echoes().all()) if (e.playback() == null) order.add(e);

        for (Echo e : order) {
            if (e.removed()) continue;
            World w = plugin.getServer().getWorld(e.worldId());
            if (w == null || !w.isChunkLoaded(e.chunkX(), e.chunkZ()) || !cfg.isWorldEnabled(w)) continue;
            List<Player> viewers = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                if (!p.hasPermission("deathechoes.see")) continue;
                Location l = p.getLocation();
                double dx = l.getX() - e.x(), dy = l.getY() - e.y(), dz = l.getZ() - e.z();
                if (dx * dx + dy * dy + dz * dz <= r2) viewers.add(p);
            }
            if (viewers.isEmpty() && !e.forcePlay()) continue;
            long chunkKey = (((long) e.chunkX()) << 32) ^ (e.chunkZ() & 0xffffffffL) ^ e.worldId().getLeastSignificantBits();
            int inChunk = perChunk.getOrDefault(chunkKey, 0);
            if (total >= cfg.maxActiveTotal() || inChunk >= cfg.maxActivePerChunk()) continue;
            total++;
            perChunk.put(chunkKey, inChunk + 1);
            wanted.put(e, viewers);
        }

        // Nicht mehr gewünschte pausieren
        for (Iterator<EchoPlayback> it = active.iterator(); it.hasNext(); ) {
            EchoPlayback pb = it.next();
            if (!wanted.containsKey(pb.echo())) {
                pb.stop();
                pb.echo().playback(null);
                it.remove();
                if (cfg.debug()) plugin.getLogger().info("Echo " + pb.echo().id() + " pausiert");
            }
        }
        // Neue starten, Zuschauer aktualisieren
        for (Map.Entry<Echo, List<Player>> en : wanted.entrySet()) {
            Echo e = en.getKey();
            EchoPlayback pb = e.playback();
            if (pb == null) {
                World w = plugin.getServer().getWorld(e.worldId());
                pb = new EchoPlayback(plugin, e, w);
                e.playback(pb);
                active.add(pb);
                if (cfg.debug()) plugin.getLogger().info("Echo " + e.id() + " gestartet (" + en.getValue().size() + " Zuschauer)");
            }
            pb.updateViewers(en.getValue());
        }
        // Geister-Look: nur Zuschauer laufender Echoes gehören ins Team
        java.util.Set<Player> ghostViewers = new java.util.HashSet<>();
        if (cfg.appearance() == PluginConfig.Appearance.GHOST) {
            for (List<Player> v : wanted.values()) ghostViewers.addAll(v);
        }
        ghostTeam.sync(ghostViewers);
    }

    /** Wiedergabe eines Echoes beenden (mit oder ohne Abschiedseffekt). */
    public void detach(Echo e, boolean farewell) {
        EchoPlayback pb = e.playback();
        World w = plugin.getServer().getWorld(e.worldId());
        if (farewell && w != null) {
            Location at = pb != null ? pb.currentLocation() : e.location(w);
            List<Player> viewers = pb != null && pb.viewerCount() > 0 ? pb.viewers() : nearbyViewers(w, e);
            Effects.farewell(plugin.settings(), viewers, at);
        }
        if (pb != null) {
            pb.stop();
            active.remove(pb);
            e.playback(null);
        }
    }

    private List<Player> nearbyViewers(World w, Echo e) {
        double r = plugin.settings().viewRadius();
        List<Player> out = new ArrayList<>();
        Location at = e.location(w);
        for (Player p : w.getPlayers()) {
            if (p.hasPermission("deathechoes.see") && p.getLocation().distanceSquared(at) <= r * r) out.add(p);
        }
        return out;
    }

    /** Entfernt verwaiste Echo-Entities (z. B. nach einem Absturz), die mit einem Chunk geladen werden. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity en : event.getEntities()) {
            if (isOrphan(en)) {
                en.remove();
                plugin.getLogger().info("Verwaiste Echo-Entity entfernt bei " + en.getLocation().toVector());
            }
        }
    }

    /** Beim Start alle bereits geladenen Welten nach verwaisten Entities durchsuchen. */
    public int purgeOrphans() {
        int n = 0;
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity en : w.getEntities()) {
                if (isOrphan(en)) {
                    en.remove();
                    n++;
                }
            }
        }
        return n;
    }

    private boolean isOrphan(Entity en) {
        if (!en.getScoreboardTags().contains(ENTITY_TAG)
                && !en.getPersistentDataContainer().has(plugin.entityKey(), PersistentDataType.STRING)) {
            return false;
        }
        for (EchoPlayback pb : active) {
            if (pb.entity() == en) return false;
        }
        return true;
    }
}
