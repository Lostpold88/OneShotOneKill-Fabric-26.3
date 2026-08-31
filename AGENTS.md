# Projektregeln & KI-Agenten-Direktiven

> [!CRITICAL]
> ### 🤖 ZWINGENDE BETRIEBSANWEISUNGEN FÜR KI-AGENTEN (AI AGENT DIRECTIVES)
> 
> **Als KI-Coding-Assistent in diesem Projekt bist du an folgende absolute Prioritätsregeln gebunden:**
>
> 1. **IMMER DIE BEIDEN PROJEKT-SKILLS LESEN & BEFOLGEN:**
>    - Du **MUSST** vor Beginn deiner Arbeit die beiden projektspezifischen Skill-Dateien vollständig laden und ihre Workflows ausnahmslos befolgen:
>      - 📖 [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md) & [`ide-index-mcp/references/tools-reference.md`](ide-index-mcp/references/tools-reference.md)
>      - 📖 [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md) & [`jetbrains-debugger/references/tool-reference.md`](jetbrains-debugger/references/tool-reference.md)
>
> 2. **PFLICHT ZUR AUSSCHLIESSLICHEN NUTZUNG DER BEIDEN MCP-SERVER (`intellij-index` & `jetbrains-debugger`):**
>    - Du **MUSST IMMER IMMER IMMER** für ausnahmslos alle semantischen Aufgaben, Recherchen, Typabfragen, Methodensignaturen und Code-Analysen die MCP-Tools nutzen:
>      - **Code-Intelligence & Navigation (`intellij-index`):** `ide_find_class`, `ide_find_definition`, `ide_find_references`, `ide_diagnostics`, `ide_type_hierarchy`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`, `ide_search_text`.
>      - **Dateisystem-Synchronisation:** `ide_sync_files` nach **jeder** Dateiänderung aufrufen.
>      - **Client-Start & Runtime-Debugging (`jetbrains-debugger`):** `start_debug_session(configuration_name: "Minecraft Client")`, `set_breakpoint`, `get_debug_session_status`, `wait_for_pause`, `evaluate_expression`, `resume_execution`, `stop_debug_session`.
>    - ⛔ **STRIKT VERBOTEN:** 
>      - Verwende **NIEMALS** CLI-Bytecode-Tools wie `javap`, `disassemble` oder Disassembler-Skripte! Alle Typen, Methoden, Parameter und Klassenstrukturen werden ausschließlich semantisch über `intellij-index` (`ide_find_class`, `ide_find_definition`, `ide_type_hierarchy` etc.) analysiert.
>      - Verwende **NIEMALS** reine Textsuch-Tools (`grep`, Textsuche) oder Vermutungen, wenn semantische IDE-Index-Tools zur Verfügung stehen.
>      - Starte den Client **NIEMALS** ohne Debugger-MCP!

## Kurzfassung

- **IntelliJ IDEA & MCP (`intellij-index`)** sind das **einzige und primäre Werkzeug** für Code-Intelligence, Navigation, Klassenstrukturen, Methodensignaturen und Refactoring. Standard-Aktionen werden **immer direkt über die nativen/lazy MCP-Tools** aufgerufen (`ide_find_class`, `ide_find_definition`, `ide_find_references`, `ide_diagnostics`, `ide_type_hierarchy`, `ide_call_hierarchy`, etc.), wie in [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md) definiert.
- **JetBrains Debugger MCP (`jetbrains-debugger`)** ist das primäre Werkzeug für interaktives Runtime-Debugging, Haltepunkte und Variableninspektion. Standard-Aktionen werden **immer direkt über die nativen/lazy Debugger-MCP-Tools** aufgerufen (`start_debug_session`, `set_breakpoint`, `get_debug_session_status`, `evaluate_expression`, `resume_execution`, `wait_for_pause`, etc.), wie in [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md) definiert.
- **Automatisierte Batch-Skripte in [`tools/`](tools/):** Für komplexe Mehrschritt- oder Schleifen-Operationen, die nicht in einem einzelnen MCP-Tool-Aufruf möglich sind, stehen spezialisierte Automatisierungs-Skripte bereit:
  - [`python tools/mcp_index.py scan-project`](tools/mcp_index.py): Sequentieller Diagnose-Scan über alle Java-Dateien im Projekt in einem Durchlauf.
  - [`python tools/mcp_debugger.py clear-all-bp`](tools/mcp_debugger.py): Batch-Abfrage und restloses Löschen aller aktiven Breakpoints in einem Schritt.
- **Fabric-API, Access Widener und Mixins** sind vollwertige Werkzeuge und dürfen jederzeit frei und gezielt nach Zweckmäßigkeit genutzt werden.
- Externe Bibliotheken (Minecraft, Fabric API, Fabric Loader, Sponge Mixin, MixinExtras, Brigadier, Netty, Java SDK etc.) werden direkt über die **IntelliJ IDEA MCP-Engine** (`scope: "project_and_libraries"`) semantisch analysiert.
- Nach externen Datei- oder Strukturänderungen durch Agenten wird das Dateisystem mit der IDE synchronisiert (`ide_sync_files`).
- Zielplattform: **Java 25, Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.158.0+26.2, Fabric Loom 1.17-SNAPSHOT, Gradle 9.7.1**. Alle Versionen stehen in [`gradle.properties`](gradle.properties).
- Mod-ID `g-sync-mod`, Package `com.gsyncmod`.
- Build und Deployment laufen über [`build.ps1`](build.ps1) (bzw. die verknüpfte IntelliJ Run Configuration `BUILD`).
- **Client-Start:** Der Minecraft Client wird **immer direkt aus IntelliJ IDEA heraus über das Debugger-MCP im Debug-Modus** gestartet (`start_debug_session(configuration_name: "Minecraft Client")` oder `execute_run_configuration(name: "Minecraft Client", mode: "debug")`). Niemals als getrennter Terminal-Prozess ohne Debugger-Anbindung!

---

## Code-Intelligence & Navigation (IntelliJ IDEA MCP)

Die Anbindung an IntelliJ IDEA erfolgt über das **intellij-index MCP** (`http://127.0.0.1:29170/index-mcp/streamable-http`).

> [!IMPORTANT]
> **Direkte MCP-Nutzung nach [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md):**
> Alle Navigations- und Code-Recherchen müssen direkt über die MCP-Werkzeuge der IDE ausgeführt werden:
> - **[`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md):** Umfassender Agenten-Leitfaden, Workflows, Dumb/Smart-Mode-Strategien und Best Practices.
> - **[`ide-index-mcp/references/tools-reference.md`](ide-index-mcp/references/tools-reference.md):** Vollständige Referenz aller verfügbaren MCP-Werkzeuge (`ide_find_class`, `ide_find_definition`, `ide_find_references`, `ide_diagnostics`, `ide_search_text`, `ide_call_hierarchy`, `ide_type_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`, `ide_sync_files`, etc.).
> - **Batch-Automatisierung:** Für projektweite Prüfungen steht [`tools/mcp_index.py`](tools/mcp_index.py) mit `python tools/mcp_index.py scan-project` zur Verfügung.

---

## Runtime-Debugging & Inspektion (JetBrains Debugger MCP)

Die Anbindung an den Debugger erfolgt über das **jetbrains-debugger MCP** (`http://127.0.0.1:29190/debugger-mcp/streamable-http`).

> [!IMPORTANT]
> **Direkte MCP-Nutzung nach [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md):**
> Bei unklarem Laufzeitverhalten, fehlerhaften Werten, NullPointern oder unvorhergesehenem Kontrollfluss wird nicht im Code geraten, sondern programmatisch über MCP gedebuggt:
> - **[`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md):** Umfassender Leitfaden, Debugging-Muster, Pausen-Handling und Best Practices.
> - **[`jetbrains-debugger/references/tool-reference.md`](jetbrains-debugger/references/tool-reference.md):** Vollständige Referenz aller Debugger-Werkzeuge (`start_debug_session`, `set_breakpoint`, `get_debug_session_status`, `wait_for_pause`, `evaluate_expression`, `resume_execution`, `pause_execution`, `step_over`, `step_into`, `step_out`, `run_to_line`, etc.).
> - **Batch-Automatisierung:** Für das restlose Bereinigen aller Breakpoints steht [`tools/mcp_debugger.py`](tools/mcp_debugger.py) mit `python tools/mcp_debugger.py clear-all-bp` zur Verfügung.
>
> **Kernregeln für Debugging:**
> 1. **Client-Start via IntelliJ Debugger MCP:** Den Minecraft Client immer über `start_debug_session(configuration_name: "Minecraft Client")` (oder `execute_run_configuration(name: "Minecraft Client", mode: "debug")`) starten, damit die JVM-Instanz dauerhaft im Debugger eingeklinkt ist.
> 2. Breakpoints **vor** dem Auslösen der Aktion setzen (`set_breakpoint`).
> 3. Nach Stepping (`step_over`, `step_into`, etc.) oder `resume_execution` immer mit `wait_for_pause` auf die Pause warten.
> 4. Dateipfade für Breakpoints müssen **absolut** sein, Zeilennummern **1-basiert**.
> 5. Zur Status- und Variableninspektion primär `get_debug_session_status` nutzen (bündelt Stack, Variablen, Code und Lokation in einem Aufruf).
> 6. Nach Abschluss der Untersuchung Breakpoints mit `python tools/mcp_debugger.py clear-all-bp` aufräumen oder die Session mit `stop_debug_session` beenden.

---

## Fabric-API, Access Widener und Mixins

Fabric-API-Events, Access Widener und Mixins sind vollwertige Werkzeuge und können je nach Zweckmäßigkeit und Sauberkeit frei gewählt und kombiniert werden. **Mixins dürfen ausdrücklich und gerne verwendet werden**, insbesondere für Eingriffe in Rendering, Animationen, Vanilla-Logik oder Methodenflüsse.

### Vorgehen bei der Umsetzung:

1. **API-Prüfung per MCP:** Zielklasse und Methoden in Minecraft/Fabric semantisch über die IDE (`ide_find_class`, `ide_find_definition`, `ide_type_hierarchy`) verifizieren.
2. **Den passenden Weg wählen:**
   - Fabric-API-Events (wenn ein sauberes Callback existiert).
   - Access Widener in [`src/main/resources/g-sync-mod.accesswidener`](src/main/resources/g-sync-mod.accesswidener) (für Sichtbarkeit oder `mutable`).
   - Mixin (für Eingriffe in Ausführungsflüsse, Werte-Modifikationen oder Rendering).
3. **Mixin-Deskriptoren exakt ableiten:**
   - Methoden- und Feld-Signaturen über die MCP-Typinformationen prüfen.
   - Bytecode-Deskriptoren nach JVM-Spezifikation aufbauen.
   - MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`, `@Local`, `@Share`) stehen direkt zur Verfügung und dürfen bevorzugt werden.

---

## Wichtige Fabric-Besonderheiten

- **Einstiegspunkte** in [`src/main/resources/fabric.mod.json`](src/main/resources/fabric.mod.json):
  - `main`: `ModInitializer` (gemeinsam)
  - `client`: `ClientModInitializer` (nur Client)
  - `fabric-datagen`: `DataGeneratorEntrypoint`
- **Fabric Events:** `Event<T>`-Konstanten mit Callbacks werden per `EVENT.register(...)` abonniert. Das Abbruchverhalten ergibt sich aus dem Rückgabetyp des Callbacks (über MCP prüfbar).
- **Registrierungen:** Erfolgen über Vanilla-Registries mit `Identifier`.
- **Netzwerk:** Payload-Typen registrieren und über die Fabric Networking API (z. B. `ServerPlayNetworking`, `ClientPlayNetworking`) senden.
- **Mixins-Konfiguration:**
  - [`g-sync-mod.mixins.json`](src/main/resources/g-sync-mod.mixins.json)
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

- **Build & Deployment:** Immer über [`build.ps1`](build.ps1) bzw. `powershell -ExecutionPolicy Bypass -File ./build.ps1` (oder die IntelliJ Run Configuration `BUILD`).
  - Liest `mod_id` aus [`gradle.properties`](gradle.properties).
  - Liest Zielordner aus [`deploy.properties`](deploy.properties).
  - Baut die Mod mit Gradle und kopiert das JAR atomar in Server- und Client-Mods-Ordner.
- **Parameter für `build.ps1`:**
  - `-Reconfigure`: Setzt die Deploy-Pfade neu.
  - `-Clean`: Führt vorab `gradlew clean` aus.
  - `-StopDaemons`: Beendet hängende Gradle-Daemons.
- **IDE Local History:** Bei unerwünschten Dateiänderungen bietet IntelliJ über `Local History > Show History` die Möglichkeit, jeden Zustand sofort per Revert wiederherzustellen.
