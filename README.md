# OneShotOneKill — Fabric 26.3

PvP-Minigame für Minecraft: Ein Treffer genügt. Umschaltbare Arenen, Killstreaks, Kopfgeld, Spielmodi (Klassisch & Waffenspiel), modernes CS:GO-Tab-Scoreboard und **20 Spezial-Items** — entwickelt für **Fabric 26.3**, ohne eine einzige Fremdbibliothek über Fabric API hinaus.

---

## Auf einen Blick

| Komponente | Version / Wert |
| :--- | :--- |
| **Minecraft** | 26.3 |
| **Fabric Loader** | 0.19.5 |
| **Fabric API** | 0.161.0+26.3 |
| **Fabric Loom** | 1.17-SNAPSHOT |
| **Mod-Version** | 2.0.0 |
| **Java** | 25 |
| **Gradle** | 9.7.1 |
| **Abhängigkeiten** | keine über Fabric API hinaus |
| **Mixins** | 40 (14 gemeinsam, 27 nur Client) |
| **Access Widener** | 2 Einträge |
| **Spezial-Items** | 20 einzigartige Ausrüstungsgegenstände |
| **Arenen** | 4 eingebaute Karten mit physischer Randkollision |
| **Spielmodi** | Klassisch (One Shot One Kill) & Waffenspiel (Gun Game) |

Mod-ID `oneshotonekill`, Package `com.oneshotonekill`. Geteilte Source-Sets über
`splitEnvironmentSourceSets()`: `src/main/java` läuft auf Server und Client, `src/client/java`
ausschließlich auf dem Client.

---

## Spielmodi

1. **Klassisch (One Shot One Kill):**
   * Jeder direkte Treffer mit Pfeil oder Spezialwaffe ist tödlich.
   * Killserien (3, 6, 9...) schalten Spezial-Items frei.
   * Zufällig spawnende Item-Boxen in der Arena sorgen für Abwechslung.
   * Ziel: Zeitlimit (z. B. 10 Min.) oder Kill-Limit (z. B. 25 Kills).

2. **Waffenspiel (Gun Game):**
   * Alle Spieler starten mit der ersten Waffenstufe.
   * Jeder erzielte Kill schaltet unmittelbar die nächste Stufe frei.
   * Individuelle Waffen-Tiers mit grafischem Fortschrittsbalken im Tab-Scoreboard.
   * Wer die finale Stufe erfolgreich meistert, beendet die Runde siegreich.

---

## Arenen & Physisches Grenzkollisions-System

Vier eingebaute Karten, im laufenden Betrieb über das Menü umschaltbar:

| Karte | Kampfzone | Decke | Besonderheiten |
| :--- | :--- | :--- | :--- |
| **Standard** | Rechteck, Y 58–64 | Y 69 | Klassische Arena für rasante Nahkämpfe |
| **DustPvP** | Rechteck, Y 70 | offen | Offenes Wüstenareal mit taktischen Deckungen |
| **BO2** | Polygon aus 221 Eckpunkten, Y 63–81 | offen | Taktischer Stadt-Grundriss mit Raster-Maskenprüfung |
| **Tilted Towers** | Mehrstöckig, Y 1–35 & Y 7–35 | Y 35 | Wolkenkratzer-Metropole mit Wandklettern & Mantling |

* **Physische Randkollision:** Auf allen vier Karten verhindert ein physikalisches Begrenzungssystem via Mixin (`EntityArenaBorderMixin`), dass Spieler die Arena verlassen. Bewegungen werden exakt an den Begrenzungspolygonen gestoppt – ohne Rausfallen in die Void und ohne fehlerhaftes Zurückteleportieren.
* **Datapack-Dimensionen mit Void-Generator:** Die eigentlichen Karten liegen als ZIP-Archive im Jar und werden beim Start in die Dimensionsordner ausgepackt. Ein Arena-Reset setzt alle Blöcke sauber zurück.

---

## Die 20 Spezial-Items

Jedes ist ein **eigenes registriertes Item mit individueller Textur oder echtem 3D-Display-Modell** — kein umbenanntes Vanilla-Item.

| Item | Auslöser | Wirkung |
| :--- | :--- | :--- |
| **Radar-Puls** | Rechtsklick | Alle Gegner leuchten 30 s lang durch Wände |
| **Explosiv-Schuss** | Rechtsklick | Nächster Pfeil detoniert im Umkreis von 7 Blöcken |
| **Reflektor-Schild** | Rechtsklick | Fängt den nächsten tödlichen Treffer ab und schützt den Träger |
| **Rauchbombe** | Werfen | Blickdichte Nebelwand; der Werfer wird taktisch versetzt |
| **Frost-Falle** | Platzieren | Unsichtbar für Gegner; friert den ersten Auslöser 7 s bewegungsunfähig ein |
| **Minigun** | Rechtsklick | 8 s Dauerfeuer mit rotierendem 3D-Laufbündel, Mündungsfeuer & Kamerarütteln |
| **Teleport-Granate** | Werfen | Versetzt den Werfer zur Einschlagstelle und stößt Gegner weg |
| **Unsichtbarkeits-Mantel** | Rechtsklick | 15 s vollständige Unsichtbarkeit |
| **Pfeil-Magnetfeld** | Rechtsklick | Lenkt feindliche Pfeile 15 s lang im Flug ab |
| **Kettenblitz** | Rechtsklick | Tödlicher Blitzschlag, der auf 2 nahestehende Gegner überspringt |
| **Tarnkappenbomber** | Rechtsklick | Taktisches Zielmenü, 3D-Nurflügler-Anflug, 10 s Bombenteppich (max. 3 Kills) |
| **Luftangriff** | Rechtsklick | Taktisches Radar mit Zielauswahl, herabstürzende 3D-Nuklearbombe & 4-Sekunden-Atompilz |
| **C4** | Rechtsklick | Kleben, fernzünden (Boden/Luft) und abnehmen — dynamischer Modellwechsel & geteilter Zünder |
| **Railgun** | Halten & Loslassen | Durchschlagender Laserstrahl mit unendlicher Reichweite; bricht beim Wegstecken sauber ab |
| **Singularität** | Werfen | 5 s Gravitations-Sog im Umkreis von 10 mit sichtbarem 3D-Gravitationskern |
| **Gleitflug** | Rechtsklick | 8 s Dauerschub in Blickrichtung: waagerecht gleiten, steil aufsteigen |
| **Geschützturm** | Platzieren | 20 s Dauerfeuer mit rotierendem 3D-Kopf; drei Treffer töten |
| **Zeitverzerrer** | Rechtsklick | Verlangsamt den gesamten Zeitfluss, Spielticks, Sounds & Animationen für 7 s (Chrono-Shader) |
| **Grappling Hook** | Rechtsklick | 10 Schüsse; sichtbarer Saughaken und Seil ziehen den Spieler rasant zum Trefferpunkt |
| **Boogie-Bomb** | Werfen | Aufschlaggranate; zwingt alle Spieler im 5m-Radius für 15 s in einen synchronen Disco-Tanz |

### Besondere Item-Mechaniken:
* **Zerstörung & Wiederaufbau:** Luftangriff und Tarnkappenbomber reißen echte Krater in die Map. Über `shared/ArenaDemolition.java` wird der Ursprungszustand jedes Blocks gespeichert und nach kurzer Zeit tickweise automatisch restauriert.
* **Boogie-Bomb & Zeitverzerrer-Synergie:**
  * Getroffene Spieler werden in die 3D-Third-Person-Perspektive gezwungen und führen eine synchrone Tanzchoreografie (Arm- & Beinschwünge, Rückwärtssalto, 360°-Spins) zu 128-BPM-Discomusik aus.
  * Eine schwebende 3D-Discokugel rotiert über dem Kopf und wirft volumetrische Scheinwerferkegel auf den Boden.
  * Beim Einschlag erfolgt ein kraftvoller Bass-Drop-Kamerazoom (1.35x), gefolgt von rhythmischen Subwoofer-Kamera-Punches auf jedem Kickdrum-Schlag.
  * Auf dem Bildschirm pulsiert eine beat-synchrone Neon-Vignette mit Ambient-Stroboskop-Flashes und funkelnden Diamantsternen/Musiknoten.
  * **Vollständige Zeitverzerrer-Integration:** Wird während des Tanzes die Zeit verzerrt, wird die Discomusik in Echtzeit tiefgepitcht, die Tanzschritte und die Discokugel verlangsamen sich synchron auf 0,55x, und das Overlay erhält eine violette Chrono-Farbverschiebung samt Interferenz-Ripples.

---

## CS:GO Tab-Scoreboard

Das alte Vanilla-Scoreboard wurde vollständig durch ein modernes, halbtransparentes Overlay nach Vorbild moderner Taktik-Shooter ersetzt:
* **Aktivierung:** Halten der Taste `TAB`.
* **Flüssige Animationen:** Sanftes, synchrones Ein- und Ausblenden über Alpha-Interpolation (kein Nachblitzen von Schriften).
* **Umfassende Match-Daten:**
  * Live-Spielerliste mit Rang, Team/Spielername, Ping, Kills, Toden, K/D-Rate und Killserien.
  * Im **Waffenspiel-Modus**: Automatische Anzeige der aktuellen Waffenstufe und Fortschrittsbalken zum nächsten Tier.
  * Eigener Spieler wird mit markanter Umrandung und Cyan-Akzent hervorgehoben.
* **Overlay-Priorität:** Rendert sauber über dem HUD, wird jedoch bei geöffneten GUIs (wie dem Admin-Menü) deaktiviert.

---

## Klettern auf Tilted Towers

Auf der Karte **Tilted Towers** steht eine intuitive Parkour-Mechanik zur Verfügung:
* Zur Wand schauen und **Vorwärts + Springen** halten, um die Wand zu greifen.
* Solange Springen gehalten wird:
  * **Vorwärts**: Klettert nach oben.
  * **Rückwärts**: Klettert nach unten.
  * **Links / Rechts**: Klettert seitlich an der Fassade entlang.
  * Keine Richtung: Festhalten an der Wand.
* **Automatisches Mantling:** Erreicht der Spieler eine Mauerkante, Dachkante oder einen Fenstersims, übersteigt er diesen automatisch und steht sicher auf der Fläche.
* **Abbrechen:** Springen loslassen oder Schleichen (Shift).

---

## Match-Start & Countdown

* **Taktischer Start-Countdown:** Eigene HUD-Ebene mit animierten Segmentziffern, rotierenden Fokusringen und ansteigender Tonhöhe.
* **Eingabesicherung:** Während des Countdowns werden Bewegungseingaben (`KeyboardInputMixin`) und Maussichten (`MouseHandlerMixin`) verworfen, während der Server die Positionen arretiert.

---

## Spezial-Items verdienen

Im Verwaltungsmenü (`C`) einzeln einstellbar:
* **Killserie:** Jede dritte Eliminierung in Folge ohne eigenen Tod belohnt den Spieler mit einem gewichteten Spezial-Item (bei 3, 6, 9...).
* **Boden-Boxen:** Schwebende Fragezeichen-Würfel spawnen alle 15 Sekunden an zufälligen, sicheren Arena-Positionen (maximal 6 gleichzeitig).

---

## Bedienung & Tastenbelegung

| Taste | Funktion |
| :--- | :--- |
| `TAB` (halten) | CS:GO-Style Tab-Scoreboard (Match-Statistiken, Ränge & K/D) |
| `C` | Verwaltungsmenü (Arenen, Match-Ablauf, Spielmodi, Itemgewichtungen) |
| `X` | Privates Testmenü zum direkten Ausgeben von Items (Admin-Status erforderlich) |
| `R` | C4-Fernzünder aktivieren / detonisieren |
| `Vorwärts + Springen` | Wandklettern & Mantling an Gebäudewänden auf Tilted Towers |

---

## Portierung & Architektur (Fabric 26.3)

Das Minigame wurde ohne externe Fremdbibliotheken auf Basis von **Fabric 26.3** und **Java 25** aufgebaut:

* **Fabric API:**
  * Lebenszyklus & Takt: `ServerLifecycleEvents`, `ServerTickEvents`, `ClientTickEvents`.
  * Netzwerk: `PayloadTypeRegistry` mit strikt typisierten Custom Packets (`ServerPlayNetworking`, `ClientPlayNetworking`).
  * Interaktion: `AttackEntityCallback`, `UseItemCallback`, Interaktions-Gates.
  * HUD & Rendering: `HudElementRegistry`, `LevelExtractionEvents`, `LevelRenderEvents` mit `RenderStateDataKey`.
* **40 Mixins:**
  * 14 gemeinsame Mixins (`src/main/resources/oneshotonekill.mixins.json`): Schadensprüfungen, Wurfschutz, physikalische Arenagrenzkollisionen, Kletter-Logik, Projektil-Handling.
  * 27 Client-Mixins (`src/client/resources/oneshotonekill.client.mixins.json`): FOV-Steuerung, dynamischer Kameraabstand & -shake, Zeitverzerrer-PostPass-Shader, Lichtberechnung, OpenAL-Tonhöhenskalierung und 3D-Modell-Rendering.
* **Access Widener:**
  * Gezielte Freigabe für Minecraft-HUD-Overlays (`extractPortalOverlay`, `extractConfusionOverlay`).

---

## Generierung von Texturen & 3D-Modellen

Alle Texturen und 3D-Item-Modelle werden prozedural über Python-Generatoren in `tools/` erzeugt:

```bash
# Texturen generieren:
python tools/generate_item_textures.py
python tools/generate_item_assets.py

# 3D-Modelle generieren:
python tools/generate_minigun_3d.py
python tools/generate_stealth_bomber_3d.py
python tools/generate_field_gear_3d.py
python tools/generate_railgun_3d.py
python tools/generate_combat_abilities_3d.py
python tools/generate_airstrike_nuke_3d.py
```

---

## Bauen & Deployment

```powershell
# Vollständiger Build und automatisches Deployment in Server- und Client-Verzeichnis:
powershell -ExecutionPolicy Bypass -File .\build.ps1

# Oder reiner Gradle-Build:
.\gradlew.bat build
```

Das fertige Mod-Jar wird in `build/libs/` erzeugt und von `build.ps1` direkt in die konfigurierten Zielordner (Server und Client) kopiert.

---

## Projektregeln

Die Entwicklungsrichtlinien und Architekturvorgaben sind verbindlich in [AGENTS.md](AGENTS.md) dokumentiert:
* 0 Compiler-Fehler, 0 Warnungen in allen Quelldateien.
* Einsatz der internen IntelliJ MCP Tools zur Indexierung, Code-Navigation und Verifikation.

---

## Lizenz

MIT
