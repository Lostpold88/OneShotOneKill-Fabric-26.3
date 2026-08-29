# Projektregeln

## Kurzfassung

- **Fabric-API, Access Widener und Mixins** sind vollwertige Werkzeuge und dürfen jederzeit frei und gezielt nach Zweckmäßigkeit genutzt werden.
- Externe Minecraft-, Fabric-API-, Fabric-Loader- und Mixin-Quellen liegen entpackt unter `APIS/`.
- `APIS/` wird zur Recherche zuerst mit `rg` oder `ast-grep` eingegrenzt; anschließend
  werden nur die relevanten Trefferdateien angesehen.
- `APIS/` ist generiert, schreibgeschützt zu behandeln und wird nicht eingecheckt.
- Zielplattform: **Java 25, Minecraft 26.2, Fabric Loader 0.19.4, Fabric API 0.158.0+26.2,
  Fabric Loom 1.17.20, Gradle 9.5.1**. Alle Versionen stehen in `gradle.properties`.
- Mod-ID `oneshotonekill`, Package `com.oneshotonekill`.
- Geteilte Source-Sets: `src/main/java` läuft auf Server und Client,
  `src/client/java` ausschließlich auf dem Client.
- Die Mod hat keine zusätzlichen Laufzeitbibliotheken.
- Build und Deployment laufen über `.\build.ps1`.

## API-Sources unter APIS

`tools/update_api_sources.py` ist ein universelles Hilfsskript für Fabric-Loom-Projekte.
Es enthält keine festen Minecraft-, Fabric-, Loom- oder Projektversionen. Stattdessen findet
es die Projektwurzel, verwendet den Gradle-Wrapper des jeweiligen Projekts, lässt fehlende
Minecraft-Quellen von Looms Dekompilierer (`genSources`) erzeugen und löst die
`sources`-Artefakte der vom Build deklarierten Abhängigkeiten auf. Aggregat-Artefakte wie
Fabric API werden dabei über ihre Modul-Artefakte derselben Gruppe aufgeklappt. Zusätzlich
werden Bibliotheken vom Kompilierklassenpfad mitgenommen, deren Gruppe oder Name `mixin`
enthält; so landet die Mixin-Implementierung des Loaders im Ergebnis, ohne dass eine feste
Koordinate im Skript steht.

Standardaufruf aus der Projektwurzel:

```powershell
python tools/update_api_sources.py
```

Der erste Durchlauf fragt Gradle nur nach den vorhandenen Artefakten. Fehlen die
Minecraft-Quellen, wird automatisch ein zweiter Durchlauf mit `genSources` gestartet; die
Dekompilierung dauert einige Minuten. Danach ist der Aufruf schnell.

Nach einem Versionswechsel oder wenn Gradle seine Abhängigkeiten ausdrücklich neu prüfen soll:

```powershell
python tools/update_api_sources.py --refresh --decompile
```

Das Ergebnis wird atomar aufgebaut:

- `APIS/minecraft/` enthält die dekompilierten Minecraft-Quellen. Weil das Projekt
  `splitEnvironmentSourceSets()` verwendet, liefert Loom zwei JARs (`common` und
  `clientOnly`); das Skript führt sie in einen Baum zusammen.
- `APIS/fabric-api/` enthält die Quellen aller Fabric-API-Module.
- `APIS/fabric-loader/` enthält die Quellen des Fabric Loaders.
- `APIS/mixin/` enthält die Quellen von Sponge Mixin (`org.spongepowered.asm`) und
  MixinExtras (`com.llamalad7.mixinextras`), also Annotationen, Injection-Points und
  Callback-Typen.
- `tools/SOURCES.json` nennt je Bereich Ursprungs-JARs mit Version und SHA-256 sowie die
  Dateianzahl. Eine Bereichsversion steht nur dort, wo sie eindeutig ist.

Gegen genau diese Datei vergleicht jeder Lauf und meldet, was sich geändert hat: neue,
entfallene und aktualisierte Module (`+`, `-`, `~`), gleiche Version bei anderem Inhalt (`!`)
und am Ende die Dateizahl je Bereich. Ändert sich nichts, steht dort nur, dass `APIS/` bereits
aktuell ist.

Ebenfalls gemeldet wird, was der Build angeboten hat, aber nicht in `APIS/` landet: ein
Source-JAR, das sich nicht lesen lässt, und eines, dessen Java-Pakete zu keinem der vier
Bereiche gehören. Ein unlesbares JAR bricht den Lauf nicht mehr ab, sondern erscheint unter
„Nicht zugeordnete Source-JARs“. Die breite Cache-Suche bleibt dabei stumm – dort liegen
zahllose fremde Source-JARs, gemeldet wird nur, was der Build selbst benannt hat.

Weitere Schalter: `--decompile` erzwingt `genSources`, `--no-decompile` verbietet es,
`--offline` verwendet ausschließlich den Gradle-Cache, `--no-gradle` sucht nur in bereits
vorhandenen Caches, `--project` akzeptiert eine fremde Projektwurzel oder einen Unterordner
davon. Explizite JAR-Pfade (`--minecraft-jar`, `--fabric-api-jar`, `--fabric-loader-jar`,
`--mixin-jar`) sind nur ein Diagnose-Fallback. Das Skript darf unverändert in andere Fabric-Projekte
kopiert werden.

`APIS/` und `tools/SOURCES.json` niemals von Hand ändern. Bei veralteten oder beschädigten
Inhalten das Skript erneut mit `--force` ausführen. Der API-Ordner bleibt in `.gitignore`.

### Verbindlicher Rechercheablauf

Der Ordner `APIS/` wird nur mit ripgrep (`rg`) und ast-grep (`ast-grep`) durchsucht.
Keine eigenen Python-Such-, Parser-, Index- oder Cachewerkzeuge dafür einführen. Auch keine
vollständigen Verzeichnisbäume pauschal öffnen.

1. Mit `rg` nach Dateinamen, Text, Dokumentation, Assets, Typ-, Methoden-, Feld-, Event- oder
   Paketnamen suchen.
2. Für strukturelle Java-Fragen `ast-grep run --lang java` verwenden, etwa für Deklarationen,
   Methodenaufrufe oder bestimmte Syntaxformen. In PowerShell Patterns mit `$` immer in
   einfache Anführungszeichen setzen.
3. Die Treffer mit Pfad und Zeilennummer eingrenzen.
4. Nur die konkret relevanten Dateien und Stellen im Editor oder mit `Get-Content` ansehen.
5. Signatur, Besitzer, Vererbung und Abbruchverhalten am tatsächlichen Quelltext belegen.

Beispiele:

```powershell
rg -n --glob '*.java' 'class LivingEntity|class ServerPlayer' APIS/minecraft
rg -n -i --glob '*.java' 'ServerTickEvents|ServerPlayConnectionEvents' APIS/fabric-api
rg --files APIS/fabric-api | rg 'event|networking|registry'
rg -n --glob '*.java' 'interface ModInitializer' APIS/fabric-loader
rg -n --glob '*.java' '@interface (Inject|Redirect|WrapOperation)' APIS/mixin
ast-grep run --lang java --pattern 'Event<$T> $NAME = $$$REST' APIS/fabric-api
ast-grep run --lang java --pattern 'public void awardStat($$$ARGS) { $$$BODY }' APIS/minecraft
```

`rg` bleibt der schnelle Einstieg für Namen und Text; `ast-grep` wird eingesetzt, wenn die
Java-Struktur relevant ist oder eine Textsuche zu viele falsche Treffer liefert. In `APIS/`
ist ast-grep ausschließlich zur Suche erlaubt, nie mit `--rewrite`. Wenn eine Suche zu breit
ist, Pattern, API-Unterordner oder `--glob` verengen. Erst danach Trefferdateien öffnen.
Annahmen aus älteren Minecraft-, Fabric- oder NeoForge-Versionen zählen nicht als Nachweis.

## Fabric-API, Access Widener und Mixins

Fabric-API-Events, Access Widener und Mixins sind vollwertige Werkzeuge und können je nach
Zweckmäßigkeit und Sauberkeit frei gewählt und kombiniert werden. **Mixins dürfen ausdrücklich
und gerne verwendet werden**, insbesondere für Eingriffe in Rendering, Animationen, Vanilla-Logik
oder Methodenflüsse.

Vor der Umsetzung gilt:

1. `APIS/` aktualisieren, falls es fehlt oder die Gradle-Versionen geändert wurden.
2. In `APIS/` (z. B. `APIS/fabric-api/` oder `APIS/minecraft/`) nach passenden Ansatzpunkten suchen.
3. Den passenden Weg wählen: Fabric-API-Events, ein Eintrag in
   `src/main/resources/oneshotonekill.accesswidener` oder ein Mixin.
4. Mixins dürfen gerne und gezielt eingesetzt werden. Annotation, Injection-Point und
   Callback-Typ dabei an den Quellen unter `APIS/mixin/` belegen, nicht aus dem Gedächtnis.
   MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`, etc.) stehen direkt zur Verfügung.

Aktuell sind die beiden Vorlagen-Mixins `MinecraftServerMixin` und `MinecraftClientMixin`
registriert und können bei Bedarf angepasst, erweitert oder durch neue Mixins ergänzt werden.

Anders als bei NeoForge gibt es in Fabric keine Access Transformer. Das Gegenstück ist der
Access Widener. Er liegt in `src/main/resources/oneshotonekill.accesswidener`, ist in
`fabric.mod.json` und über `loom.accessWidenerPath` in `build.gradle` angemeldet und
verwendet den Namespace `official` (mit `named` bricht der Build ab).
`.\gradlew.bat validateAccessWidener` prüft die Einträge gegen das Minecraft-JAR.

### Wann der Quelltext für ein Mixin nicht reicht

Für einfache Mixins genügt `APIS/`: Einstieg am Anfang oder Ende einer Methode (`@Inject`
mit `HEAD` oder `RETURN`), Ersetzen eines im Quelltext sichtbaren Aufrufs (`@Redirect`,
`@WrapOperation`), Accessor und Invoker. Namen müssen dabei nicht umgerechnet werden: Es gibt
nur den Namespace `official` und kein Refmap, die Namen im Quelltext gelten auch zur Laufzeit.

Sobald `ordinal`, `slice`, eine lokale Variable (`@Local`, `@ModifyVariable`), eine Konstante
(`@Constant`) oder ein Lambda-Körper im Spiel ist, reicht der dekompilierte Quelltext nicht.
Lambdas, `invokedynamic`-Aufrufe, Slot-Nummern lokaler Variablen und zurückgebaute Schleifen
stehen im Bytecode anders als in `APIS/minecraft/`. Dann zusätzlich die Zielklasse mit `javap`
ansehen; das gemappte Minecraft-JAR liegt bereits im Loom-Cache und muss nicht nachgeladen
werden:

```powershell
$Jar = (Get-ChildItem .gradle/loom-cache/minecraftMaven -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -like '*common*' } |
    Select-Object -First 1).FullName
javap -p -c -l -cp $Jar net.minecraft.server.MinecraftServer
```

Für Client-Klassen statt `*common*` nach `*clientOnly*` filtern. Ein falscher Injection-Point
fällt wegen `defaultRequire` 1 beim Start hart auf und läuft nicht still ins Leere.

## Wichtige Fabric-Besonderheiten

- Einstiegspunkte stehen in `src/main/resources/fabric.mod.json`: `main`
  (`ModInitializer`), `client` (`ClientModInitializer`) und `fabric-datagen`
  (`DataGeneratorEntrypoint`). Es gibt keinen Mod- und keinen Game-Bus wie bei NeoForge.
- Fabric API stellt Ereignisse als `Event<T>`-Konstanten mit Callback-Interfaces bereit,
  die per `EVENT.register(...)` abonniert werden. Ob ein Callback abbrechen kann, ergibt
  sich aus seinem Rückgabetyp und ist im Quelltext unter `APIS/fabric-api/` zu belegen.
- Registrierungen laufen über die Vanilla-Registries mit einer eigenen `Identifier`;
  die genaue Halterklasse und Signatur immer in `APIS/minecraft/` prüfen.
- Netzwerkpakete werden als Payload-Typ registriert und über die Networking-Module von
  Fabric API verschickt. Registrierung, Nebenläufigkeit und Ausführungs-Thread vor der
  Verwendung im Quelltext von `APIS/fabric-api/` nachlesen.
- Client-Code gehört nach `src/client/java`. Das Source-Set wird nur auf dem Client geladen;
  ein Zugriff aus `src/main/java` darauf ist ein Kompilierfehler und kein Laufzeitproblem.
  Deshalb ersetzt die Source-Set-Trennung die NeoForge-Abgrenzung über `Dist`.
- Mixins werden in `oneshotonekill.mixins.json` (gemeinsam) beziehungsweise
  `oneshotonekill.client.mixins.json` (nur Client) eingetragen. `compatibilityLevel` ist
  `JAVA_25`, `defaultRequire` ist 1: Ein nicht greifender Injection-Point lässt den Start
  scheitern statt still ins Leere zu laufen.
- Der Loader bringt neben Sponge Mixin auch MixinExtras mit. Dessen Annotationen sind ohne
  zusätzliche Abhängigkeit nutzbar; ihre genaue Semantik steht unter `APIS/mixin/`.

## Minecraft 26.2

Wichtige Brüche gegenüber 26.1.2:

- `ResourceLocation` wurde durch `net.minecraft.resources.Identifier` ersetzt.
- `Minecraft.screen` / `setScreen` wurde zu `Minecraft.gui.screen()` / `gui.setScreen()`;
  `Minecraft#gui` ist weiterhin vom Typ `Gui`.
- Farbige Blöcke und Items liegen in `net.minecraft.world.level.block.ColorCollection`,
  einem Record mit Zugriffsmethoden je Farbe und `pick(DyeColor)`, etwa `Blocks.WOOL.black()`.
- `EntityType.ITEM_DISPLAY` wurde zu `EntityTypes.ITEM_DISPLAY`.
- Portal- und Verzerrungs-Overlay liegen in `net.minecraft.client.gui.Hud` statt in `Gui`.
- `Options.hideGui` ist entfallen.

Das Projekt deklariert keine Mappings-Abhängigkeit; die Klassennamen im Quelltext entsprechen
genau denen unter `APIS/minecraft/`. Loom verlangt im Access Widener deshalb den Namespace
`official` und lehnt `named` beim Bauen ab.

Das Modellformat unterstützt freie Euler-Drehwinkel je Element. Laufzeitfarben verwenden
`minecraft:dye`, `tintindex: 0` und `DyedItemColor`.

## Technologie und Abhängigkeiten

- Java 25, Minecraft 26.2, Fabric Loader 0.19.4, Fabric API 0.158.0+26.2, Fabric Loom
  1.17.20 und Gradle 9.5.1. Versionen werden nur in `gradle.properties` gepflegt.
- Zwei Source-Sets über `splitEnvironmentSourceSets()`; beide gehören zur Mod
  `oneshotonekill` im `loom.mods`-Block.
- Keine zusätzlichen Mod-Laufzeitbibliotheken. Eine neue Abhängigkeit braucht eine
  Begründung, warum Vanilla und Fabric API nicht ausreichen. Neue Abhängigkeiten werden mit
  `modImplementation` deklariert, wenn sie selbst eine Mod sind.
- Nach jeder Änderung an `build.gradle` oder `gradle.properties`
  `python tools/update_api_sources.py` erneut ausführen.

## Quellen, Assets und Build

- Unter `src/main/java` und `src/client/java` sind ausschließlich `.java`-Dateien erlaubt.
  `src/main/resources` und `src/client/resources` enthalten die benötigten Assets.
- Generierte Assets entstehen über den `fabric-datagen`-Einstiegspunkt
  (`.\gradlew.bat runDatagen`) und werden nicht von Hand nachbearbeitet.
- Build und Deployment immer über `.\build.ps1` beziehungsweise
  `powershell -ExecutionPolicy Bypass -File ./build.ps1`. Das Skript liest `mod_id` aus
  `gradle.properties` und die Zielordner aus `deploy.properties`, baut mit Gradle, kopiert
  das JAR atomar in Server und Client und verifiziert SHA-256.
- `.\build.ps1 -Reconfigure` setzt die Deploy-Pfade neu, `.\build.ps1 -Clean` baut sauber,
  `.\build.ps1 -StopDaemons` beendet hängende Gradle-Daemons.
- Ein grüner Build ersetzt keinen Laufzeittest. Bei Änderungen an Rendering, HUD, Netzwerk
  oder Mixins zusätzlich `.\gradlew.bat runClient` beziehungsweise `runServer` starten und
  das Log prüfen.
