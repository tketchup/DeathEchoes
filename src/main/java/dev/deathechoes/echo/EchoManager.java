package dev.deathechoes.echo;

import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.storage.EchoStorage;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Register aller Echoes im Speicher mit Indizes nach Chunk und Anker-Block.
 * Alle Methoden laufen auf dem Main-Thread; Plattenzugriffe gibt {@link EchoStorage} an seinen Thread ab.
 */
public final class EchoManager {

    /** Warum ein Echo entfernt wird (für Log und Effekt). */
    public enum Reason { ANCHOR_GONE, ANCHOR_EMPTY, EXPIRED, LIMIT, ADMIN, CLEAR, STALE }

    public record ChunkKey(UUID world, int x, int z) { }

    public record AnchorKey(UUID world, int x, int y, int z) { }

    private static final char[] ID_CHARS = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final DeathEchoesPlugin plugin;
    private final EchoStorage storage;
    private final Map<String, Echo> byId = new LinkedHashMap<>();
    private final Map<ChunkKey, List<Echo>> byChunk = new HashMap<>();
    private final Map<AnchorKey, List<Echo>> byAnchor = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public EchoManager(DeathEchoesPlugin plugin, EchoStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public String newId() {
        while (true) {
            char[] c = new char[6];
            for (int i = 0; i < c.length; i++) c[i] = ID_CHARS[random.nextInt(ID_CHARS.length)];
            String id = new String(c);
            if (!byId.containsKey(id)) return id;
        }
    }

    /** Neues Echo registrieren, Spieler-Limit anwenden und (falls persistent) speichern. */
    public void add(Echo echo) {
        if (echo.persistent()) {
            List<Echo> own = new ArrayList<>(byOwner(echo.owner()));
            own.removeIf(e -> !e.persistent());
            own.sort(Comparator.comparingLong(Echo::createdAt));
            int max = plugin.settings().maxPerPlayer();
            while (own.size() >= max) {
                remove(own.removeFirst(), Reason.LIMIT, true);
            }
        }
        index(echo);
        if (echo.persistent()) storage.save(echo);
        resolveSkinIfMissing(echo);
        if (plugin.settings().debug()) {
            plugin.getLogger().info("Echo " + echo.id() + " von " + echo.ownerName() + " angelegt, Anker: "
                    + (echo.hasAnchor() ? echo.anchors() : "keiner"));
        }
    }

    /** Beim Laden aus der Persistenz: nur registrieren, nicht erneut speichern. */
    public void addLoaded(Echo echo) {
        if (byId.containsKey(echo.id())) return;
        index(echo);
        resolveSkinIfMissing(echo);
    }

    /**
     * skin-by-name: fehlt der Skin (Offline-Mode), ihn asynchron über den Namen holen, im Echo speichern
     * und die laufende Gestalt neu erzeugen.
     */
    private void resolveSkinIfMissing(Echo echo) {
        if (echo.skinValue() != null || !plugin.settings().skinByName()) return;
        plugin.skins().resolve(echo.ownerName()).whenComplete((skin, err) -> {
            if (err != null || skin.isEmpty() || !plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (echo.removed() || echo.skinValue() != null) return;
                echo.skin(skin.get().value(), skin.get().signature());
                if (echo.persistent()) storage.save(echo);
                if (echo.playback() != null) echo.playback().refreshAppearance();
                if (plugin.settings().debug()) plugin.getLogger().info("Skin für Echo " + echo.id() + " nachgeladen");
            });
        });
    }

    private void index(Echo echo) {
        byId.put(echo.id(), echo);
        byChunk.computeIfAbsent(new ChunkKey(echo.worldId(), echo.chunkX(), echo.chunkZ()), k -> new ArrayList<>()).add(echo);
        for (BlockPos p : echo.anchors()) {
            byAnchor.computeIfAbsent(new AnchorKey(echo.worldId(), p.x(), p.y(), p.z()), k -> new ArrayList<>()).add(echo);
        }
    }

    /**
     * Echo entfernen: Wiedergabe beenden (optional mit Abschiedseffekt), Indizes und Datei löschen,
     * Aufnahme-Daten werden mit dem Objekt freigegeben.
     */
    public void remove(Echo echo, Reason reason, boolean farewell) {
        if (echo.removed()) return;
        echo.markRemoved();
        try {
            plugin.replay().detach(echo, farewell);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Beenden der Wiedergabe von " + echo.id(), e);
        }
        byId.remove(echo.id());
        ChunkKey ck = new ChunkKey(echo.worldId(), echo.chunkX(), echo.chunkZ());
        List<Echo> list = byChunk.get(ck);
        if (list != null) {
            list.remove(echo);
            if (list.isEmpty()) byChunk.remove(ck);
        }
        for (BlockPos p : echo.anchors()) {
            AnchorKey ak = new AnchorKey(echo.worldId(), p.x(), p.y(), p.z());
            List<Echo> at = byAnchor.get(ak);
            if (at != null) {
                at.remove(echo);
                if (at.isEmpty()) byAnchor.remove(ak);
            }
        }
        if (echo.persistent()) storage.delete(echo.id());
        plugin.getLogger().info("Echo " + echo.id() + " (" + echo.ownerName() + ") entfernt: " + reason);
    }

    public Echo get(String id) {
        return byId.get(id);
    }

    /** Echoes, deren Anker an dieser Position liegt (meist 0 oder 1). */
    public List<Echo> byAnchor(UUID world, int x, int y, int z) {
        List<Echo> l = byAnchor.get(new AnchorKey(world, x, y, z));
        return l == null ? Collections.emptyList() : new ArrayList<>(l);
    }

    public List<Echo> inChunk(UUID world, int cx, int cz) {
        List<Echo> l = byChunk.get(new ChunkKey(world, cx, cz));
        return l == null ? Collections.emptyList() : new ArrayList<>(l);
    }

    public Collection<Echo> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** Kopie, damit beim Iterieren entfernt werden darf. */
    public List<Echo> snapshot() {
        return new ArrayList<>(byId.values());
    }

    public List<Echo> byOwner(UUID owner) {
        List<Echo> out = new ArrayList<>();
        for (Echo e : byId.values()) if (e.owner().equals(owner)) out.add(e);
        return out;
    }

    public int size() {
        return byId.size();
    }
}
