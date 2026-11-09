package dev.deathechoes.replay;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.config.Messages;
import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.Echo;
import dev.deathechoes.record.Recording;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Wiedergabe eines einzelnen Echoes als Mannequin.
 *
 * <p>Zustandsautomat: PLAYING (Frames abspielen, pro Tick interpoliert) → Tod-Effekt → PAUSE
 * (Entity entfernt) → wieder PLAYING. Die Entity ist nie persistent und nur für Zuschauer sichtbar.</p>
 */
public final class EchoPlayback {

    /** Zwischen zwei Frames weiter als das = Teleport, nicht interpolieren. */
    private static final double SNAP_DISTANCE_SQ = 8 * 8;
    private static final int AURA_EVERY_TICKS = 3;

    private enum Phase { PLAYING, PAUSE }

    private final DeathEchoesPlugin plugin;
    private final Echo echo;
    private final World world;
    private final Recording rec;

    private Phase phase = Phase.PLAYING;
    private int tick;
    private int pause;
    private int lastFrame = -1;
    private Mannequin entity;
    private Material shownItem;
    private Pose shownPose;
    private final Set<UUID> viewerIds = new HashSet<>();
    private final List<Player> viewers = new ArrayList<>();
    private final Location scratch;

    public EchoPlayback(DeathEchoesPlugin plugin, Echo echo, World world) {
        this.plugin = plugin;
        this.echo = echo;
        this.world = world;
        this.rec = echo.recording();
        this.scratch = new Location(world, 0, 0, 0);
    }

    public Echo echo() {
        return echo;
    }

    public Mannequin entity() {
        return entity;
    }

    public int viewerCount() {
        return viewers.size();
    }

    /** Neue Zuschauerliste übernehmen und Sichtbarkeit der Entity anpassen. */
    public void updateViewers(List<Player> now) {
        Set<UUID> nowIds = new HashSet<>();
        for (Player p : now) nowIds.add(p.getUniqueId());
        if (entity != null) {
            for (Player old : viewers) {
                if (!nowIds.contains(old.getUniqueId()) && old.isOnline()) old.hideEntity(plugin, entity);
            }
            for (Player p : now) {
                if (!viewerIds.contains(p.getUniqueId())) p.showEntity(plugin, entity);
            }
        }
        viewers.clear();
        viewers.addAll(now);
        viewerIds.clear();
        viewerIds.addAll(nowIds);
    }

    /** Ein Server-Tick. */
    public void tick() {
        PluginConfig cfg = plugin.settings();
        if (phase == Phase.PAUSE) {
            if (--pause <= 0) {
                phase = Phase.PLAYING;
                tick = 0;
                lastFrame = -1;
            }
            return;
        }
        int frames = rec.frames();
        if (frames < 2) return;
        int interval = rec.intervalTicks();
        int i = Math.min(tick / interval, frames - 1);
        int j = Math.min(i + 1, frames - 1);
        double t = (tick % interval) / (double) interval;

        double ax = rec.x()[i], ay = rec.y()[i], az = rec.z()[i];
        double bx = rec.x()[j], by = rec.y()[j], bz = rec.z()[j];
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        if (dx * dx + dy * dy + dz * dz > SNAP_DISTANCE_SQ) t = 0;
        scratch.setX(ax + dx * t);
        scratch.setY(ay + dy * t);
        scratch.setZ(az + dz * t);
        scratch.setYaw(lerpAngle(rec.yaw()[i], rec.yaw()[j], t));
        scratch.setPitch((float) (rec.pitch()[i] + (rec.pitch()[j] - rec.pitch()[i]) * t));

        boolean chunkLoaded = world.isChunkLoaded(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4);
        if (entity != null && !entity.isValid()) {
            // z. B. durch Chunk-Unload verworfen (nicht persistent) – beim nächsten Durchlauf neu erzeugen
            entity = null;
        }
        if (entity == null && chunkLoaded) {
            spawn(scratch, cfg);
            if (tick == 0) Effects.loopStart(cfg, viewers, scratch);
        } else if (entity != null && chunkLoaded) {
            entity.teleport(scratch);
            // Kopf und Körper explizit drehen, sonst hinkt die Kopfrotation bei Living Entities nach
            entity.setRotation(scratch.getYaw(), scratch.getPitch());
            entity.setBodyYaw(scratch.getYaw());
        }

        if (entity != null && i != lastFrame) {
            applyFrame(i, cfg);
            lastFrame = i;
        }
        if (entity != null && tick % AURA_EVERY_TICKS == 0) {
            Effects.aura(cfg, viewers, scratch, (rec.flags()[i] & Recording.FLAG_SPRINT) != 0);
        }

        tick++;
        if (tick > rec.durationTicks()) {
            Location end = new Location(world, rec.x()[frames - 1], rec.y()[frames - 1], rec.z()[frames - 1]);
            if (entity != null) entity.playHurtAnimation(0);
            Effects.death(cfg, viewers, end, echo.cause());
            despawn();
            phase = Phase.PAUSE;
            pause = Math.max(1, cfg.loopPauseTicks());
        }
    }

    private void applyFrame(int i, PluginConfig cfg) {
        Pose pose = switch (rec.pose()[i]) {
            case Recording.POSE_SNEAKING -> Pose.SNEAKING;
            case Recording.POSE_SWIMMING -> Pose.SWIMMING;
            case Recording.POSE_GLIDING -> Pose.FALL_FLYING;
            case Recording.POSE_SLEEPING -> Pose.SLEEPING;
            default -> Pose.STANDING;
        };
        if (!Mannequin.validPoses().contains(pose)) pose = Pose.STANDING;
        if (pose != shownPose) {
            entity.setPose(pose, true);
            shownPose = pose;
        }
        Material item = rec.item()[i];
        if (item != shownItem) {
            entity.getEquipment().setItemInMainHand(item == null ? null : new ItemStack(item));
            shownItem = item;
        }
        byte flags = rec.flags()[i];
        if ((flags & Recording.FLAG_SWING) != 0) {
            entity.swingMainHand();
            Effects.swing(cfg, viewers, scratch);
        }
        if ((flags & Recording.FLAG_HURT) != 0) {
            entity.playHurtAnimation(0);
            Effects.hurt(cfg, viewers, scratch);
        }
    }

    private void spawn(Location at, PluginConfig cfg) {
        shownItem = null;
        shownPose = null;
        lastFrame = -1;
        try {
            entity = world.spawn(at.clone(), Mannequin.class, m -> configure(m, cfg));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Echo " + echo.id() + ": Mannequin konnte nicht erzeugt werden: " + e.getMessage());
            entity = null;
            return;
        }
        if (!entity.isValid()) {
            // Spawn wurde von einem anderen Plugin abgebrochen
            entity = null;
            return;
        }
        // Team: verhindert das Wegschieben von Spielern und liefert ggf. den Geister-Look
        plugin.replay().ghostTeam().addEntity(entity, cfg.appearance() == PluginConfig.Appearance.GHOST);
        for (Player v : viewers) v.showEntity(plugin, entity);
    }

    /** Aussehen hat sich geändert (z. B. Skin nachgeladen): Entity neu erzeugen, passiert im nächsten Tick. */
    public void refreshAppearance() {
        despawn();
    }

    private void configure(Mannequin m, PluginConfig cfg) {
        m.setPersistent(false);
        m.setVisibleByDefault(false);
        m.getPersistentDataContainer().set(plugin.entityKey(), PersistentDataType.STRING, echo.id());
        m.addScoreboardTag(ReplayService.ENTITY_TAG);
        m.setInvulnerable(true);
        m.setGravity(false);
        m.setSilent(true);
        m.setCollidable(false);
        m.setImmovable(true);
        m.setAI(false);
        m.setGlowing(cfg.glowing());
        if (cfg.appearance() != PluginConfig.Appearance.SKIN) {
            // OUTLINE und GHOST: Flag plus unendlicher Effekt ohne Partikel: Living Entities setzen Unsichtbarkeit sonst
            // jeden Tick anhand ihrer Effekte zurück
            m.setInvisible(true);
            m.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        m.setDescription(null);
        String name = cfg.nameFormat();
        if (name != null && !name.isBlank()) {
            m.customName(Messages.colorize(name.replace("{player}", echo.ownerName())));
            m.setCustomNameVisible(true);
        }
        if (echo.skinValue() != null) {
            m.setProfile(ResolvableProfile.resolvableProfile()
                    .name(echo.ownerName())
                    .addProperty(new ProfileProperty("textures", echo.skinValue(), echo.skinSignature()))
                    .build());
        }
        EntityEquipment eq = m.getEquipment();
        Material[] armor = rec.armor();
        if (armor != null && armor.length == 4) {
            eq.setHelmet(stack(armor[0]));
            eq.setChestplate(stack(armor[1]));
            eq.setLeggings(stack(armor[2]));
            eq.setBoots(stack(armor[3]));
        }
        for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
            try {
                eq.setDropChance(slot, 0f);
            } catch (IllegalArgumentException ignored) {
                // nicht jeder Slot unterstützt Drop-Chancen
            }
        }
    }

    private static ItemStack stack(Material m) {
        return m == null ? null : new ItemStack(m);
    }

    public void despawn() {
        if (entity != null) {
            plugin.replay().ghostTeam().removeEntity(entity);
            entity.remove();
            entity = null;
        }
    }

    /** Wiedergabe beenden und Entity entfernen. */
    public void stop() {
        despawn();
        viewers.clear();
        viewerIds.clear();
    }

    /** Aktuelle Position der Gestalt (oder Todesort, falls gerade keine Entity existiert). */
    public Location currentLocation() {
        return entity != null ? entity.getLocation() : echo.location(world);
    }

    public List<Player> viewers() {
        return viewers;
    }

    static float lerpAngle(float a, float b, double t) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return (float) (a + diff * t);
    }
}
