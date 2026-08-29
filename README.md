# OneShotOneKill — Fabric 26.2

PvP-Minigame für Minecraft: Ein Treffer genügt. Umschaltbare Arenen, Killstreaks, Kopfgeld und
17 Spezial-Items — portiert von der NeoForge-Fassung auf **Fabric 26.2**, ohne eine einzige
Fremdbibliothek über Fabric API hinaus.

---

## Auf einen Blick

| Komponente | Version / Wert |
| :--- | :--- |
| **Minecraft** | 26.2 |
| **Fabric Loader** | 0.19.4 |
| **Fabric API** | 0.158.0+26.2 |
| **Fabric Loom** | 1.17.20 (`1.17-SNAPSHOT`) |
| **Mod-Version** | 1.0.0 |
| **Java** | 25 |
| **Gradle** | 9.5.1 |
| **Abhängigkeiten** | keine über Fabric API hinaus |
| **Mixins** | 22 (11 gemeinsam, 11 nur Client) |
| **Access Widener** | 2 Einträge |

Mod-ID `oneshotonekill`, Package `com.oneshotonekill`. Geteilte Source-Sets über
`splitEnvironmentSourceSets()`: `src/main/java` läuft auf Server und Client, `src/client/java`
ausschließlich auf dem Client.

---

## Arenen

Drei eingebaute Karten, im laufenden Betrieb umschaltbar:

| Karte | Kampfzone | Decke |
| :--- | :--- | :--- |
| **Standard** | Rechteck, Y 58–64 | Y 69 |
| **DustPvP** | Rechteck, Y 70 | offen |
| **BO2** | Polygon aus 221 Eckpunkten, Y 63–81 | offen |

Die Arenen sind **Datapack-Dimensionen** mit Void-Generator; die eigentlichen Karten liegen als
Archive im Jar und werden beim Start in die Dimensionsordner ausgepackt. Ein Reset holt die
Spieler heraus, lässt die Chunks leerlaufen und packt neu aus.

BO2 ist als Umriss vermessen statt als Quader — von der umschließenden Box gehören nur rund zwei
Drittel wirklich zur Karte. Der Umriss wird beim Laden einmal in eine Maske gerastert, danach ist
die Bereichsprüfung ein Feldzugriff statt eines Punkt-in-Polygon-Tests über hundert Ecken.

---

## Die 17 Spezial-Items

Jedes ist ein **eigenes registriertes Item mit eigener Textur oder individuellem 3D-Modell** — kein umbenanntes Vanilla-Item.

| Item | Auslöser | Wirkung |
| :--- | :--- | :--- |
| **Radar-Puls** | Rechtsklick | Alle Gegner leuchten 30 s |
| **Explosiv-Schuss** | Rechtsklick | Nächster Pfeil sprengt im Umkreis von 7 |
| **Reflektor-Schild** | Rechtsklick | Fängt den nächsten tödlichen Treffer ab |
| **Rauchbombe** | Werfen | Nebelwand, Werfer wird versetzt |
| **Frost-Falle** | Platzieren | Unsichtbar für Gegner; friert den ersten Spieler 7 s fest |
| **Minigun** | Rechtsklick | 8 s Dauerfeuer mit rotierendem 3D-Laufbündel |
| **Teleport-Granate** | Werfen | Versetzt und stößt Gegner weg |
| **Unsichtbarkeits-Mantel** | Rechtsklick | 15 s unsichtbar |
| **Pfeil-Magnetfeld** | Rechtsklick | Lenkt Pfeile 15 s ab |
| **Kettenblitz** | Rechtsklick | Blitz springt auf 2 Gegner über |
| **Tarnkappenbomber** | Rechtsklick | Zielmenü, 3D-Nurflügler-Anflug, dann 13 s Bombardement |
| **Luftangriff** | Rechtsklick | Taktisches Radar mit Zielauswahl, 3D-Nuklearbombe und Atompilz |
| **C4** | Rechtsklick | Kleben, zünden (Boden/Luft) und abnehmen — dynamischer Modellwechsel & geteilter Zünder |
| **Railgun** | Halten und loslassen | Voll geladen ein durchschlagender Strahl mit unendlicher Reichweite |
| **Singularität** | Werfen | 5 s Sog im Umkreis von 10, sichtbarer 3D-Gravitationskern |
| **Gleitflug** | Rechtsklick | 8 s Dauerschub in Blickrichtung: waagerecht schweben, steil steigen |
| **Geschützturm** | Platzieren | 20 s Dauerfeuer mit 3D-Drehkopf, drei Treffer töten |

Zwei Items töten bewusst **nicht** mit einem Schlag: Geschützturm und Bomber zielen automatisch
beziehungsweise ununterbrochen — mit Sofort-Kill wäre jede von ihnen eingesehene Deckung
unbetretbar. Beide sammeln stattdessen Treffer auf ein Konto, das nach acht Sekunden verfällt.

**Die Karte bleibt unberührt.** Alles Abgestellte ist eine Display-Entity, kein gesetzter Block.
Explosionen laufen über die eigene Eliminierungs-Buchführung statt über Vanilla-Explosionen und
können deshalb grundsätzlich keinen Schaden an der Karte anrichten.

**Der Luftangriff endet in einem Atompilz.** Er läuft über vier Sekunden ab statt in einem
einzigen Partikelstoß: Feuerball am Boden, der von Weißglut über Gelb nach Dunkelrot abkühlt,
ein Stiel, der daraus hochwächst, ein Hut, der oben aufsetzt und mit hängender Krempe aufreißt,
und eine Druckwelle über den Boden. Gezeichnet mit `DustParticleOptions`, weil sich nur dort die
Farbe frei wählen lässt.

Vor dem Einschlag fällt keine Gruppe aus Vanilla-TNT mehr herab, sondern eine einzelne große
3D-Nuklearbombe: fast vier Blöcke lang, mit bauchiger Panzerhülle, Warnring, Sicherungskästen
und breitem Leitwerk.

Zwei Items dürfen die Karte bewusst dynamisch verändern: Luftangriff und Tarnkappenbomber reißen
echte Krater. Beide laufen über dieselbe Buchführung in `shared/ArenaDemolition.java`, die zu
jedem gesprengten Block seinen Ursprungszustand merkt und ihn nach einer Wartezeit tickweise
wieder einsetzt.

---

## C4-System & Inventar-Handhabung

* **Dynamischer 3D-Modellwechsel:**
  * Unplatzierte C4-Ladungen erscheinen in Hotbar und Hand als Sprengstoff-Riegel (`c4_charge`).
  * Nach dem Platzieren verwandelt sich der Gegenstandsstapel in den aktiven Funkzünder (`c4`).
  * Weitere erhaltene C4s bleiben unplatzierte Riegel und nutzen beim Platzieren den bestehenden Zünder mit.
* **Intelligente Klick-Erkennung:**
  * Rechtsklick mit dem Zünder (in die Luft, auf Blöcke oder den Boden) löst die Detonation aller scharfen Ladungen aus.
  * Rechtsklick mit leerer Hand, C4 oder Zünder auf eine platzierte C4 nimmt diese sicher ab, ohne den Bogen in der Zweithand aufzuspannen.
* **Inventar-Schutz:**
  * Spezial-Items können innerhalb des eigenen Inventars und der Hotbar frei verschoben und umsortiert werden.
  * Feste Match-Ausrüstung (Dolch, Bogen, Pfeil) bleibt fest an ihren vergebenen Slot gebunden.
  * Kein Droppen: Weder per Q-Taste noch durch Herausziehen aus dem Inventarfenster können Spezial-Items weggeworfen werden.

---

## Match-Start & Countdown

Der Countdown ist eine eigene HUD-Ebene, kein Vanilla-Titel: ein Ring, der die drei Sekunden
abbaut, ein zweiter, der je Sekunde einmal herumläuft, vier Ecken, die nach innen wandern, und
die Zahl als Segmentanzeige, die bei jedem Sekundenwechsel kurz aufspringt. Die Farbe geht von
kühl über gold nach rot, die Tonhöhe des Schlags steigt mit.

Während des Countdowns steht man wirklich still: Der Client verwirft seine Bewegungseingabe in
`KeyboardInputMixin`, die Maus sperrt `MouseHandlerMixin`, und der Server hält jeden zusätzlich
auf seinem Startpunkt.

---

## Chat-Meldungen & Kill-Feed

* `[OSOK] ▸ Name hat OneShotOneKill betreten · 3 online`
* `[OSOK] ⚔ Täter hat Opfer mit Railgun ausgeschaltet · Serie 3`
* `[OSOK] 🛡 Opfer hat Luftangriff von Täter abgewehrt`
* `[OSOK] ⚡ Name hält eine Serie von 3 und erhält Railgun`

---

## Spezial-Items verdienen

Zwei Wege, im Verwaltungsmenü einzeln abschaltbar:

- **Killserie** — jede dritte Eliminierung ohne eigenen Tod bringt ein gewichtet gezogenes Item (bei 3, 6, 9...).
- **Boden-Boxen** — schwebende Fragezeichen-Würfel, alle 15 Sekunden eine, bis zu sechs gleichzeitig.

---

## Bedienung

| Taste | Wirkung |
| :--- | :--- |
| `C` | Verwaltungsmenü (Arenen, Match-Ablauf, Itemgewichtungen) |
| `X` | Privates Testmenü zum Ausgeben von Items (erfordert Admin-Status) |
| `R` | C4-Zünder |

---

## Portierung von NeoForge auf Fabric

Beide Fassungen laufen auf Minecraft 26.2, der gesamte Vanilla-Code blieb deshalb unverändert.
Ausgetauscht wurde nur die Loader-Schicht mit Fabric API, Access Widener und Mixins gemäß [AGENTS.md](AGENTS.md).

**Fabric API** — Registrierungen (`Registry.register` statt `DeferredRegister`),
Netzwerk (`PayloadTypeRegistry` mit `ServerPlayNetworking`/`ClientPlayNetworking` statt
`PacketDistributor`), Lebenszyklus und Takt (`ServerLifecycleEvents`, `ServerTickEvents`),
Spielerereignisse (`ServerPlayerEvents`), Schaden und Tod (`ServerLivingEntityEvents`),
Interaktion (`AttackEntityCallback`, `UseItemCallback` und Verwandte), Welteintritt
(`ServerEntityEvents.ALLOW_LOAD`), HUD (`HudElementRegistry`), Tastenbelegung
(`KeyMappingHelper`), Weltrendering (`LevelExtractionEvents`, `LevelRenderEvents` samt
`RenderStateDataKey`), Tooltips und Chatfilter.

**Vanilla-Erweiterung & Access Widener** — Die drei eigenen Modellbausteine hängen sich direkt in
Vanillas offene `LateBoundIdMapper`-Tabellen (`ItemModels`, `ConditionalItemModelProperties`,
`SpecialModelRenderers`), und die beiden Bildschirmeffekte des Match-Starts nutzen zwei
Access-Widener-Zeilen für `Hud#extractPortalOverlay` und `#extractConfusionOverlay`. Die Setter
von `Display` sind bereits durch `fabric-transitive-access-wideners-v1` geöffnet.

**Mixins** — 22 Stück für gezielte Eingriffe in Vanilla-Logik, Rendering, Positions-Sync und Abläufe. Beispiele: die
Unverwundbarkeits-Vorprüfung vor `hurtServer`, der Wurfschutz in `ServerPlayer#drop`, die
Tabellenlisten-Zeile, Spannen und Lösen des Bogens, Sichtfeld, Kameraabstand, Kamerawackeln,
Handanimation, Nebel und harter Teleport-Snap bei Remote-Spielern.

Injection-Points, die sich aus dem dekompilierten Quelltext nicht sicher ablesen lassen, sind
mit `javap` am gemappten Jar belegt — so kam etwa heraus, dass der Kameraabstand nicht in
`Camera#setup`, sondern in `Camera#alignWithEntity` gesetzt wird.

---

## API-Quellen unter `APIS/`

`tools/update_api_sources.py` entpackt die Quellen, gegen die entwickelt wird, nach `APIS/`.
Das Skript enthält keine festen Versionen oder Modullisten — es fragt den Gradle-Wrapper des
Projekts und leitet alles daraus ab. Der Ordner ist generiert und wird nicht eingecheckt.

```powershell
python tools/update_api_sources.py
```

| Ordner | Inhalt |
| :--- | :--- |
| `APIS/minecraft/` | Dekompiliertes Minecraft, `common` und `clientOnly` zusammengeführt |
| `APIS/fabric-api/` | Quellen aller Fabric-API-Module |
| `APIS/fabric-loader/` | Quellen des Fabric Loaders |
| `APIS/mixin/` | Sponge Mixin und MixinExtras |

`tools/SOURCES.json` hält fest, aus welchen JARs mit welcher Version und Prüfsumme der Stand
gebaut wurde. Der erste Lauf dekompiliert Minecraft und dauert einige Minuten, danach ist der
Aufruf schnell. Mit `--force` wird neu entpackt, mit `--refresh --decompile` nach einem
Versionswechsel.

---

## Generierung von Texturen & 3D-Modellen

Alle Texturen und 3D-Modelle werden prozedural über Python-Skripte in `tools/` erzeugt:

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
# Build und automatisches Deployment in Server- und Client-Verzeichnis:
powershell -ExecutionPolicy Bypass -File .\build.ps1

# Oder reiner Gradle-Build:
.\gradlew.bat build
```

Das fertige Jar landet in `build/libs/`. Die Zielordner für das Deployment stehen in
`deploy.properties` und lassen sich mit `.\build.ps1 -Reconfigure` neu setzen. `.\build.ps1
-Clean` baut sauber, `.\build.ps1 -StopDaemons` beendet hängende Gradle-Daemons.

Ein grüner Build ersetzt keinen Laufzeittest: Weil `defaultRequire` auf 1 steht, fällt ein nicht
greifender Injection-Point erst beim Start auf. Nach Änderungen an Rendering, HUD, Netzwerk oder
Mixins zusätzlich `.\gradlew.bat runClient` beziehungsweise `runServer` starten und das Log
prüfen.

---

## Projektregeln

Die verbindlichen Regeln für die Arbeit an diesem Projekt stehen in [AGENTS.md](AGENTS.md):
Einsatz von Fabric-API, Access Widener und Mixins, Rechercheablauf über `APIS/` mit `rg` und `ast-grep`,
und wann für ein Mixin zusätzlich der Bytecode zu prüfen ist.

---

## Lizenz

MIT
