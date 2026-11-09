package dev.deathechoes.echo;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;

/** Vereinfachte Todesursache, bestimmt den Effekt am Ende der Schleife. */
public enum DeathCause {
    EXPLOSION("Explosion"),
    FIRE("Feuer"),
    LAVA("Lava"),
    DROWNING("Ertrunken"),
    FALL("Sturz"),
    VOID("Leere"),
    PROJECTILE("Geschoss"),
    MELEE("Nahkampf"),
    MAGIC("Magie"),
    FREEZE("Erfroren"),
    LIGHTNING("Blitz"),
    SONIC("Schallwelle"),
    SUFFOCATION("Erstickt"),
    STARVATION("Verhungert"),
    GENERIC("Unbekannt");

    private final String label;

    DeathCause(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static DeathCause parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return GENERIC;
        }
    }

    public static DeathCause from(DamageSource source) {
        if (source == null) return GENERIC;
        DamageType t = source.getDamageType();
        if (t == DamageType.EXPLOSION || t == DamageType.PLAYER_EXPLOSION || t == DamageType.FIREWORKS
                || t == DamageType.BAD_RESPAWN_POINT || t == DamageType.FIREBALL || t == DamageType.UNATTRIBUTED_FIREBALL
                || t == DamageType.WITHER_SKULL) {
            return EXPLOSION;
        }
        if (t == DamageType.LAVA) return LAVA;
        if (t == DamageType.IN_FIRE || t == DamageType.ON_FIRE || t == DamageType.HOT_FLOOR || t == DamageType.CAMPFIRE) {
            return FIRE;
        }
        if (t == DamageType.DROWN || t == DamageType.DRY_OUT) return DROWNING;
        if (t == DamageType.FALL || t == DamageType.FLY_INTO_WALL || t == DamageType.STALAGMITE
                || t == DamageType.ENDER_PEARL || t == DamageType.MACE_SMASH) {
            return FALL;
        }
        if (t == DamageType.OUT_OF_WORLD || t == DamageType.OUTSIDE_BORDER || t == DamageType.GENERIC_KILL) return VOID;
        if (t == DamageType.ARROW || t == DamageType.TRIDENT || t == DamageType.MOB_PROJECTILE || t == DamageType.THROWN
                || t == DamageType.SPIT || t == DamageType.WIND_CHARGE || t == DamageType.SPEAR) {
            return PROJECTILE;
        }
        if (t == DamageType.MAGIC || t == DamageType.INDIRECT_MAGIC || t == DamageType.WITHER || t == DamageType.DRAGON_BREATH) {
            return MAGIC;
        }
        if (t == DamageType.FREEZE) return FREEZE;
        if (t == DamageType.LIGHTNING_BOLT) return LIGHTNING;
        if (t == DamageType.SONIC_BOOM) return SONIC;
        if (t == DamageType.IN_WALL || t == DamageType.CRAMMING || t == DamageType.FALLING_BLOCK
                || t == DamageType.FALLING_ANVIL || t == DamageType.FALLING_STALACTITE) {
            return SUFFOCATION;
        }
        if (t == DamageType.STARVE) return STARVATION;
        if (t == DamageType.MOB_ATTACK || t == DamageType.MOB_ATTACK_NO_AGGRO || t == DamageType.PLAYER_ATTACK
                || t == DamageType.STING || t == DamageType.THORNS || t == DamageType.CACTUS || t == DamageType.SWEET_BERRY_BUSH) {
            // Creeper-Explosionen laufen über EXPLOSION; das hier ist Nahkampf
            if (source.getCausingEntity() != null && source.getCausingEntity().getType() == EntityType.CREEPER) {
                return EXPLOSION;
            }
            return MELEE;
        }
        return GENERIC;
    }
}
