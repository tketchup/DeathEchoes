package dev.deathechoes.command;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.deathechoes.DeathEchoesPlugin;
import dev.deathechoes.anchor.AnchorWatcher;
import dev.deathechoes.config.Messages;
import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.BlockPos;
import dev.deathechoes.echo.DeathCause;
import dev.deathechoes.echo.Echo;
import dev.deathechoes.echo.EchoManager;
import dev.deathechoes.record.FrameBuffer;
import dev.deathechoes.record.Recorder;
import dev.deathechoes.record.Recording;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mannequin;
import dev.deathechoes.replay.ReplayService;

/** /echoes – registriert über die Brigadier-Lifecycle-API von Paper. */
public final class EchoesCommand {

    private static final String ADMIN = "deathechoes.admin";
    private static final String SEE = "deathechoes.see";
    /** Besitzer synthetischer Debug-Echoes. */
    private static final UUID DEBUG_OWNER = new UUID(0, 0);

    private final DeathEchoesPlugin plugin;

    public EchoesCommand(DeathEchoesPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private static Predicate<CommandSourceStack> perm(String node) {
        return s -> s.getSender().hasPermission(node);
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("echoes")
                .requires(perm(SEE))
                .executes(ctx -> list(ctx, null))
                .then(literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(argument("player", StringArgumentType.word()).requires(perm(ADMIN))
                                .suggests(this::suggestOwners)
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("remove").requires(perm(ADMIN))
                        .then(argument("id", StringArgumentType.word()).suggests(this::suggestIds)
                                .executes(this::remove)))
                .then(literal("clear").requires(perm(ADMIN))
                        .executes(ctx -> clear(ctx, null))
                        .then(argument("player", StringArgumentType.word()).suggests(this::suggestOwners)
                                .executes(ctx -> clear(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("reload").requires(perm(ADMIN)).executes(this::reload))
                .then(literal("test").requires(perm(ADMIN))
                        .executes(ctx -> test(ctx, DeathCause.GENERIC))
                        .then(argument("cause", StringArgumentType.word()).suggests(this::suggestCauses)
                                .executes(ctx -> test(ctx, DeathCause.parse(
                                        StringArgumentType.getString(ctx, "cause").toUpperCase(Locale.ROOT))))))
                .then(literal("debug").requires(perm(ADMIN))
                        .then(literal("recorder")
                                .executes(ctx -> debugRecorder(ctx, null))
                                .then(argument("player", StringArgumentType.word()).suggests(this::suggestOnline)
                                        .executes(ctx -> debugRecorder(ctx, StringArgumentType.getString(ctx, "player")))))
                        .then(literal("stats").executes(this::debugStats))
                        .then(literal("forceplay")
                                .then(argument("id", StringArgumentType.word()).suggests(this::suggestIds)
                                        .executes(this::debugForcePlay)))
                        .then(literal("spawn")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> debugSpawn(ctx, null, DeathCause.EXPLOSION))
                                                        .then(argument("world", StringArgumentType.word()).suggests(this::suggestWorlds)
                                                                .executes(ctx -> debugSpawn(ctx, StringArgumentType.getString(ctx, "world"), DeathCause.EXPLOSION))
                                                                .then(argument("cause", StringArgumentType.word()).suggests(this::suggestCauses)
                                                                        .executes(ctx -> debugSpawn(ctx, StringArgumentType.getString(ctx, "world"),
                                                                                DeathCause.parse(StringArgumentType.getString(ctx, "cause").toUpperCase(Locale.ROOT))))
                                                                        .then(argument("name", StringArgumentType.word())
                                                                                .executes(ctx -> debugSpawn(ctx, StringArgumentType.getString(ctx, "world"),
                                                                                        DeathCause.parse(StringArgumentType.getString(ctx, "cause").toUpperCase(Locale.ROOT)))))))))))
                        .then(literal("check").executes(this::debugCheck)))
                .build();
    }

    // ------------------------------------------------------------------ Vorschläge

    private CompletableFuture<Suggestions> suggestIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        String rem = b.getRemainingLowerCase();
        for (Echo e : plugin.echoes().all()) if (e.id().startsWith(rem)) b.suggest(e.id());
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOwners(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        String rem = b.getRemainingLowerCase();
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (Echo e : plugin.echoes().all()) names.add(e.ownerName());
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        for (String n : names) if (n.toLowerCase(Locale.ROOT).startsWith(rem)) b.suggest(n);
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOnline(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        for (Player p : Bukkit.getOnlinePlayers()) b.suggest(p.getName());
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestWorlds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        for (World w : Bukkit.getWorlds()) b.suggest(w.getName());
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestCauses(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        String rem = b.getRemainingLowerCase();
        for (DeathCause c : DeathCause.values()) {
            String n = c.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(rem)) b.suggest(n);
        }
        return b.buildFuture();
    }

    // ------------------------------------------------------------------ Befehle

    private int list(CommandContext<CommandSourceStack> ctx, String playerName) {
        CommandSender sender = ctx.getSource().getSender();
        List<Echo> list = new ArrayList<>();
        if (playerName != null) {
            for (Echo e : plugin.echoes().all()) if (e.ownerName().equalsIgnoreCase(playerName)) list.add(e);
        } else if (sender instanceof Player p && !p.hasPermission(ADMIN)) {
            list.addAll(plugin.echoes().byOwner(p.getUniqueId()));
        } else {
            // Admins und Konsole sehen ohne Argument alle Echoes
            list.addAll(plugin.echoes().all());
        }
        if (list.isEmpty()) {
            msg().send(sender, "list-empty");
            return Command.SINGLE_SUCCESS;
        }
        list.sort(Comparator.comparingLong(Echo::createdAt));
        msg().send(sender, "list-header", "count", list.size());
        long now = System.currentTimeMillis();
        for (Echo e : list) {
            String state;
            World w = Bukkit.getWorld(e.worldId());
            if (e.playback() != null) state = msg().format("state-active");
            else if (w != null && w.isChunkLoaded(e.chunkX(), e.chunkZ())) state = msg().format("state-idle");
            else state = msg().format("state-unloaded");
            String cause = e.cause().label() + (e.killer() != null ? " (" + e.killer() + ")" : "")
                    + (e.hasAnchor() ? "" : ", ohne Grab") + (e.persistent() ? "" : ", Test");
            msg().sendLine(sender, "list-entry",
                    "id", e.id(), "player", e.ownerName(), "world", e.worldName(),
                    "x", (int) Math.floor(e.x()), "y", (int) Math.floor(e.y()), "z", (int) Math.floor(e.z()),
                    "cause", cause, "age", formatAge(now - e.createdAt()), "state", state);
        }
        return Command.SINGLE_SUCCESS;
    }

    static String formatAge(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "min";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "min";
        return (h / 24) + "d";
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Echo e = plugin.echoes().get(id);
        if (e == null) {
            msg().send(sender, "not-found", "id", id);
            return 0;
        }
        plugin.echoes().remove(e, EchoManager.Reason.ADMIN, true);
        msg().send(sender, "removed", "id", id);
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> ctx, String playerName) {
        int n = 0;
        for (Echo e : plugin.echoes().snapshot()) {
            if (playerName != null && !e.ownerName().equalsIgnoreCase(playerName)) continue;
            plugin.echoes().remove(e, EchoManager.Reason.CLEAR, true);
            n++;
        }
        msg().send(ctx.getSource().getSender(), "cleared", "count", n);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        msg().send(ctx.getSource().getSender(), "reloaded");
        return Command.SINGLE_SUCCESS;
    }

    /** Spielt die aktuelle Aufnahme des Ausführenden als Echo ab (ohne Tod, ohne Anker, nicht gespeichert). */
    private int test(CommandContext<CommandSourceStack> ctx, DeathCause cause) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player p)) {
            msg().send(sender, "players-only");
            return 0;
        }
        Recording rec = plugin.recorder().freeze(p, false);
        if (rec == null) {
            msg().send(sender, "test-empty");
            return 0;
        }
        PluginConfig cfg = plugin.settings();
        Location l = p.getLocation();
        long now = System.currentTimeMillis();
        String skinValue = null, skinSig = null;
        for (var prop : p.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) {
                skinValue = prop.getValue();
                skinSig = prop.getSignature();
            }
        }
        Echo e = new Echo(plugin.echoes().newId(), p.getUniqueId(), p.getName(), skinValue, skinSig,
                l.getWorld().getUID(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                List.of(), null, false, now, now + cfg.testLifetimeMillis(), cause, null, rec, false);
        plugin.echoes().add(e);
        msg().send(sender, "test-started", "id", e.id(), "frames", rec.frames(),
                "seconds", String.format(Locale.ROOT, "%.1f", rec.durationTicks() / 20.0));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ Debug

    /** Zeigt den Ringpuffer eines Spielers als Text/Zähler. */
    private int debugRecorder(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSender sender = ctx.getSource().getSender();
        Recorder rec = plugin.recorder();
        sender.sendMessage(Messages.colorize("&7Recorder: &f" + rec.trackedPlayers() + " &7Spieler, Kapazität &f"
                + rec.capacity() + " &7Frames à &f" + plugin.settings().sampleIntervalTicks() + " &7Ticks, Sample-Läufe &f"
                + rec.sampleCounter()));
        Player target = name != null ? Bukkit.getPlayerExact(name) : sender instanceof Player p ? p : null;
        if (target == null) {
            if (name != null) msg().send(sender, "unknown-player", "player", name);
            return Command.SINGLE_SUCCESS;
        }
        Recorder.Track t = rec.track(target.getUniqueId());
        if (t == null) {
            sender.sendMessage(Messages.colorize("&cKein Track für " + target.getName()));
            return Command.SINGLE_SUCCESS;
        }
        FrameBuffer b = t.buffer();
        int n = b.size();
        int swings = 0, hurts = 0, sprints = 0;
        int[] poses = new int[5];
        for (int i = 0; i < n; i++) {
            byte f = b.flags(i);
            if ((f & Recording.FLAG_SWING) != 0) swings++;
            if ((f & Recording.FLAG_HURT) != 0) hurts++;
            if ((f & Recording.FLAG_SPRINT) != 0) sprints++;
            byte pose = b.pose(i);
            if (pose >= 0 && pose < poses.length) poses[pose]++;
        }
        double seconds = Math.max(0, n - 1) * plugin.settings().sampleIntervalTicks() / 20.0;
        sender.sendMessage(Messages.colorize(String.format(Locale.ROOT,
                "&b%s&7: &f%d/%d &7Frames (&f%.1fs&7), Samples gesamt &f%d", target.getName(), n, b.capacity(), seconds, t.samples())));
        sender.sendMessage(Messages.colorize(String.format(Locale.ROOT,
                "&7Schläge &f%d &7Treffer &f%d &7Sprint &f%d &7| Posen: steh %d, schleich %d, schwimm %d, gleit %d, schlaf %d",
                swings, hurts, sprints, poses[0], poses[1], poses[2], poses[3], poses[4])));
        if (n > 0) {
            sender.sendMessage(Messages.colorize(String.format(Locale.ROOT,
                    "&7Ältester Frame: &f%.1f %.1f %.1f &7Neuester: &f%.1f %.1f %.1f",
                    b.x(0), b.y(0), b.z(0), b.x(n - 1), b.y(n - 1), b.z(n - 1))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int debugStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int entities = 0, tracked = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity en : w.getEntitiesByClass(Mannequin.class)) {
                if (en.getScoreboardTags().contains(ReplayService.ENTITY_TAG)) entities++;
            }
        }
        for (Echo e : plugin.echoes().all()) {
            if (e.playback() != null && e.playback().entity() != null) tracked++;
        }
        sender.sendMessage(Messages.colorize("&7Echoes: &f" + plugin.echoes().size() + " &7| aktiv: &f"
                + plugin.replay().activeCount() + " &7| Entities in der Welt: &f" + entities
                + " &7| davon verwaltet: &f" + tracked + " &7| Recorder-Spieler: &f" + plugin.recorder().trackedPlayers()));
        return Command.SINGLE_SUCCESS;
    }

    private int debugForcePlay(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Echo e = plugin.echoes().get(id);
        if (e == null) {
            msg().send(sender, "not-found", "id", id);
            return 0;
        }
        e.forcePlay(!e.forcePlay());
        sender.sendMessage(Messages.colorize("&7forceplay für &b" + id + "&7: &f" + e.forcePlay()));
        return Command.SINGLE_SUCCESS;
    }

    /** Prüft sofort alle Anker (statt auf den periodischen Task zu warten). */
    private int debugCheck(CommandContext<CommandSourceStack> ctx) {
        int before = plugin.echoes().size();
        for (Echo e : plugin.echoes().snapshot()) {
            AnchorWatcher.State s = plugin.anchors().check(e);
            ctx.getSource().getSender().sendMessage(Messages.colorize("&7" + e.id() + ": &f" + s));
            plugin.anchors().checkAndApply(e, true);
        }
        ctx.getSource().getSender().sendMessage(Messages.colorize("&7Entfernt: &f" + (before - plugin.echoes().size())));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Legt ein synthetisches, persistentes Echo an (für Konsolentests ohne echten Spieler). Steht in
     * Suchradius ein Container, wird er als Anker benutzt, sonst entsteht ein Echo ohne Grab.
     */
    private int debugSpawn(CommandContext<CommandSourceStack> ctx, String worldName, DeathCause cause) {
        CommandSender sender = ctx.getSource().getSender();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        World w = worldName != null ? Bukkit.getWorld(worldName)
                : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
        if (w == null) {
            sender.sendMessage(Messages.colorize("&cUnbekannte Welt: " + worldName));
            return 0;
        }
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            sender.sendMessage(Messages.colorize("&cChunk nicht geladen (forceload add " + x + " " + z + ")"));
            return 0;
        }
        PluginConfig cfg = plugin.settings();
        Recording rec = syntheticRecording(x + 0.5, y, z + 0.5, cfg);
        Block anchor = plugin.anchors().findAnyContainer(w, x, y, z);
        List<BlockPos> anchors = new ArrayList<>();
        Material mat = null;
        boolean hadContents = false;
        long now = System.currentTimeMillis();
        long expires;
        if (anchor != null) {
            anchors.add(BlockPos.of(anchor));
            Block other = AnchorWatcher.secondHalf(anchor);
            if (other != null) anchors.add(BlockPos.of(other));
            mat = anchor.getType();
            hadContents = AnchorWatcher.containerHasContents(anchor);
            expires = cfg.lifetimeMillis() > 0 ? now + cfg.lifetimeMillis() : 0;
        } else {
            expires = now + cfg.gravelessLifetimeMillis();
        }
        // Optionaler Name, z. B. um skin-by-name zu testen
        String name;
        try {
            name = StringArgumentType.getString(ctx, "name");
        } catch (IllegalArgumentException noName) {
            name = "Debug" + (plugin.echoes().byOwner(DEBUG_OWNER).size() + 1);
        }
        Echo e = new Echo(plugin.echoes().newId(), DEBUG_OWNER, name, null, null, w.getUID(), w.getName(),
                x + 0.5, y, z + 0.5, anchors, mat, hadContents, now, expires, cause, "Debug", rec, true);
        plugin.echoes().add(e);
        sender.sendMessage(Messages.colorize("&aDebug-Echo &b" + e.id() + " &aangelegt, Anker: &f"
                + (anchor != null ? mat + " " + anchors + (hadContents ? " (mit Inhalt)" : "") : "keiner")
                + "&a, " + rec.frames() + " Frames"));
        return Command.SINGLE_SUCCESS;
    }

    /** Ein Kreisbogen, der am Todesort endet, mit Schlägen, Schleichen und Treffern. */
    static Recording syntheticRecording(double ex, double ey, double ez, PluginConfig cfg) {
        int n = cfg.bufferCapacity();
        FrameBuffer b = new FrameBuffer(n);
        double radius = 3;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            double angle = Math.PI * 1.5 * (1 - t);
            double px = ex + Math.cos(angle) * radius - radius;
            double pz = ez + Math.sin(angle) * radius;
            float yaw = (float) Math.toDegrees(Math.atan2(-(Math.sin(angle)), Math.cos(angle)));
            byte pose = (t > 0.35 && t < 0.5) ? Recording.POSE_SNEAKING : Recording.POSE_STANDING;
            byte flags = 0;
            if (i % 8 == 0) flags |= Recording.FLAG_SWING;
            if (t > 0.9) flags |= Recording.FLAG_HURT;
            if (t < 0.3) flags |= Recording.FLAG_SPRINT;
            b.add(px, ey, pz, yaw, 0, pose, flags, i < n / 2 ? Material.DIAMOND_SWORD : Material.TORCH);
        }
        return b.freeze(cfg.sampleIntervalTicks(),
                new Material[] {Material.IRON_HELMET, Material.IRON_CHESTPLATE, null, Material.IRON_BOOTS});
    }
}
