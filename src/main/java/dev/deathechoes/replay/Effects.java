package dev.deathechoes.replay;

import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.DeathCause;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Partikel und Geräusche. Alles wird nur an die Zuschauer geschickt (Player#spawnParticle /
 * Player#playSound), damit Spieler ohne deathechoes.see nichts davon mitbekommen.
 */
public final class Effects {

    private Effects() {
    }

    private static void particle(Collection<? extends Player> viewers, Particle p, Location at, int count,
                                 double spread, double speed) {
        for (Player v : viewers) {
            v.spawnParticle(p, at, count, spread, spread, spread, speed);
        }
    }

    private static void sound(PluginConfig cfg, Collection<? extends Player> viewers, Sound s, Location at,
                              float volumeFactor, float pitch) {
        if (!cfg.sounds()) return;
        float volume = cfg.soundVolume() * volumeFactor;
        if (volume <= 0) return;
        for (Player v : viewers) {
            v.playSound(at, s, SoundCategory.AMBIENT, volume, pitch);
        }
    }

    /** Leichte Seelen-Aura um die Gestalt (alle paar Ticks). */
    public static void aura(PluginConfig cfg, Collection<? extends Player> viewers, Location body, boolean sprinting) {
        if (!cfg.particles()) return;
        particle(viewers, Particle.SOUL, body.clone().add(0, 1.0, 0), 1, 0.25, 0.01);
        if (sprinting) {
            particle(viewers, Particle.SOUL_FIRE_FLAME, body.clone().add(0, 0.1, 0), 1, 0.15, 0.0);
        }
    }

    public static void loopStart(PluginConfig cfg, Collection<? extends Player> viewers, Location at) {
        if (cfg.particles()) particle(viewers, Particle.SCULK_SOUL, at.clone().add(0, 1, 0), 6, 0.3, 0.02);
        sound(cfg, viewers, Sound.PARTICLE_SOUL_ESCAPE, at, 1.0f, 0.6f);
    }

    public static void swing(PluginConfig cfg, Collection<? extends Player> viewers, Location at) {
        sound(cfg, viewers, Sound.ENTITY_PLAYER_ATTACK_WEAK, at, 0.5f, 0.5f);
    }

    public static void hurt(PluginConfig cfg, Collection<? extends Player> viewers, Location at) {
        sound(cfg, viewers, Sound.ENTITY_PLAYER_HURT, at, 0.4f, 0.5f);
    }

    /** Effekt am Ende der Aufnahme, passend zur Todesursache. */
    public static void death(PluginConfig cfg, Collection<? extends Player> viewers, Location at, DeathCause cause) {
        Location body = at.clone().add(0, 1, 0);
        if (cfg.particles()) {
            switch (cause) {
                case EXPLOSION -> {
                    particle(viewers, Particle.EXPLOSION, body, 1, 0, 0);
                    particle(viewers, Particle.LARGE_SMOKE, body, 15, 0.6, 0.02);
                }
                case FIRE -> particle(viewers, Particle.FLAME, body, 30, 0.4, 0.03);
                case LAVA -> {
                    particle(viewers, Particle.FLAME, body, 25, 0.4, 0.03);
                    particle(viewers, Particle.LAVA, body, 8, 0.3, 0);
                }
                case DROWNING -> {
                    particle(viewers, Particle.BUBBLE_POP, body, 30, 0.4, 0.02);
                    particle(viewers, Particle.SPLASH, body, 20, 0.4, 0);
                }
                case FALL -> {
                    particle(viewers, Particle.CLOUD, at.clone().add(0, 0.1, 0), 20, 0.5, 0.02);
                    particle(viewers, Particle.POOF, at.clone().add(0, 0.2, 0), 10, 0.4, 0.02);
                }
                case VOID -> {
                    particle(viewers, Particle.PORTAL, body, 60, 0.5, 0.5);
                    particle(viewers, Particle.REVERSE_PORTAL, body, 20, 0.3, 0.05);
                }
                case PROJECTILE -> particle(viewers, Particle.CRIT, body, 25, 0.4, 0.2);
                case MELEE -> {
                    particle(viewers, Particle.SWEEP_ATTACK, body, 1, 0, 0);
                    particle(viewers, Particle.DAMAGE_INDICATOR, body, 8, 0.3, 0.1);
                }
                case MAGIC -> particle(viewers, Particle.WITCH, body, 30, 0.4, 0.05);
                case FREEZE -> particle(viewers, Particle.SNOWFLAKE, body, 35, 0.4, 0.02);
                case LIGHTNING -> particle(viewers, Particle.ELECTRIC_SPARK, body, 40, 0.5, 0.2);
                case SONIC -> particle(viewers, Particle.SCULK_SOUL, body, 20, 0.4, 0.05);
                default -> particle(viewers, Particle.SMOKE, body, 20, 0.4, 0.02);
            }
            particle(viewers, Particle.SOUL, body, 10, 0.3, 0.04);
        }
        switch (cause) {
            case EXPLOSION -> sound(cfg, viewers, Sound.ENTITY_GENERIC_EXPLODE, at, 0.5f, 0.6f);
            case FIRE -> sound(cfg, viewers, Sound.ENTITY_PLAYER_HURT_ON_FIRE, at, 0.6f, 0.6f);
            case LAVA -> sound(cfg, viewers, Sound.BLOCK_LAVA_EXTINGUISH, at, 0.6f, 0.6f);
            case DROWNING -> sound(cfg, viewers, Sound.ENTITY_PLAYER_HURT_DROWN, at, 0.6f, 0.6f);
            case FALL -> sound(cfg, viewers, Sound.ENTITY_PLAYER_BIG_FALL, at, 0.7f, 0.6f);
            case VOID -> sound(cfg, viewers, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, at, 0.6f, 0.6f);
            case FREEZE -> sound(cfg, viewers, Sound.ENTITY_PLAYER_HURT_FREEZE, at, 0.6f, 0.6f);
            case LIGHTNING -> sound(cfg, viewers, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, at, 0.15f, 1.4f);
            case SONIC -> sound(cfg, viewers, Sound.ENTITY_WARDEN_HEARTBEAT, at, 0.8f, 0.6f);
            default -> sound(cfg, viewers, Sound.ENTITY_PLAYER_HURT, at, 0.6f, 0.5f);
        }
        sound(cfg, viewers, Sound.PARTICLE_SOUL_ESCAPE, at, 1.0f, 0.5f);
    }

    /** Abschied, wenn das Grab verschwindet. */
    public static void farewell(PluginConfig cfg, Collection<? extends Player> viewers, Location at) {
        Location body = at.clone().add(0, 1, 0);
        if (cfg.particles()) {
            particle(viewers, Particle.SOUL, body, 25, 0.4, 0.06);
            particle(viewers, Particle.SCULK_SOUL, body, 10, 0.3, 0.04);
            particle(viewers, Particle.END_ROD, body, 8, 0.2, 0.08);
        }
        sound(cfg, viewers, Sound.PARTICLE_SOUL_ESCAPE, at, 1.2f, 0.8f);
        sound(cfg, viewers, Sound.BLOCK_BEACON_DEACTIVATE, at, 0.5f, 1.4f);
    }
}
