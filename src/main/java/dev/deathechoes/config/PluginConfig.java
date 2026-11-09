package dev.deathechoes.config;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Unveränderlicher Schnappschuss der config.yml. Bei /echoes reload wird ein neues Objekt gebaut,
 * damit laufende Tasks nie eine halb geladene Konfiguration sehen.
 */
public record PluginConfig(
        int recordDurationSeconds,
        int sampleIntervalTicks,
        double viewRadius,
        int maxActiveTotal,
        int maxActivePerChunk,
        int loopPauseTicks,
        Appearance appearance,
        boolean glowing,
        boolean skinByName,
        boolean pushPlayers,
        String nameFormat,
        int maxPerPlayer,
        long lifetimeMillis,
        boolean echoWithoutGrave,
        long gravelessLifetimeMillis,
        boolean removeWhenEmpty,
        long testLifetimeMillis,
        int anchorSearchRadius,
        int anchorSearchDelayTicks,
        int anchorCheckIntervalTicks,
        Set<Material> containerMaterials,
        boolean particles,
        boolean sounds,
        float soundVolume,
        boolean worldWhitelist,
        Set<String> worldList,
        boolean debug) {

    public enum Appearance { SKIN, GHOST, OUTLINE }

    /** Anzahl der Frames, die der Ringpuffer fassen muss. */
    public int bufferCapacity() {
        return Math.max(2, (recordDurationSeconds * 20) / sampleIntervalTicks + 1);
    }

    public boolean isWorldEnabled(World world) {
        boolean listed = worldList.contains(world.getName().toLowerCase(Locale.ROOT));
        return worldWhitelist == listed;
    }

    public static PluginConfig load(FileConfiguration c, Logger log) {
        int duration = clamp(c.getInt("recording.duration-seconds", 8), 1, 60, "recording.duration-seconds", log);
        int interval = clamp(c.getInt("recording.sample-interval-ticks", 2), 1, 20, "recording.sample-interval-ticks", log);

        Appearance appearance;
        String rawAppearance = c.getString("playback.appearance", "SKIN");
        try {
            appearance = Appearance.valueOf(rawAppearance.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("playback.appearance '" + rawAppearance + "' ist ungültig, nutze SKIN.");
            appearance = Appearance.SKIN;
        }

        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("anchor.container-materials")) {
            Material m = Material.matchMaterial(name.trim());
            if (m == null || !m.isBlock()) {
                log.warning("anchor.container-materials: '" + name + "' ist kein gültiger Block, wird ignoriert.");
            } else {
                materials.add(m);
            }
        }
        if (materials.isEmpty()) {
            log.warning("anchor.container-materials ist leer, nutze CHEST und BARREL.");
            materials.add(Material.CHEST);
            materials.add(Material.BARREL);
        }

        Set<String> worlds = new HashSet<>();
        List<String> rawWorlds = c.getStringList("worlds.list");
        for (String w : rawWorlds) worlds.add(w.toLowerCase(Locale.ROOT));
        String mode = c.getString("worlds.mode", "BLACKLIST").trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("BLACKLIST") && !mode.equals("WHITELIST")) {
            log.warning("worlds.mode '" + mode + "' ist ungültig, nutze BLACKLIST.");
            mode = "BLACKLIST";
        }

        return new PluginConfig(
                duration,
                interval,
                Math.max(1, c.getDouble("playback.view-radius", 24)),
                Math.max(0, c.getInt("playback.max-active-total", 20)),
                Math.max(0, c.getInt("playback.max-active-per-chunk", 2)),
                Math.max(0, c.getInt("playback.loop-pause-ticks", 40)),
                appearance,
                c.getBoolean("playback.glowing", true),
                c.getBoolean("playback.skin-by-name", false),
                c.getBoolean("playback.push-players", false),
                c.getString("playback.name-format", "&7Echo von &f{player}"),
                Math.max(1, c.getInt("echoes.max-per-player", 3)),
                Math.max(0, c.getLong("echoes.lifetime-minutes", 0)) * 60_000L,
                c.getBoolean("echoes.echo-without-grave", false),
                Math.max(5, c.getLong("echoes.graveless-lifetime-seconds", 120)) * 1000L,
                c.getBoolean("echoes.remove-when-empty", true),
                Math.max(5, c.getLong("echoes.test-lifetime-seconds", 60)) * 1000L,
                clamp(c.getInt("anchor.search-radius", 2), 0, 6, "anchor.search-radius", log),
                clamp(c.getInt("anchor.search-delay-ticks", 2), 1, 20, "anchor.search-delay-ticks", log),
                clamp(c.getInt("anchor.check-interval-ticks", 30), 5, 1200, "anchor.check-interval-ticks", log),
                Set.copyOf(materials),
                c.getBoolean("effects.particles", true),
                c.getBoolean("effects.sounds", true),
                (float) Math.max(0, Math.min(1, c.getDouble("effects.sound-volume", 0.35))),
                mode.equals("WHITELIST"),
                Set.copyOf(worlds),
                c.getBoolean("debug", false));
    }

    private static int clamp(int value, int min, int max, String key, Logger log) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            log.warning(key + " = " + value + " liegt außerhalb von " + min + ".." + max + ", nutze " + fixed + ".");
            return fixed;
        }
        return value;
    }
}
