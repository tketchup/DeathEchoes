# DeathEchoes

Paper-Plugin für Minecraft **26.3** (Java 25). Wenn ein Spieler stirbt, bleibt ein **Echo** zurück: ein
Geister-Replay der letzten Sekunden vor dem Tod. Es läuft in einer Schleife am Todesort und lebt genau so
lange wie das Grab (z. B. die Kiste von GraveBox).

## Installation

1. `./gradlew build` → `build/libs/DeathEchoes.jar`
2. Jar in den `plugins/`-Ordner legen, Server neu starten (kein `/reload`).
3. Optional, aber vorgesehen: **GraveBox 2.1** daneben installieren. DeathEchoes braucht keine API von
   GraveBox, es liest nur den Weltzustand (`softdepend: [GraveBox]`).

Voraussetzung: Paper 26.3 (Mannequin-Entity, Brigadier-Lifecycle-API), Java 25.

## Ablauf

1. **Aufnahme:** Für jeden Online-Spieler hält ein Ringpuffer die letzten `recording.duration-seconds`
   (Standard 8 s), gemessen alle `sample-interval-ticks` (Standard 2): Position, Blickrichtung, Pose
   (stehen, schleichen, schwimmen, gleiten, schlafen), Sprint, Item in der Haupthand, Schlag, Treffer.
   Nur im Speicher, primitive Spalten-Arrays, keine Allokation pro Tick.
2. **Tod:** Die Aufnahme wird eingefroren. `anchor.search-delay-ticks` später wird im Umkreis
   (`anchor.search-radius`) ein **neu entstandener** Container gesucht. Das ist der Anker (Doppelkiste: beide
   Hälften). Ohne Grab entsteht kein Echo (außer `echo-without-grave: true`).
3. **Wiedergabe:** Nur wenn ein Spieler mit `deathechoes.see` im Radius ist. Am Ende ein Effekt passend zur
   Todesursache (Explosion, Feuer, Lava, Ertrinken, Sturz, Leere, ...), kurze Pause, dann von vorn.
4. **Ende:** Sobald der Anker weg ist (GraveBox hat die leere Kiste entfernt, abgebaut, explodiert, ...),
   verschwindet das Echo mit Abschiedseffekt und die Datei wird gelöscht.

## Befehle

| Befehl | Recht | Beschreibung |
|---|---|---|
| `/echoes list` | `deathechoes.see` | Eigene Echoes (Admins und Konsole: alle) |
| `/echoes list <spieler>` | `deathechoes.admin` | Echoes eines Spielers |
| `/echoes remove <id>` | `deathechoes.admin` | Echo entfernen (die Kiste bleibt) |
| `/echoes clear [spieler]` | `deathechoes.admin` | Alle (bzw. alle eines Spielers) entfernen |
| `/echoes reload` | `deathechoes.admin` | config.yml neu laden |
| `/echoes test [ursache]` | `deathechoes.admin` | Eigene aktuelle Aufnahme sofort als Echo abspielen (kein Tod, kein Anker, nicht gespeichert, `test-lifetime-seconds`) |
| `/echoes debug recorder [spieler]` | `deathechoes.admin` | Ringpuffer als Text/Zähler |
| `/echoes debug stats` | `deathechoes.admin` | Echoes, aktive Wiedergaben, Echo-Entities in der Welt |
| `/echoes debug spawn <x> <y> <z> [welt] [ursache] [name]` | `deathechoes.admin` | Synthetisches Echo (auch von der Konsole); ein Container im Suchradius wird Anker, `name` z. B. zum Testen von `skin-by-name` |
| `/echoes debug forceplay <id>` | `deathechoes.admin` | Echo auch ohne Zuschauer abspielen (Konsolentests) |
| `/echoes debug check` | `deathechoes.admin` | Alle Anker sofort prüfen |

Ursachen: `explosion, fire, lava, drowning, fall, void, projectile, melee, magic, freeze, lightning, sonic, suffocation, starvation, generic`.

Rechte: `deathechoes.see` (Standard: alle) – Echoes sehen und hören. `deathechoes.admin` (Standard: OP).

## Konfiguration

Alles in `plugins/DeathEchoes/config.yml`, deutsch kommentiert: Aufnahmedauer, Sample-Rate, Sichtradius,
Limits (gesamt, pro Chunk, pro Spieler), Lebensdauer, Aussehen (`GHOST`/`SKIN`/`OUTLINE`), Glowing, `skin-by-name`, `push-players`, Partikel/Sounds,
Welten-Whitelist/Blacklist, Container-Materialien, `echo-without-grave`, `remove-when-empty`, Nachrichten
mit `&`-Farbcodes.

## Entscheidungen

- **Darstellung: Mannequin** (seit MC 1.21.9 / Paper-API `org.bukkit.entity.Mannequin`). Ein echtes
  Spielermodell mit Skin, Posen, Ausrüstung, Schlag- und Treffer-Animation – ohne NMS und ohne Packet-Tricks.
  Display-Entities mit Spielerkopf wirken dagegen wie ein schwebender Kopf. Die Entity ist nicht persistent,
  unverwundbar, ohne Schwerkraft/KI/Kollision und `visibleByDefault=false`: nur Zuschauer mit
  `deathechoes.see` bekommen sie gezeigt. Dazu Seelen-Partikel und gedämpfte Sounds.
- **Transparenz (`appearance: GHOST`, Standard):** Minecraft kennt keine einstellbare Deckkraft für Entities.
  Einzige Möglichkeit ohne Resourcepack: Unsichtbare Mitglieder des eigenen Scoreboard-Teams zeichnet der
  Client durchscheinend, wenn das Team „see friendly invisibles“ hat. Die Deckkraft (ca. 15 %) gibt der
  Client fest vor; Rüstung und Items bleiben voll sichtbar. Das Team `deathechoes_ghost` auf dem
  Haupt-Scoreboard enthält die Mannequins und nur die Spieler, die gerade ein Echo sehen. Spieler, die schon
  in einem anderen Team sind oder ein eigenes Scoreboard haben, werden nie umgehängt; sie sehen den Umriss
  wie bei `OUTLINE`. Das Team setzt weder Farbe noch Präfix (Namen in Chat/Tab bleiben gleich) und wird beim
  Stoppen entfernt. Nebeneffekt: Zwei Spieler, die gleichzeitig ein Echo sehen, sehen sich gegenseitig
  durchscheinend, falls einer einen Unsichtbarkeitstrank nutzt. `SKIN` zeigt das Modell voll sichtbar,
  `OUTLINE` nur den leuchtenden Umriss.
- **Skin:** wird beim Tod aus dem Spielerprofil (`textures`) gespeichert. Auf Offline-Mode-Servern gibt es keine
  Textur, dann zeigt das Mannequin den Standard-Skin. Mit `skin-by-name: true` holt das Plugin die signierten
  Skin-Daten des gleichnamigen Mojang-Accounts asynchron von der Mojang-API (`api.mojang.com`,
  `sessionserver.mojang.com`), speichert sie im Echo und erzeugt die Gestalt neu. Standardmäßig aus, weil im
  Offline-Mode jeder jeden Namen benutzen kann.
- **Kein Wegschieben (`push-players: false`):** Spieler schiebt ihr eigener Client von Entities weg; das
  serverseitige `setCollidable(false)` kennt er nicht, die Kollisionsregel eines Scoreboard-Teams aber schon.
  SKIN/OUTLINE: Die Mannequins stehen allein im Team `deathechoes_solid` mit Regel NEVER (keine Nebenwirkung
  auf Spieler). GHOST: Regel „nur andere Teams schieben“ im Ghost-Team, damit Zuschauer und Echo sich nicht
  schieben, alles andere aber normal kollidiert. Zuschauer, die wegen eines fremden Teams nicht ins Ghost-Team
  kommen, können vom Echo noch geschoben werden.
- **„Neu entstanden“:** GraveBox setzt die Kiste synchron im `PlayerDeathEvent` (Priorität HIGHEST). Um eine
  schon vorher dort stehende Kiste nicht fälschlich als Grab zu nehmen, merkt sich ein **rein lesender**
  Listener auf `LOWEST` die Container im Umkreis. Das ist die einzige Abweichung von „alles auf MONITOR“; er
  verändert nichts am Event. Alle anderen Listener laufen auf `MONITOR`, `ignoreCancelled = true` (wo das
  Event abbrechbar ist) und fassen Drops, Inventar und Todesnachricht nie an.
- **`remove-when-empty`:** GraveBox 2.1 arbeitet mit virtuellen Inventaren; die Items liegen in einer Datei,
  die Kiste selbst ist leer. Würde man den echten Kisteninhalt prüfen, verschwände jedes GraveBox-Echo sofort.
  Daher greift die Option nur, wenn der Container beim Verankern echten Inhalt hatte (andere Grab-Plugins).
  Bei GraveBox endet das Echo, wenn GraveBox die geleerte Kiste entfernt.
- **Explosionen:** Ob die Kiste eine Explosion überlebt, entscheidet das Grab-Plugin (GraveBox:
  `explosion-protection: true` nimmt sie aus der Blockliste). DeathEchoes prüft einen Tick nach jeder
  Explosion, die einen Anker enthielt, was übrig ist: Kiste da → Echo bleibt, Kiste weg → Echo verschwindet.
- **Prüfungen:** Event-Prüfungen 1-2 Ticks nach Abbau, Explosion und Inventar-Schließen; zusätzlich ein
  periodischer Check (`anchor.check-interval-ticks`), weil GraveBox die Kiste per `setType(AIR)` ohne Event
  entfernt. Geprüft wird nur in geladenen Chunks, es wird nie ein Chunk geladen.
- **Persistenz:** eine JSON-Datei pro Echo in `plugins/DeathEchoes/echoes/` (lesbar, ca. 3-5 KB pro Echo),
  geschrieben und gelesen auf einem eigenen IO-Thread, atomar per temp-Datei. Beim Chunk-Load wird der Anker
  geprüft; fehlt er, wird das Echo still verworfen und die Datei gelöscht.
- **Limit pro Spieler** (`max-per-player`): beim Überschreiten wird das älteste Echo entfernt, die Kiste
  bleibt unberührt.

## Entwicklung

- `./gradlew build` baut und führt die Unit-Tests aus (`src/test`).
- `./gradlew deploy` kopiert die Jar als `DeathEchoes.jar` nach `~/Documents/code/testserver/plugins`.
