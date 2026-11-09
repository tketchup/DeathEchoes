# DeathEchoes

Paper-Plugin für Minecraft 26.3 (Java 25, Gradle Kotlin DSL). Geister-Replays der letzten Sekunden vor dem Tod, gekoppelt an das GraveBox-Grab. Überblick, Befehle und Designentscheidungen stehen in der README.md.

- Bauen und testen: `./gradlew build`. Unit-Tests liegen unter `src/test`.
- Die Jar heißt immer `build/libs/DeathEchoes.jar`, ohne Versionsnummer.
- Pakete: `record` (Ringpuffer/Recorder), `replay` (Mannequin-Wiedergabe, Effekte), `anchor` (Grab-Erkennung, Chunk-Events), `echo` (Modell, Register), `storage` (JSON, IO-Thread), `command`, `config`.

## Ablauf nach jeder Änderung

Testserver: `~/Documents/code/testserver` (Start/Stop/RCON siehe `~/Documents/code/testserver/CLAUDE.md`).

1. Server stoppen (`tmux send-keys -t mc 'stop' Enter`, warten, bis die tmux-Sitzung endet).
2. `./gradlew deploy` – baut und kopiert `DeathEchoes.jar` nach `~/Documents/code/testserver/plugins`.
3. Server starten und warten, bis `Done (` in `logs/latest.log` steht.
4. Log auf Fehler und `DeathEchoes`-Warnungen prüfen.
5. Per RCON testen, z. B. `mcrcon -c -H localhost -P 25575 -p test 'forceload add 8 8' 'echoes debug spawn 8 250 8 world explosion'`, dann `echoes debug forceplay <id>` und `echoes debug stats`. Am Ende `echoes clear`, Testblöcke entfernen und `forceload remove 8 8`.

## Wichtig

- Der Testserver wird von mehreren Plugins gemeinsam genutzt. In `plugins/` nur `DeathEchoes.jar` und `plugins/DeathEchoes/` anfassen (GraveBox-2.1.jar wurde für die Tests ergänzt).
- Auf dem Testserver überschreibt ein anderes Plugin `/item`; in RCON-Tests `minecraft:item` verwenden.
- Niemals `/reload` benutzen.
- Die Shell ist fish: keine bash-Heredocs oder Brace-Expansion direkt in Befehlen.
- GraveBox hat keine API: niemals gegen dessen Klassen bauen, nur den Weltzustand lesen.
