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
> 2. **PFLICHT ZUR AUSNAHMSLOSEN BEVORZUGUNG & NUTZUNG ALLER MCP-WERKZEUGE BEIDER SERVER:**
>    - Alle Werkzeuge beider MCP-Server (`intellij-index` & `jetbrains-debugger`) sind in der IDE vollständig aktiviert. Du **MUSST AUSNAHMSLOS ALLE** von beiden Plugins bereitgestellten Tools für sämtliche Aufgaben (Dateierstellung, Code-Modifikation, Member-Editing, Refactoring, Formatierung, Imports, Conversion, Diagnostics, Build, Test, Debugging) bevorzugen und aktiv nutzen:
>      - **Dateien Lesen & Quellcode-Inspektion:**
>        - **Lokale Projektdateien (`src/...`, Konfigurationen, Assets):** Das interne KI-Dateilese-Tool (`view_file`) darf für schnelles und präzises Lesen lokaler Projektdateien verwendet werden.
>        - **Externe Bibliotheken, Minecraft-Interna & JAR-Archive:** Müssen ausnahmslos über `ide_read_file` (aus `intellij-index`) gelesen werden.
>      - **Code-Modifikation & Refactoring (`intellij-index`):** `ide_reformat_code`, `ide_optimize_imports`, `ide_convert_java_to_kotlin`, `ide_edit_member`, `ide_insert_member`, `ide_replace_member`, `ide_change_signature`, `ide_structural_search_replace`, `ide_replace_text_in_file`, `ide_refactor_rename`, `ide_refactor_safe_delete`, `ide_move_file`.
>      - **Dateien, VFS & Workspace (`intellij-index`):** `ide_create_file` (direkt im VFS anlegen, sofort indiziert), `ide_open_file`, `ide_open_project`, `ide_reload_project`, `ide_get_active_file`.
>      - **Code-Intelligence & Analyse (`intellij-index`):** `ide_symbol_info`, `ide_file_structure`, `ide_find_symbol`, `ide_find_class`, `ide_find_definition`, `ide_find_references`, `ide_diagnostics`, `ide_project_diagnostics`, `ide_type_hierarchy`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`, `ide_search_text`.
>      - **Build & Testing (`intellij-index`):** `ide_build_project`, `ide_list_tests`, `ide_run_tests`.
>      - **Dateisystem-Synchronisation:** `ide_sync_files` nach jeder externen Dateiänderung aufrufen.
>      - **Client-Start & Runtime-Debugging (`jetbrains-debugger`):** `start_debug_session(configuration_name: "Minecraft Client")`, `set_breakpoint`, `get_debug_session_status`, `wait_for_pause`, `evaluate_expression`, `resume_execution`, `pause_execution`, `step_over`, `step_into`, `step_out`, `run_to_line`, `get_stack_trace`, `select_stack_frame`, `list_threads`, `get_variables`, `set_variable`, `stop_debug_session`.
>    - ⛔ **STRIKT VERBOTEN:** 
>      - Verwende **NIEMALS** CLI-Bytecode-Tools wie `javap`, `disassemble` oder Disassembler-Skripte! Alle Typen, Methoden, Parameter und Klassenstrukturen werden ausschließlich semantisch über `intellij-index` (`ide_find_class`, `ide_find_definition`, `ide_symbol_info`, `ide_type_hierarchy` etc.) analysiert.
>      - Verwende **NIEMALS** reine Textsuch-Tools (`grep`, Textsuche) oder Vermutungen, wenn semantische IDE-Index-Tools zur Verfügung stehen.
>      - Führe Datei- und Member-Änderungen bevorzugt über die IDE-Tools (`ide_create_file`, `ide_edit_member`, `ide_insert_member`, `ide_refactor_rename` etc.) aus.
>      - Starte den Client **NIEMALS** ohne Debugger-MCP!
>      - Führe Builds **NIEMALS** über manuelle CLI-Befehle im Terminal aus, wenn die MCP-Tools `ide_build_project` oder `execute_run_configuration` zur Verfügung stehen!

## Kurzfassung

- **IntelliJ IDEA & MCP (`intellij-index`)** sind das **einzige und primäre Werkzeug** für Code-Intelligence, Navigation, Klassenstrukturen, Methodensignaturen, File-Creation, Import-Optimierung, Reformatting, Refactoring und Build (`ide_build_project`). Sämtliche Aktionen werden **ausnahmslos und immer direkt über die MCP-Tools** aufgerufen, wie in [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md) definiert.
- **JetBrains Debugger MCP (`jetbrains-debugger`)** ist das primäre Werkzeug für interaktives Runtime-Debugging, Haltepunkte, Variableninspektion und Run-Konfigurationen (`execute_run_configuration(name: "BUILD", mode: "run")`). Standard-Aktionen werden **immer direkt über die Debugger-MCP-Tools** aufgerufen, wie in [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md) definiert.
- **Automatisierte Batch-Skripte in [`tools/`](tools/):** Für komplexe Mehrschritt- oder Schleifen-Operationen, die nicht in einem einzelnen MCP-Tool-Aufruf möglich sind, stehen spezialisierte Automatisierungs-Skripte bereit:
  - [`python tools/mcp_index.py scan-project`](tools/mcp_index.py): Sequentieller Diagnose-Scan über alle Java-Dateien im Projekt in einem Durchlauf.
  - [`python tools/mcp_debugger.py clear-all-bp`](tools/mcp_debugger.py): Batch-Abfrage und restloses Löschen aller aktiven Breakpoints in einem Schritt.
- **Fabric-API, Access Widener und Mixins** sind vollwertige Werkzeuge und dürfen jederzeit frei und gezielt nach Zweckmäßigkeit genutzt werden.
- Externe Bibliotheken (Minecraft, Fabric API, Fabric Loader, Sponge Mixin, MixinExtras, Brigadier, Netty, Java SDK etc.) werden direkt über die **IntelliJ IDEA MCP-Engine** (`scope: "project_and_libraries"`) semantisch analysiert.
- Nach externen Datei- oder Strukturänderungen durch Agenten wird das Dateisystem mit der IDE synchronisiert (`ide_sync_files`).
- Zielplattform: **Java 25, Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.160.0+26.2, Fabric Loom 1.17-SNAPSHOT, Gradle 9.7.1**. Alle Versionen stehen in [`gradle.properties`](gradle.properties).
- Mod-ID `oneshotonekill`, Package `com.oneshotonekill`.
- **Build und Deployment:** Werden **ausnahmslos über MCP-Tools** ausgeführt:
  - `ide_build_project` (aus `intellij-index`) für schnelle strukturierte Compiler-Prüfungen & Fehlerrückmeldungen.
  - `execute_run_configuration(name: "BUILD", mode: "run")` (aus `jetbrains-debugger`) für den vollständigen Build- und Deployment-Lauf inklusive Mod-Kopieren.
- **Client-Start:** Der Minecraft Client wird **immer direkt aus IntelliJ IDEA heraus über das Debugger-MCP im Debug-Modus** gestartet (`start_debug_session(configuration_name: "Minecraft Client")` oder `execute_run_configuration(name: "Minecraft Client", mode: "debug")`). Niemals als getrennter Terminal-Prozess ohne Debugger-Anbindung!

---

## Code-Intelligence & Navigation (IntelliJ IDEA MCP)

Die Anbindung an IntelliJ IDEA erfolgt über das **intellij-index MCP** (`http://127.0.0.1:29170/index-mcp/streamable-http`).

> [!IMPORTANT]
> **Direkte MCP-Nutzung nach [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md):**
> Alle Werkzeuge sind in der IDE voll aktiviert und **MÜSSEN ausnahmslos bevorzugt** für sämtliche Operationen verwendet werden:
>
> ### Vollständige Übersicht aller aktiven MCP-Tools (`intellij-index`):
> 
> 1. **Code-Intelligence & Navigation:**
>    - `ide_find_class`: Klassen nach Namen / CamelCase suchen.
>    - `ide_find_definition`: Zur Deklaration / Definition springen.
>    - `ide_find_references`: Semantische Verwendungsstellen projektweit finden.
>    - `ide_find_symbol`: Beliebige Code-Symbole (Methoden, Felder, Klassen) finden.
>    - `ide_find_implementations`: Implementierungen von Interfaces & abstrakten Methoden finden.
>    - `ide_find_super_methods`: Basis-/Interface-Methoden ermitteln, die überschrieben werden.
>    - `ide_type_hierarchy`: Vollständige Vererbungshierarchie (Super- und Subtypen).
>    - `ide_call_hierarchy`: Aufrufhierarchie (`callers` / `callees`) analysieren.
>    - `ide_file_structure`: Strukturbaum / Outline einer Datei mit Zeilenangaben.
>    - `ide_symbol_info`: Voll aufgelöste Typen, Signaturen & JavaDoc-Dokumentation.
>    - `ide_search_text`: Textsuche / Regex über den IntelliJ-Index.
>    - `ide_diagnostics`: Compiler-, Syntaxfehler und Quick-Fixes einer Datei.
>    - `ide_project_diagnostics`: Projektweiter Batch-Diagnose-Scan aller Dateien.
>
> 2. **Code-Modifikation & Refactoring:**
>    - `ide_refactor_rename`: Sicheres Umbenennen inkl. Getter/Setter, Overrides & Verwendungen.
>    - `ide_refactor_safe_delete`: Sicheres Löschen mit automatischer Verwendungsprüfung.
>    - `ide_move_file`: Datei verschieben mit automatischer Package- und Import-Aktualisierung.
>    - `ide_reformat_code`: Code nach Projekt-Style (.editorconfig / IDE) formatieren.
>    - `ide_optimize_imports`: Unbenutzte Imports entfernen und sortieren.
>    - `ide_change_signature`: Methodensignaturen projektweit sicher anpassen.
>    - `ide_edit_member`: Vollständiges Member (Signatur + Body) ersetzen.
>    - `ide_insert_member`: Neues Member (Methode/Feld) strukturiert an Position einfügen.
>    - `ide_replace_member`: Methoden-Body oder Feld-Initializer ersetzen (Signatur bleibt erhalten).
>    - `ide_replace_text_in_file`: Textersetzung über das IDE-Dokumentenmodell (sofort indexiert).
>    - `ide_structural_search_replace`: Structural Search and Replace (SSR).
>    - `ide_convert_java_to_kotlin`: Java-Klassen via IntelliJ J2K zu Kotlin konvertieren.
>
> 3. **Dateien, VFS & Workspace:**
>    - `ide_create_file`: Neue Quellcodedatei direkt im VFS anlegen (sofort indexiert).
>    - `ide_create_module`: Neues Modul anlegen.
>    - `ide_find_file`: Dateien im Projekt nach Namen suchen.
>    - `ide_read_file`: Quellcode aus externen JARs / Bibliotheken und Abhängigkeiten lesen (für lokale Projektdateien steht das interne Lesetool `view_file` zur Verfügung).
>    - `ide_open_file`: Datei an genauer Zeile/Spalte im Editor öffnen.
>    - `ide_get_active_file`: Aktuell im Editor fokussierte Datei abfragen.
>    - `ide_open_project` / `ide_open_workspace`: Projekte / Worktrees per MCP öffnen.
>    - `ide_close_project` / `ide_reload_project`: Projekte schließen oder neu laden.
>    - `ide_link_build_system` / `ide_import_modules`: Build-System / Module integrieren.
>    - `ide_install_plugin` / `ide_restart`: IDE-Plugins installieren / IDE neustarten.
>    - `ide_sync_files`: Virtuelles Dateisystem mit externen Änderungen synchronisieren.
>    - `ide_index_status`: Indexierungsstatus & Smart-/Dumb-Mode abfragen.
>
> 4. **Build & Tests:**
>    - `ide_build_project`: Projekt mit IDE-Build-System bauen und Fehler strukturiert erfassen.
>    - `ide_list_tests`: Alle Unit-/Integrationstests im Projekt auflisten.
>    - `ide_run_tests`: Tests über den IDE-Test-Runner ausführen und auswerten.
>
> 5. **Lifecycle Management:**
>    - `ide_project_status`, `ide_enroll_all_projects`, `ide_release_all_projects`, `ide_release_project`, `ide_get_project_modes`, `ide_set_project_mode`, `ide_set_all_project_modes`, `ide_set_power_save_mode`, `ide_lifecycle_log`, `ide_set_lifecycle_log_file`.

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

- **Build & Deployment via MCP (Verbindlich):**
  - **IDE-interner Build & Diagnostics:** `ide_build_project` aus `intellij-index` (führt einen schnellen Build im IDE-Kontext aus und liefert strukturierte Compiler-Meldungen).
  - **Vollständiger Build & Deployment:** `execute_run_configuration(name: "BUILD", mode: "run")` aus `jetbrains-debugger` (führt die IntelliJ Run Configuration `BUILD` aus, die [`build.ps1`](build.ps1) triggert und das Mod-JAR atomar nach `SERVER/mods` und in das Modrinth-Profil kopiert).
- **Konfiguration des Build-Skripts [`build.ps1`](build.ps1):**
  - Liest `mod_id` aus [`gradle.properties`](gradle.properties).
  - Liest Zielordner aus [`deploy.properties`](deploy.properties).
  - Baut die Mod mit Gradle und kopiert das JAR atomar in Server- und Client-Mods-Ordner.
  - Parameter (bei manuellem Bedarf): `-Reconfigure`, `-Clean`, `-StopDaemons`.
- **IDE Local History:** Bei unerwünschten Dateiänderungen bietet IntelliJ über `Local History > Show History` die Möglichkeit, jeden Zustand sofort per Revert wiederherzustellen.
