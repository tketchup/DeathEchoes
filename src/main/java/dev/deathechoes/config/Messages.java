package dev.deathechoes.config;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Nachrichten aus dem Abschnitt "messages" mit &-Farbcodes und {platzhaltern}. */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<String, String> raw = new HashMap<>();
    private String prefix = "";

    public void load(FileConfiguration config) {
        raw.clear();
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                raw.put(key, section.getString(key, key));
            }
        }
        prefix = raw.getOrDefault("prefix", "");
    }

    /** Text ohne Präfix, Platzhalter als Paare: "id", "abc", "count", "3", ... */
    public String format(String key, Object... placeholders) {
        String text = raw.getOrDefault(key, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return text;
    }

    public Component component(String key, Object... placeholders) {
        return LEGACY.deserialize(format(key, placeholders));
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(LEGACY.deserialize(prefix + format(key, placeholders)));
    }

    /** Freien Text mit Farbcodes senden (für Listenzeilen ohne Präfix). */
    public void sendLine(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(component(key, placeholders));
    }

    public static Component colorize(String legacyText) {
        return LEGACY.deserialize(legacyText);
    }
}
