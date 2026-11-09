package dev.deathechoes.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.deathechoes.echo.BlockPos;
import dev.deathechoes.echo.DeathCause;
import dev.deathechoes.echo.Echo;
import dev.deathechoes.record.Recording;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;

/**
 * Speichert jedes Echo als eigene JSON-Datei unter {@code plugins/DeathEchoes/echoes/<id>.json}.
 *
 * <p>Alle Plattenzugriffe laufen auf einem eigenen Single-Thread-Executor, nie auf dem Main-Thread.
 * Weil {@link Echo} und {@link Recording} nach dem Erzeugen unveränderlich sind, darf der Worker
 * sie gefahrlos serialisieren. Geschrieben wird atomar (temp-Datei + move).</p>
 */
public final class EchoStorage {

    private static final int FORMAT_VERSION = 1;

    private final Path dir;
    private final Logger log;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DeathEchoes-IO");
        t.setDaemon(true);
        return t;
    });

    public EchoStorage(Path dataFolder, Logger log) {
        this.dir = dataFolder.resolve("echoes");
        this.log = log;
    }

    public CompletableFuture<List<Echo>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            List<Echo> out = new ArrayList<>();
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.log(Level.SEVERE, "Kann Ordner " + dir + " nicht anlegen", e);
                return out;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) {
                    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        out.add(fromJson(JsonParser.parseReader(r).getAsJsonObject()));
                    } catch (Exception e) {
                        log.warning("Echo-Datei " + f.getFileName() + " ist beschädigt (" + e.getMessage()
                                + "), wird nach .broken umbenannt.");
                        try {
                            Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {
                            // dann bleibt sie liegen und wird beim nächsten Start erneut gemeldet
                        }
                    }
                }
            } catch (IOException e) {
                log.log(Level.SEVERE, "Echoes konnten nicht gelesen werden", e);
            }
            return out;
        }, io);
    }

    public void save(Echo echo) {
        io.execute(() -> {
            Path target = dir.resolve(echo.id() + ".json");
            Path tmp = dir.resolve(echo.id() + ".json.tmp");
            try {
                Files.createDirectories(dir);
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    gson.toJson(toJson(echo), w);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Echo " + echo.id() + " konnte nicht gespeichert werden", e);
            }
        });
    }

    public void delete(String id) {
        io.execute(() -> {
            try {
                Files.deleteIfExists(dir.resolve(id + ".json"));
            } catch (IOException e) {
                log.log(Level.WARNING, "Echo-Datei " + id + " konnte nicht gelöscht werden", e);
            }
        });
    }

    /** Ausstehende Schreibvorgänge abwarten (onDisable). */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Speicher-Thread nicht rechtzeitig fertig, breche ab.");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ JSON

    static JsonObject toJson(Echo e) {
        JsonObject o = new JsonObject();
        o.addProperty("version", FORMAT_VERSION);
        o.addProperty("id", e.id());
        o.addProperty("owner", e.owner().toString());
        o.addProperty("ownerName", e.ownerName());
        if (e.skinValue() != null) {
            JsonObject skin = new JsonObject();
            skin.addProperty("value", e.skinValue());
            if (e.skinSignature() != null) skin.addProperty("signature", e.skinSignature());
            o.add("skin", skin);
        }
        o.addProperty("world", e.worldId().toString());
        o.addProperty("worldName", e.worldName());
        o.addProperty("x", e.x());
        o.addProperty("y", e.y());
        o.addProperty("z", e.z());
        JsonArray anchors = new JsonArray();
        for (BlockPos p : e.anchors()) {
            JsonArray a = new JsonArray();
            a.add(p.x());
            a.add(p.y());
            a.add(p.z());
            anchors.add(a);
        }
        o.add("anchors", anchors);
        if (e.anchorMaterial() != null) o.addProperty("anchorMaterial", e.anchorMaterial().name());
        o.addProperty("anchorHadContents", e.anchorHadContents());
        o.addProperty("createdAt", e.createdAt());
        o.addProperty("expiresAt", e.expiresAt());
        o.addProperty("cause", e.cause().name());
        if (e.killer() != null) o.addProperty("killer", e.killer());
        o.add("recording", recordingToJson(e.recording()));
        return o;
    }

    static JsonObject recordingToJson(Recording r) {
        JsonObject o = new JsonObject();
        o.addProperty("interval", r.intervalTicks());
        JsonArray x = new JsonArray(), y = new JsonArray(), z = new JsonArray();
        JsonArray yaw = new JsonArray(), pitch = new JsonArray(), pose = new JsonArray(), flags = new JsonArray();
        JsonArray item = new JsonArray();
        // Item-Palette statt Materialname pro Frame: kleiner und schneller zu lesen
        Map<Material, Integer> palette = new HashMap<>();
        JsonArray paletteJson = new JsonArray();
        for (int i = 0; i < r.frames(); i++) {
            x.add(round(r.x()[i], 1000));
            y.add(round(r.y()[i], 1000));
            z.add(round(r.z()[i], 1000));
            yaw.add(round(r.yaw()[i], 10));
            pitch.add(round(r.pitch()[i], 10));
            pose.add(r.pose()[i]);
            flags.add(r.flags()[i]);
            Material m = r.item()[i];
            if (m == null) {
                item.add(-1);
            } else {
                Integer idx = palette.get(m);
                if (idx == null) {
                    idx = palette.size();
                    palette.put(m, idx);
                    paletteJson.add(m.name());
                }
                item.add(idx);
            }
        }
        o.add("x", x);
        o.add("y", y);
        o.add("z", z);
        o.add("yaw", yaw);
        o.add("pitch", pitch);
        o.add("pose", pose);
        o.add("flags", flags);
        o.add("itemPalette", paletteJson);
        o.add("item", item);
        JsonArray armor = new JsonArray();
        for (Material m : r.armor()) armor.add(m == null ? "" : m.name());
        o.add("armor", armor);
        return o;
    }

    private static double round(double v, double scale) {
        return Math.round(v * scale) / scale;
    }

    static Echo fromJson(JsonObject o) {
        int version = o.get("version").getAsInt();
        if (version > FORMAT_VERSION) throw new IllegalStateException("unbekannte Version " + version);
        List<BlockPos> anchors = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("anchors")) {
            JsonArray a = el.getAsJsonArray();
            anchors.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
        String skinValue = null, skinSig = null;
        if (o.has("skin")) {
            JsonObject s = o.getAsJsonObject("skin");
            skinValue = s.get("value").getAsString();
            skinSig = s.has("signature") ? s.get("signature").getAsString() : null;
        }
        Material anchorMat = o.has("anchorMaterial") ? Material.matchMaterial(o.get("anchorMaterial").getAsString()) : null;
        if (!anchors.isEmpty() && anchorMat == null) throw new IllegalStateException("Anker ohne gültiges Material");
        return new Echo(
                o.get("id").getAsString(),
                UUID.fromString(o.get("owner").getAsString()),
                o.get("ownerName").getAsString(),
                skinValue, skinSig,
                UUID.fromString(o.get("world").getAsString()),
                o.get("worldName").getAsString(),
                o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                anchors, anchorMat,
                o.has("anchorHadContents") && o.get("anchorHadContents").getAsBoolean(),
                o.get("createdAt").getAsLong(),
                o.get("expiresAt").getAsLong(),
                DeathCause.parse(o.get("cause").getAsString()),
                o.has("killer") ? o.get("killer").getAsString() : null,
                recordingFromJson(o.getAsJsonObject("recording")),
                true);
    }

    static Recording recordingFromJson(JsonObject o) {
        JsonArray x = o.getAsJsonArray("x");
        int n = x.size();
        double[] cx = new double[n], cy = new double[n], cz = new double[n];
        float[] yaw = new float[n], pitch = new float[n];
        byte[] pose = new byte[n], flags = new byte[n];
        Material[] item = new Material[n];
        JsonArray jy = o.getAsJsonArray("y"), jz = o.getAsJsonArray("z");
        JsonArray jyaw = o.getAsJsonArray("yaw"), jpitch = o.getAsJsonArray("pitch");
        JsonArray jpose = o.getAsJsonArray("pose"), jflags = o.getAsJsonArray("flags"), jitem = o.getAsJsonArray("item");
        if (jy.size() != n || jz.size() != n || jyaw.size() != n || jpitch.size() != n
                || jpose.size() != n || jflags.size() != n || jitem.size() != n) {
            throw new IllegalStateException("Spaltenlängen der Aufnahme passen nicht zusammen");
        }
        List<Material> palette = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("itemPalette")) palette.add(Material.matchMaterial(el.getAsString()));
        for (int i = 0; i < n; i++) {
            cx[i] = x.get(i).getAsDouble();
            cy[i] = jy.get(i).getAsDouble();
            cz[i] = jz.get(i).getAsDouble();
            yaw[i] = jyaw.get(i).getAsFloat();
            pitch[i] = jpitch.get(i).getAsFloat();
            pose[i] = jpose.get(i).getAsByte();
            flags[i] = jflags.get(i).getAsByte();
            int idx = jitem.get(i).getAsInt();
            item[i] = idx >= 0 && idx < palette.size() ? palette.get(idx) : null;
        }
        Material[] armor = new Material[4];
        JsonArray ja = o.getAsJsonArray("armor");
        for (int i = 0; i < 4 && i < ja.size(); i++) {
            String s = ja.get(i).getAsString();
            armor[i] = s.isEmpty() ? null : Material.matchMaterial(s);
        }
        return new Recording(o.get("interval").getAsInt(), cx, cy, cz, yaw, pitch, pose, flags, item, armor);
    }
}
