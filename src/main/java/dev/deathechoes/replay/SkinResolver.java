package dev.deathechoes.replay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holt für {@code skin-by-name} die signierten Skin-Daten eines Namens von der Mojang-API.
 *
 * <p>Nur für Offline-Mode-Server nötig (dort enthält das Spielerprofil keine Textur). Läuft komplett
 * asynchron über den {@link HttpClient}; Ergebnisse (auch "Name unbekannt") werden im Speicher gecacht,
 * Netzwerkfehler nicht, damit später ein neuer Versuch möglich ist.</p>
 */
public final class SkinResolver {

    public record Skin(String value, String signature) { }

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Map<String, CompletableFuture<Optional<Skin>>> cache = new ConcurrentHashMap<>();

    public SkinResolver(Logger log) {
        this.log = log;
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    public CompletableFuture<Optional<Skin>> resolve(String name) {
        if (!isValidName(name)) return CompletableFuture.completedFuture(Optional.empty());
        String key = name.toLowerCase(Locale.ROOT);
        CompletableFuture<Optional<Skin>> f = cache.computeIfAbsent(key, k -> fetch(name));
        f.whenComplete((r, err) -> {
            if (err != null) cache.remove(key, f);
        });
        return f;
    }

    private CompletableFuture<Optional<Skin>> fetch(String name) {
        HttpRequest byName = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .timeout(TIMEOUT).GET().build();
        return http.sendAsync(byName, HttpResponse.BodyHandlers.ofString())
                .thenCompose(res -> {
                    if (res.statusCode() == 204 || res.statusCode() == 404) {
                        log.info("skin-by-name: Kein Mojang-Account mit dem Namen " + name + ".");
                        return CompletableFuture.completedFuture(Optional.<Skin>empty());
                    }
                    if (res.statusCode() != 200) {
                        throw new IllegalStateException("Mojang-API antwortet mit HTTP " + res.statusCode());
                    }
                    String id = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
                    HttpRequest profile = HttpRequest.newBuilder(URI.create(
                                    "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                            .timeout(TIMEOUT).GET().build();
                    return http.sendAsync(profile, HttpResponse.BodyHandlers.ofString()).thenApply(p -> {
                        if (p.statusCode() != 200) {
                            throw new IllegalStateException("Session-Server antwortet mit HTTP " + p.statusCode());
                        }
                        JsonObject o = JsonParser.parseString(p.body()).getAsJsonObject();
                        for (JsonElement el : o.getAsJsonArray("properties")) {
                            JsonObject prop = el.getAsJsonObject();
                            if ("textures".equals(prop.get("name").getAsString())) {
                                String sig = prop.has("signature") ? prop.get("signature").getAsString() : null;
                                return Optional.of(new Skin(prop.get("value").getAsString(), sig));
                            }
                        }
                        return Optional.<Skin>empty();
                    });
                })
                .whenComplete((r, err) -> {
                    if (err != null) log.warning("skin-by-name: Skin für " + name + " nicht abrufbar: " + err.getMessage());
                });
    }

    public void shutdown() {
        cache.clear();
        http.shutdownNow();
    }
}
