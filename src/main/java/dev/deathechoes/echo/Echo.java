package dev.deathechoes.echo;

import dev.deathechoes.record.Recording;
import dev.deathechoes.replay.EchoPlayback;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Ein Echo: eingefrorene Aufnahme plus Ort, Anker (Grab-Container) und Lebensdauer.
 * Die Stammdaten sind unveränderlich; nur der Laufzeitzustand (Wiedergabe, Chunk geladen) ändert sich.
 */
public final class Echo {

    private final String id;
    private final UUID owner;
    private final String ownerName;
    /** Nachträglich setzbar (skin-by-name), sonst unveränderlich. Nur Main-Thread. */
    private volatile String skinValue;
    private volatile String skinSignature;
    private final UUID worldId;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    /** Leer = Echo ohne Grab. Bei Doppelkisten zwei Einträge. */
    private final List<BlockPos> anchors;
    private final Material anchorMaterial;
    /** Hatte der Container beim Verankern echten Inhalt? Nur dann greift remove-when-empty. */
    private final boolean anchorHadContents;
    private final long createdAt;
    /** 0 = unbegrenzt. */
    private final long expiresAt;
    private final DeathCause cause;
    private final String killer;
    private final Recording recording;
    /** false = Test-Echo, wird nie gespeichert. */
    private final boolean persistent;

    // Laufzeitzustand (nur Main-Thread)
    private EchoPlayback playback;
    private boolean forcePlay;
    private boolean removed;

    public Echo(String id, UUID owner, String ownerName, String skinValue, String skinSignature,
                UUID worldId, String worldName, double x, double y, double z,
                List<BlockPos> anchors, Material anchorMaterial, boolean anchorHadContents,
                long createdAt, long expiresAt, DeathCause cause, String killer,
                Recording recording, boolean persistent) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.skinValue = skinValue;
        this.skinSignature = skinSignature;
        this.worldId = worldId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.anchors = List.copyOf(anchors);
        this.anchorMaterial = anchorMaterial;
        this.anchorHadContents = anchorHadContents;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.cause = cause;
        this.killer = killer;
        this.recording = recording;
        this.persistent = persistent;
    }

    public String id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public String skinValue() { return skinValue; }
    public String skinSignature() { return skinSignature; }

    public void skin(String value, String signature) {
        this.skinValue = value;
        this.skinSignature = signature;
    }
    public UUID worldId() { return worldId; }
    public String worldName() { return worldName; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public List<BlockPos> anchors() { return anchors; }
    public boolean hasAnchor() { return !anchors.isEmpty(); }
    public Material anchorMaterial() { return anchorMaterial; }
    public boolean anchorHadContents() { return anchorHadContents; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public DeathCause cause() { return cause; }
    public String killer() { return killer; }
    public Recording recording() { return recording; }
    public boolean persistent() { return persistent; }

    public int chunkX() { return ((int) Math.floor(x)) >> 4; }
    public int chunkZ() { return ((int) Math.floor(z)) >> 4; }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    /** Todesort, falls die Welt geladen ist, sonst null. */
    public Location location(World world) {
        return new Location(world, x, y, z);
    }

    public EchoPlayback playback() { return playback; }
    public void playback(EchoPlayback playback) { this.playback = playback; }
    public boolean forcePlay() { return forcePlay; }
    public void forcePlay(boolean forcePlay) { this.forcePlay = forcePlay; }
    public boolean removed() { return removed; }
    public void markRemoved() { this.removed = true; }
}
