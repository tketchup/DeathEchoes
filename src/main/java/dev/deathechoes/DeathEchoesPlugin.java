package dev.deathechoes;

import dev.deathechoes.anchor.AnchorWatcher;
import dev.deathechoes.command.EchoesCommand;
import dev.deathechoes.config.Messages;
import dev.deathechoes.config.PluginConfig;
import dev.deathechoes.echo.Echo;
import dev.deathechoes.echo.EchoManager;
import dev.deathechoes.record.Recorder;
import dev.deathechoes.replay.ReplayService;
import dev.deathechoes.storage.EchoStorage;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * DeathEchoes – Geister-Replays der letzten Sekunden vor dem Tod, die so lange leben wie das Grab.
 *
 * <p>Start: Konfiguration → Speicher → Recorder → Wiedergabe → Anker-Wächter → Echoes asynchron laden
 * → Befehl. Stopp: Tasks beenden, alle Entities entfernen, ausstehende Schreibvorgänge abwarten.</p>
 */
public final class DeathEchoesPlugin extends JavaPlugin {

    private PluginConfig settings;
    private final Messages messages = new Messages();
    private NamespacedKey entityKey;
    private EchoStorage storage;
    private EchoManager echoes;
    private Recorder recorder;
    private ReplayService replay;
    private AnchorWatcher anchors;
    private dev.deathechoes.replay.SkinResolver skins;
    private volatile boolean enabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        entityKey = new NamespacedKey(this, "echo");

        storage = new EchoStorage(getDataFolder().toPath(), getLogger());
        skins = new dev.deathechoes.replay.SkinResolver(getLogger());
        echoes = new EchoManager(this, storage);
        recorder = new Recorder(this);
        replay = new ReplayService(this);
        anchors = new AnchorWatcher(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(recorder, this);
        pm.registerEvents(replay, this);
        pm.registerEvents(anchors, this);

        replay.ghostTeam().resetStale();
        replay.ghostTeam().configure(settings.pushPlayers());
        int orphans = replay.purgeOrphans();
        if (orphans > 0) getLogger().info(orphans + " verwaiste Echo-Entities entfernt.");

        recorder.start();
        replay.start();
        anchors.start();
        enabled = true;

        // Echoes vom IO-Thread laden, dann auf dem Main-Thread registrieren und prüfen
        storage.loadAll().whenComplete((list, error) -> getServer().getScheduler().runTask(this, () -> {
            if (!enabled) return;
            if (error != null) {
                getLogger().log(Level.SEVERE, "Echoes konnten nicht geladen werden", error);
                return;
            }
            for (Echo e : list) echoes.addLoaded(e);
            getLogger().info(list.size() + " gespeicherte Echoes geladen.");
            anchors.validateLoaded();
        }));

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new EchoesCommand(this).build(),
                        "Death Echoes verwalten", List.of("deathechoes")));

        boolean graveBox = pm.getPlugin("GraveBox") != null;
        getLogger().info("DeathEchoes aktiv (GraveBox " + (graveBox ? "gefunden" : "nicht gefunden")
                + ", Aufnahme " + settings.recordDurationSeconds() + "s alle " + settings.sampleIntervalTicks() + " Ticks).");
    }

    @Override
    public void onDisable() {
        enabled = false;
        if (anchors != null) anchors.stop();
        if (replay != null) replay.stop();
        if (recorder != null) recorder.shutdown();
        if (replay != null) {
            int left = replay.purgeOrphans();
            if (left > 0) getLogger().warning(left + " Echo-Entities beim Beenden nachträglich entfernt.");
            replay.ghostTeam().shutdown();
        }
        if (skins != null) skins.shutdown();
        if (storage != null) storage.shutdown();
        getLogger().info("DeathEchoes deaktiviert, alle Echo-Entities entfernt.");
    }

    private void loadSettings() {
        reloadConfig();
        settings = PluginConfig.load(getConfig(), getLogger());
        messages.load(getConfig());
    }

    /** /echoes reload: Konfiguration neu laden und alle Dienste neu starten. */
    public void reloadAll() {
        loadSettings();
        replay.stop();
        replay.ghostTeam().configure(settings.pushPlayers());
        recorder.start();
        replay.start();
        anchors.start();
    }

    public PluginConfig settings() { return settings; }
    public Messages messages() { return messages; }
    public NamespacedKey entityKey() { return entityKey; }
    public EchoManager echoes() { return echoes; }
    public Recorder recorder() { return recorder; }
    public ReplayService replay() { return replay; }
    public AnchorWatcher anchors() { return anchors; }
    public dev.deathechoes.replay.SkinResolver skins() { return skins; }
}
