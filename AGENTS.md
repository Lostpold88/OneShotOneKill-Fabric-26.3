# Projektregeln

## Kurzfassung

- **IntelliJ IDEA & MCP (`intellij-index`)** sind das primäre Werkzeug für Code-Intelligence, Navigation und Refactoring. Die vollständige Anleitung und Werkzeugreferenz liegt im Ordner [`ide-index-mcp/`](ide-index-mcp/) und ist vor jeglichen Code-Operationen **definitiv und verbindlich anzuschauen und zu befolgen**.
- **Fabric-API, Access Widener und Mixins** sind vollwertige Werkzeuge und dürfen jederzeit frei und gezielt nach Zweckmäßigkeit genutzt werden.
- Externe Bibliotheken (Minecraft, Fabric API, Fabric Loader, Sponge Mixin, MixinExtras, Brigadier, Netty, Java SDK etc.) werden direkt über die **IntelliJ IDEA MCP-Engine** (`scope: "project_and_libraries"`) semantisch analysiert.
- Nach externen Datei- oder Strukturänderungen durch Agenten wird das Dateisystem mit der IDE synchronisiert.
- Zielplattform: **Java 25, Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.158.0+26.2, Fabric Loom 1.17-SNAPSHOT, Gradle 9.5.1**. Alle Versionen stehen in `gradle.properties`.
- Mod-ID `oneshotonekill`, Package `com.oneshotonekill`.
- Geteilte Source-Sets: `src/main/java` läuft auf Server und Client, `src/client/java` ausschließlich auf dem Client.
- Build und Deployment laufen über [`.\build.ps1`](file:///E:/OneShotOneKill/MOD/build.ps1) (bzw. die verknüpfte IntelliJ Run Configuration `BUILD`).

---

## Code-Intelligence & Navigation (IntelliJ IDEA MCP)

Die Anbindung an IntelliJ IDEA erfolgt über das **intellij-index MCP** (`http://127.0.0.1:29170/index-mcp/streamable-http`).

> [!IMPORTANT]
> **Verbindliche Dokumentation im Ordner [`ide-index-mcp/`](ide-index-mcp/):**
> Vor der Navigation, Recherche oder Bearbeitung von Code muss zwingend die Dokumentation im Ordner [`ide-index-mcp/`](ide-index-mcp/) konsultiert werden:
> - **[`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md):** Umfassender Agenten-Leitfaden, Workflows, Dumb/Smart-Mode-Strategien und Best Practices.
> - **[`ide-index-mcp/references/tools-reference.md`](ide-index-mcp/references/tools-reference.md):** Vollständige Referenz aller verfügbaren MCP-Werkzeuge, Scopes, Filter und Parameter.

---

## Fabric-API, Access Widener und Mixins

Fabric-API-Events, Access Widener und Mixins sind vollwertige Werkzeuge und können je nach Zweckmäßigkeit und Sauberkeit frei gewählt und kombiniert werden. **Mixins dürfen ausdrücklich und gerne verwendet werden**, insbesondere für Eingriffe in Rendering, Animationen, Vanilla-Logik oder Methodenflüsse.

### Vorgehen bei der Umsetzung:

1. **API-Prüfung per MCP:** Zielklasse und Methoden in Minecraft/Fabric semantisch über die IDE verifizieren.
2. **Den passenden Weg wählen:**
   - Fabric-API-Events (wenn ein sauberes Callback existiert).
   - Access Widener in `src/main/resources/oneshotonekill.accesswidener` (für Sichtbarkeit oder `mutable`).
   - Mixin (für Eingriffe in Ausführungsflüsse, Werte-Modifikationen oder Rendering).
3. **Mixin-Deskriptoren exakt ableiten:**
   - Methoden- und Feld-Signaturen über die MCP-Typinformationen prüfen.
   - Bytecode-Deskriptoren nach JVM-Spezifikation aufbauen.
   - MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`, `@Local`, `@Share`) stehen direkt zur Verfügung und dürfen bevorzugt werden.

### Access Widener

- Datei: `src/main/resources/oneshotonekill.accesswidener`.
- Verwendet zwingend den Namespace `official` (mit `named` bricht der Build ab).
- Validierung: `.\gradlew.bat validateAccessWidener` prüft die Einträge gegen das Minecraft-JAR.

### Bytecode-Inspektion bei komplexen Mixins

Sobald `ordinal`, `slice`, lokale Variablen-Slots (`@Local`, `@ModifyVariable`), Konstanten (`@Constant`) oder interne Lambda-Körper betroffen sind, zusätzlich den Bytecode mit `javap` aus dem Loom-Cache prüfen:

```powershell
$Jar = (Get-ChildItem .gradle/loom-cache/minecraftMaven -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -like '*common*' } |
    Select-Object -First 1).FullName
javap -p -c -l -cp $Jar net.minecraft.server.MinecraftServer
```

---

## Wichtige Fabric-Besonderheiten

- **Einstiegspunkte** in `src/main/resources/fabric.mod.json`:
  - `main`: `ModInitializer` (gemeinsam)
  - `client`: `ClientModInitializer` (nur Client)
  - `fabric-datagen`: `DataGeneratorEntrypoint`
- **Fabric Events:** `Event<T>`-Konstanten mit Callbacks werden per `EVENT.register(...)` abonniert. Das Abbruchverhalten ergibt sich aus dem Rückgabetyp des Callbacks (über MCP prüfbar).
- **Registrierungen:** Erfolgen über Vanilla-Registries mit `Identifier`.
- **Netzwerk:** Payload-Typen registrieren und über die Fabric Networking API (z. B. `ServerPlayNetworking`, `ClientPlayNetworking`) senden.
- **Client-Trennung:** Client-Code gehört strikt nach `src/client/java`. Ein Zugriff aus `src/main/java` darauf führt zu einem Kompilierfehler.
- **Mixins-Konfiguration:**
  - `oneshotonekill.mixins.json` (gemeinsam)
  - `oneshotonekill.client.mixins.json` (nur Client)
  - `compatibilityLevel` ist `JAVA_25`, `defaultRequire` ist `1` (Fehler fallen beim Start sofort auf).

---

## Minecraft 26.2 Besonderheiten

- `ResourceLocation` wurde durch `net.minecraft.resources.Identifier` ersetzt.
- `Minecraft.screen` / `setScreen` wurde zu `Minecraft.gui.screen()` / `gui.setScreen()`.
- Farbige Blöcke/Items liegen in `net.minecraft.world.level.block.ColorCollection` mit `pick(DyeColor)`.
- `EntityType.ITEM_DISPLAY` wurde zu `EntityTypes.ITEM_DISPLAY`.
- Portal- und Verzerrungs-Overlay liegen in `net.minecraft.client.gui.Hud`.
- Namespace im Access Widener und in Mixins ist `official` (keine intermediären Mappings nötig).

---

## Build, Deployment und IDE-Integration

- **Build & Deployment:** Immer über [`.\build.ps1`](file:///E:/OneShotOneKill/MOD/build.ps1) bzw. `powershell -ExecutionPolicy Bypass -File ./build.ps1` (oder die IntelliJ Run Configuration `BUILD`).
  - Liest `mod_id` aus `gradle.properties`.
  - Liest Zielordner aus `deploy.properties`.
  - Baut die Mod mit Gradle und kopiert das JAR atomar in Server- und Client-Mods-Ordner.
- **Parameter für `build.ps1`:**
  - `-Reconfigure`: Setzt die Deploy-Pfade neu.
  - `-Clean`: Führt vorab `gradlew clean` aus.
  - `-StopDaemons`: Beendet hängende Gradle-Daemons.
- **IDE Local History:** Bei unerwünschten Dateiänderungen bietet IntelliJ über `Local History > Show History` die Möglichkeit, jeden Zustand sofort per Revert wiederherzustellen.
