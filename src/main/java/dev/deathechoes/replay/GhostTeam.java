package dev.deathechoes.replay;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Scoreboard-Teams der Echo-Mannequins auf dem Haupt-Scoreboard. Sie erledigen zwei Dinge, die der
 * Client nur über Teams kennt:
 *
 * <ul>
 *   <li><b>Kollision:</b> Spieler werden vom eigenen Client von Entities weggeschoben; das serverseitige
 *       {@code setCollidable(false)} kennt der Client nicht. Die Kollisionsregel eines Teams dagegen schon.</li>
 *   <li><b>Transparenz</b> ({@code appearance: GHOST}): Unsichtbare Mitglieder des eigenen Teams zeichnet der
 *       Client durchscheinend (feste Deckkraft von ca. 15 %), wenn "see friendly invisibles" an ist. Dafür
 *       kommen die Zuschauer mit ins Ghost-Team.</li>
 * </ul>
 *
 * <p>{@value #SOLID_TEAM} (SKIN/OUTLINE) enthält nur Mannequins, Regel NEVER: kein Schieben, keine
 * Nebenwirkung auf Spieler. {@value #GHOST_TEAM} enthält Mannequins und Zuschauer, Regel "nur andere
 * Teams schieben": Zuschauer und Echo schieben sich nicht, alles andere kollidiert normal.</p>
 *
 * <p>Rücksicht auf andere Plugins: Ein Spieler kommt nur ins Ghost-Team, wenn er das Haupt-Scoreboard
 * benutzt und in keinem anderen Team ist. Keine Farbe, kein Präfix. Spieler bleiben nur drin, solange
 * sie ein Echo sehen; beim Stoppen werden beide Teams entfernt.</p>
 */
public final class GhostTeam {

    static final String GHOST_TEAM = "deathechoes_ghost";
    static final String SOLID_TEAM = "deathechoes_solid";

    private final Logger log;
    /** Spieler, die wir selbst ins Ghost-Team gesteckt haben (nur diese werden wieder entfernt). */
    private final Set<UUID> added = new HashSet<>();
    private boolean pushPlayers;

    public GhostTeam(Logger log) {
        this.log = log;
    }

    private static Scoreboard main() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    /** Konfiguration übernehmen (Start und /echoes reload). */
    public void configure(boolean pushPlayers) {
        this.pushPlayers = pushPlayers;
        Team g = main().getTeam(GHOST_TEAM);
        if (g != null) applyGhostOptions(g);
        Team s = main().getTeam(SOLID_TEAM);
        if (s != null) applySolidOptions(s);
    }

    private void applyGhostOptions(Team t) {
        t.setCanSeeFriendlyInvisibles(true);
        t.setOption(Team.Option.COLLISION_RULE,
                pushPlayers ? Team.OptionStatus.ALWAYS : Team.OptionStatus.FOR_OTHER_TEAMS);
    }

    private void applySolidOptions(Team t) {
        t.setOption(Team.Option.COLLISION_RULE, pushPlayers ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER);
    }

    private Team team(boolean ghost) {
        Scoreboard sb = main();
        String name = ghost ? GHOST_TEAM : SOLID_TEAM;
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
            if (ghost) applyGhostOptions(t);
            else applySolidOptions(t);
        }
        return t;
    }

    /** Beim Start: Reste eines Absturzes (Teams stehen im scoreboard.dat) entfernen. */
    public void resetStale() {
        for (String name : new String[] {GHOST_TEAM, SOLID_TEAM}) {
            Team t = main().getTeam(name);
            if (t != null) {
                log.info("Altes Team " + name + " mit " + t.getSize() + " Einträgen entfernt.");
                t.unregister();
            }
        }
        added.clear();
    }

    /**
     * Mannequin ins passende Team. Ohne Ghost-Look und mit {@code push-players: true} braucht es kein Team.
     */
    public void addEntity(Entity e, boolean ghost) {
        if (!ghost && pushPlayers) return;
        team(ghost).addEntity(e);
    }

    public void removeEntity(Entity e) {
        for (String name : new String[] {GHOST_TEAM, SOLID_TEAM}) {
            Team t = main().getTeam(name);
            if (t != null && t.hasEntity(e)) t.removeEntity(e);
        }
    }

    /** Zuschauerliste abgleichen: neue aufnehmen (wenn konfliktfrei), nicht mehr benötigte entfernen. */
    public void sync(Set<Player> viewers) {
        Set<UUID> wanted = new HashSet<>();
        for (Player p : viewers) {
            UUID id = p.getUniqueId();
            if (added.contains(id)) {
                wanted.add(id);
                continue;
            }
            if (p.getScoreboard() != main() || main().getEntryTeam(p.getName()) != null) continue;
            team(true).addPlayer(p);
            added.add(id);
            wanted.add(id);
        }
        for (Iterator<UUID> it = added.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (wanted.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) removePlayer(p);
            it.remove();
        }
    }

    /** Spieler verlässt den Server: aus dem Team nehmen, sonst bleibt er im scoreboard.dat stehen. */
    public void release(Player p) {
        if (added.remove(p.getUniqueId())) removePlayer(p);
    }

    private void removePlayer(Player p) {
        Team t = main().getTeam(GHOST_TEAM);
        if (t != null && t.hasPlayer(p)) t.removePlayer(p);
    }

    /** onDisable: beide Teams komplett entfernen. */
    public void shutdown() {
        for (String name : new String[] {GHOST_TEAM, SOLID_TEAM}) {
            Team t = main().getTeam(name);
            if (t != null) t.unregister();
        }
        added.clear();
    }
}
