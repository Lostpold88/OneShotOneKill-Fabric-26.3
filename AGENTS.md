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
>      - **Code-Modifikation & Refactoring (`intellij-index`):** `ide_reformat_code`, `ide_optimize_imports`, `ide_convert_java_to_kotlin`, `ide_edit_member` (unterstützt `target`/`symbolId`, liefert `updatedSymbol`), `ide_insert_member`, `ide_replace_member` (unterstützt `target`/`symbolId`, liefert `updatedSymbol`), `ide_change_signature` (unterstützt `dryRun: true` Preview & `target` für Java/Kotlin JVM, liefert `updatedSymbol`), `ide_structural_search_replace`, `ide_replace_text_in_file`, `ide_refactor_rename` (unterstützt `dryRun: true` Preview, liefert `updatedSymbol`), `ide_refactor_safe_delete` (unterstützt `dryRun: true` Preview, liefert `invalidatedSymbolId`), `ide_move_file` (`destination`).
>      - **Dateien, VFS & Workspace (`intellij-index`):** `ide_create_file` (direkt im VFS anlegen, sofort indiziert), `ide_create_module`, `ide_find_file`, `ide_open_file`, `ide_get_active_file`, `ide_open_project`, `ide_open_workspace`, `ide_close_project`, `ide_reload_project`, `ide_link_build_system`, `ide_import_modules`, `ide_install_plugin`, `ide_restart`.
>      - **Code-Intelligence & Analyse (`intellij-index`):** `ide_symbol_info` (unterstützt `target`/`symbolId`), `ide_file_structure` (unterstützt `includeNodes`, `includeSymbolIds` für PSI-Handles, `maxSymbolIds`), `ide_find_symbol`, `ide_find_class`, `ide_find_definition` (unterstützt `target`/`symbolId`, `fullElementPreview`), `ide_find_references` (unterstützt `target`/`symbolId`, `paths`), `ide_diagnostics`, `ide_project_diagnostics`, `ide_type_hierarchy` (unterstützt `target`/`symbolId`, bounded BFS-Pagination via `maxNodes` & `cursor`), `ide_call_hierarchy` (unterstützt `symbolId`, bounded BFS-Pagination via `maxNodes` & `cursor`), `ide_find_implementations` (unterstützt `target`/`symbolId`), `ide_find_super_methods` (unterstützt `target`/`symbolId`), `ide_search_text` (`regex`, `paths`, `context`).
>      - **Build & Testing (`intellij-index`):** `ide_build_project`, `ide_list_tests`, `ide_run_tests`.
>      - **Index & Dateisystem-Synchronisation (`intellij-index`):** `ide_index_status` (Pre-Flight Check für Smart-/Dumb-Mode), `ide_sync_files` nach jeder externen Dateiänderung aufrufen.
>      - **Lifecycle-Management (`intellij-index`):** `ide_project_status`, `ide_enroll_all_projects`, `ide_release_all_projects`, `ide_release_project`, `ide_get_project_modes`, `ide_set_project_mode`, `ide_set_all_project_modes`, `ide_set_power_save_mode`, `ide_lifecycle_log`, `ide_set_lifecycle_log_file`.
>      - **Client-Start & Runtime-Debugging (`jetbrains-debugger`):** `list_run_configurations`, `execute_run_configuration`, `list_debug_sessions`, `start_debug_session(configuration_name: "Minecraft Client")`, `stop_debug_session`, `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `get_debug_session_status`, `wait_for_pause`, `evaluate_expression`, `resume_execution`, `pause_execution`, `step_over`, `step_into`, `step_out`, `run_to_line`, `jump_to_line`, `get_stack_trace`, `select_stack_frame`, `list_threads`, `get_variables`, `set_variable`, `get_source_context`.
>    - ⛔ **STRIKT VERBOTEN:** 
>      - Verwende **NIEMALS** CLI-Bytecode-Tools wie `javap`, `disassemble` oder Disassembler-Skripte! Alle Typen, Methoden, Parameter und Klassenstrukturen werden ausschließlich semantisch über `intellij-index` (`ide_find_class`, `ide_find_definition`, `ide_symbol_info`, `ide_type_hierarchy` etc.) analysiert.
>      - Verwende **NIEMALS** reine Textsuch-Tools (`grep`, Textsuche) oder Vermutungen, wenn semantische IDE-Index-Tools zur Verfügung stehen.
>      - Führe Datei- und Member-Änderungen bevorzugt über die IDE-Tools (`ide_create_file`, `ide_edit_member`, `ide_insert_member`, `ide_refactor_rename` etc.) aus.
>      - Starte den Client **NIEMALS** ohne Debugger-MCP!
>      - Führe Builds **NIEMALS** über manuelle CLI-Befehle im Terminal aus, wenn die MCP-Tools `ide_build_project` oder `execute_run_configuration` zur Verfügung stehen!

## Kurzfassung

- **IntelliJ IDEA & MCP (`intellij-index`)** sind das **einzige und primäre Werkzeug** für Code-Intelligence, Navigation, Klassenstrukturen, Methodensignaturen, File-Creation, Import-Optimierung, Reformatting, Refactoring und Build (`ide_build_project`). Sämtliche Aktionen werden **ausnahmslos und immer direkt über die MCP-Tools** aufgerufen, wie in [`ide-index-mcp/SKILL.md`](ide-index-mcp/SKILL.md) definiert.
- **JetBrains Debugger MCP (`jetbrains-debugger`)** ist das primäre Werkzeug für interaktives Runtime-Debugging, Haltepunkte, Variableninspektion und Run-Konfigurationen (`execute_run_configuration(name: "BUILD", mode: "run")`). Standard-Aktionen werden **immer direkt über die Debugger-MCP-Tools** aufgerufen, wie in [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md) definiert.
- **Automatisierte Batch-Skripte in [`tools/`](tools/):** Für komplexe Mehrschritt- oder Schleifen-Operationen stehen spezialisierte Automatisierungs-Skripte bereit:
  - [`python tools/mcp_index.py scan-project`](tools/mcp_index.py): High-Performance Batch-Diagnosescan über alle Java-Dateien im Projekt mittels nativer MCP-Batch-API (`files: [...]` in Batches von standardmäßig 25 Dateien [max. 50], Statusauswertung via `fileAnalyses`, Flags: `--severity [all|errors|warnings]`, `--batch-size`, `--max-problems`, `--json`). Prüft das gesamte Projekt (128 Klassen) in nur ca. 42 Sekunden.
  - [`python tools/mcp_index.py sync`](tools/mcp_index.py): VFS-Synchronisation mit Auswertung von `refreshedRoots` und `deletedPaths` (optional `--paths`).
  - [`python tools/mcp_index.py status`](tools/mcp_index.py): Schnelle Abfrage von IDE-Indexierungsstatus und Dumb-Mode (`isDumbMode`, `isIndexing`).
  - [`python tools/mcp_debugger.py clear-all-bp`](tools/mcp_debugger.py): Batch-Abfrage und restloses Löschen aller aktiven Breakpoints in einem Schritt.
- **Fabric-API, Access Widener und Mixins** sind vollwertige Werkzeuge und dürfen jederzeit frei und gezielt nach Zweckmäßigkeit genutzt werden.
- Externe Bibliotheken (Minecraft, Fabric API, Fabric Loader, Sponge Mixin, MixinExtras, Brigadier, Netty, Java SDK etc.) werden direkt über die **IntelliJ IDEA MCP-Engine** (`scope: "project_and_libraries"`) semantisch analysiert.
- Nach externen Datei- oder Strukturänderungen durch Agenten wird das Dateisystem mit der IDE synchronisiert (`ide_sync_files`).
- Zielplattform: **Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Fabric Loom 1.17-SNAPSHOT, Gradle 9.7.1**. Alle Versionen stehen in [`gradle.properties`](gradle.properties).
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
>    - `ide_find_class`: Klassen nach Namen / CamelCase suchen (`scope`: `project_files`, `project_and_libraries`, `project_production_files`, `project_test_files`, `matchMode`: `substring` [Standard], `prefix`, `exact`, `language`, `includeGenerated`, `cursor`, `pageSize`).
>    - `ide_find_definition`: Zur Deklaration / Definition springen (liefert & akzeptiert persistente `symbolId`, verschachteltes `target` [mit `symbolId`, `position` (`file`, `line`, `column`) oder `qualifiedName`+`language`], oder Top-Level `file`+`line`+`column` bzw. `language`+`symbol`; unterstützt `fullElementPreview: true` und `maxPreviewLines`).
>    - `ide_find_references`: Semantische Verwendungsstellen projektweit finden (unterstützt verschachteltes `target`, Top-Level `symbolId`, `file`+`line`+`column` oder `language`+`symbol`, `scope`, Pfad-Filterung via `paths`-Globs wie `["src/**", "!**/*Test.java"]`, `includeGenerated`, `cursor`, `pageSize`).
>    - `ide_find_symbol`: Beliebige Code-Symbole (Methoden, Felder, Klassen) finden (`query`, `scope`, `language`, `includeGenerated`, `cursor`, `pageSize`).
>    - `ide_find_implementations`: Implementierungen von Interfaces & abstrakten Methoden finden (unterstützt verschachteltes `target`, `symbolId`, `file`+`line`+`column` oder `language`+`symbol`, `scope`, `includeGenerated`, `cursor`, `pageSize`).
>    - `ide_find_super_methods`: Basis-/Interface-Methoden ermitteln, die überschrieben werden (liefert & akzeptiert persistente `symbolId` sowie verschachteltes `target`, liefert `symbolId` auch für Basisdeklarationen inkl. externer Bibliotheken/JARs).
>    - `ide_type_hierarchy`: Vollständige Vererbungshierarchie (Super- und Subtypen). Unterstützt verschachteltes `target`, `symbolId`, `className` (FQN), `file`+`line`+`column` oder `language`+`symbol`, `scope`, `includeGenerated` sowie bounded BFS-Pagination via `maxNodes` (1–500, Standard 100) und `cursor`. Liefert `element` (mit optionaler `symbolId`), `supertypes`, `subtypes`, `traversal` mit traversal-lokaler `nodeId`/`parentId`/`depth`, `returnedNodes`, `elapsedMs`, `hasMore`, `cursor`.
>    - `ide_call_hierarchy`: Aufrufhierarchie (`direction`: `callers` / `callees`). Unterstützt `symbolId`, `file`+`line`+`column` oder `language`+`symbol`, `depth` (1–5, Standard 3), `scope`, `includeGenerated` sowie bounded BFS-Pagination via `maxNodes` (1–500, Standard 100) und `cursor`. Liefert `element` (mit optionaler `symbolId`), `calls`, `returnedNodes`, `elapsedMs`, `hasMore`, `cursor`.
>    - `ide_file_structure`: Strukturbaum / Outline einer Datei mit Zeilenangaben & Member-Hierarchie (`file`). Unterstützt strukturierte Deklarationsknoten via `includeNodes: true`, exakte PSI-Element-Handles via `includeSymbolIds: true` (impliziert `includeNodes`) sowie Handle-Budgetierung via `maxSymbolIds` (1–100, Standard 100). Liefert `structure`, `nodes`, `symbolIdsTruncated` und `symbolIdsOmitted`.
>    - `ide_symbol_info`: Voll aufgelöste Typen, Signaturen & JavaDoc-Dokumentation ohne Dateilesen (liefert & akzeptiert persistente `symbolId` sowie verschachteltes `target`, `includeDoc`, `maxDocLength`).
>    - `ide_search_text`: Textsuche / Regex über den IntelliJ-Index (`regex: true` für Regex, `paths`-Globs zur Pfad-Einschränkung wie `["src/**", "!**/*Test.java"]`, `context`: `all`|`code`|`comments`|`strings`, `caseSensitive`, `wholeWord`, `filePattern`, `cursor`, `pageSize`).
>    - `ide_diagnostics`: Compiler-, Syntaxfehler und Quick-Fixes für Einzeldatei (`file`) oder Datei-Batch (`files` bis zu 100 Dateien; empfohlen in Batches von 20–30 Dateien, um Daemon-Locks zu vermeiden, `severity`: `all`|`errors`|`warnings`, `maxProblems`, `includeBuildErrors`, `includeTestResults`, `testResultFilter`, `startLine`/`endLine`, liefert `fileAnalyses`).
>    - `ide_project_diagnostics`: Projektweiter Batch-Diagnose-Scan aller Dateien inkl. ungeöffneter Dateien mit Fail-Closed Coverage (`complete`-Flag, `paths`, `analysisId`-Polling, `waitSeconds`, `maxFiles`, `maxProblems`, `timeoutSeconds`).
>
> 2. **Code-Modifikation & Refactoring:**
>    - `ide_refactor_rename`: Sicheres, semantisches Umbenennen von Symbolen oder Dateien inkl. automatischer Referenzaktualisierung projektweit. Unterstützt non-mutating Dry-Run-Vorschauen (`dryRun: true` zur risikofreien Vorabprüfung von `canApply`, `plannedChange`, `affectedFiles`, `usageCount`, `conflictCount` und `warnings` ohne Dateispeicherungen oder Undo-Eintrag), verschachteltes `target` (`symbolId`, `position` oder `qualifiedName`+`language`), legacy Top-Level Selektoren, `targetType`: `symbol`|`file`, `overrideStrategy` (`rename_base` [Standard, löst Kotlin-Overrides via Light-Method API headless off-EDT auf], `rename_only_current`, `ask`), `relatedRenamingStrategy` (`all` [Standard], `none`, `accessors_and_tests`, `ask`), automatische Kollisionserkennung für Klassen-, Datei- und Ordnernamen, automatische Konstruktorauflösung auf die umgebende Klasse und Rückgabe von `updatedSymbol`.
>    - `ide_refactor_safe_delete`: Sicheres Löschen von Symbolen oder Dateien mit automatischer Verwendungsprüfung (Java/Kotlin). Unterstützt non-mutating Dry-Run-Vorschauen (`dryRun: true` zur risikofreien Vorabprüfung von `canApply`, `plannedChange`, `affectedFiles`, `usageCount`, `conflictCount` und Blockern/Warnungen ohne Dateilöschungen oder Undo-Eintrag; gefundene Verwendungen tragen zu `conflictCount` bei und verhindern `canApply` ohne `force: true`), verschachteltes `target` (`symbolId`, `position` oder `qualifiedName`+`language`), Top-Level `symbolId`, `target_type` (`symbol` [Standard] oder `file`), `force: true` (Löschen trotz Verwendungen erzwingen) und Rückgabe von `invalidatedSymbolId`.
>    - `ide_move_file`: Datei verschieben mit automatischer Package- und Import-Aktualisierung (`file`, `destination` — relatives Zielverzeichnis zum Projekt-Root).
>    - `ide_reformat_code`: Code nach Projekt-Style (.editorconfig / IDE) formatieren (`file`, optional `startLine`/`endLine`, `optimizeImports`, `rearrangeCode`).
>    - `ide_optimize_imports`: Unbenutzte Imports entfernen und sortieren (`file`).
>    - `ide_change_signature`: Methodensignaturen projektweit sicher anpassen für Java-Methoden und Kotlin JVM-Funktionen. Unterstützt non-mutating Dry-Run-Vorschauen (`dryRun: true` zur risikofreien Vorabprüfung von `canApply`, `plannedChange`, `affectedFiles`, `changesCount` und Blockern/Warnungen vor Ausführung), verschachteltes `target` (`symbolId`, `position` oder `qualifiedName`+`language`), legacy Top-Level Selektoren, `newName`, `newReturnType`, `newVisibility` (`public`, `protected`, `private`, `package-private`), `newParameters` (Array von `{oldIndex, name, type, defaultValue}` mit `oldIndex: -1` für neue Parameter), `generateDelegate` und Rückgabe von `updatedSymbol`.
>    - `ide_edit_member`: Vollständiges Member (Signatur + Body) ersetzen. Unterstützt verschachteltes `target` (`symbolId`, `position` oder `qualifiedName`+`language`), Top-Level `symbolId`, legacy Member-Selektoren (`file`, `class`, `member`, `parameterCount`, `line`), `content`, `reformat` und liefert `updatedSymbol`.
>    - `ide_insert_member`: Neues Member (Methode/Feld) strukturiert an Position einfügen (`file`, `class`, `content`, `position`: `first`|`last`|`before`|`after`, `anchor`, `anchorParameterCount`, `anchorLine`, `reformat`).
>    - `ide_replace_member`: Methoden-Body oder Feld-Initializer ersetzen (Signatur bleibt erhalten). Unterstützt verschachteltes `target` (`symbolId`, `position` oder `qualifiedName`+`language`), Top-Level `symbolId`, legacy Member-Selektoren (`file`, `class`, `member`, `parameterCount`, `line`), `content`, `reformat` und liefert `updatedSymbol`.
>    - `ide_replace_text_in_file`: Textersetzung über das IDE-Dokumentenmodell (sofort indexiert, `file`, `searchText`, `replaceText`, `regex`, `caseSensitive`).
>    - `ide_structural_search_replace`: Structural Search and Replace (SSR) mit Pfad-Filterung (`paths`, `searchPattern`, `replacePattern`, `filePattern`, `scope`).
>    - `ide_convert_java_to_kotlin`: Java-Klassen via IntelliJ J2K zu Kotlin konvertieren (`files`).
>
> 3. **Dateien, VFS & Workspace:**
>    - `ide_create_file`: Neue Quellcodedatei direkt im VFS anlegen (`file`, `content`, sofort indexiert).
>    - `ide_create_module`: Neues Modul anlegen (`path`, `name`, `excludes`).
>    - `ide_find_file`: Dateien im Projekt nach Namen suchen (`query`, `scope`, `includeGenerated`, `cursor`, `pageSize`).
>    - `ide_read_file`: Quellcode aus externen JARs / Bibliotheken und Abhängigkeiten lesen (`file` oder `qualifiedName`, `startLine`, `endLine`).
>    - `ide_open_file`: Datei an genauer Zeile/Spalte im Editor öffnen (`file`, `line`, `column`).
>    - `ide_get_active_file`: Aktuell im Editor fokussierte Datei abfragen (liefert Cursorposition und Selektion).
>    - `ide_open_project`: Projekt per absolutem Pfad öffnen und auf Indexierung warten (`path`, `autoLink` [Maven/Gradle-Buildsystem automatisch verlinken, Default: `false`], `excludeDirectories` [Ordner von Indexing/Refactoring ausschließen, z. B. `["wksp", ".claude"]`], `timeoutSeconds`, keine `.idea`-Voraussetzung; liefert Bestätigung inkl. Setup-Details).
>    - `ide_open_workspace`: Maven-Workspace aus Verzeichnis (`path`) oder Modulliste (`modules`) aggregieren (`timeoutSeconds`).
>    - `ide_close_project`: Geöffnetes Projektfenster schließen und Speicher freigeben (`project_path`).
>    - `ide_reload_project`: Maven-/Gradle-Buildmodell asynchron neu laden (`project_path`).
>    - `ide_link_build_system`: Unverlinktes Gradle- oder Maven-Projekt anbinden (`path`).
>    - `ide_import_modules`: Maven-Projektverzeichnisse als Module importieren (`paths`).
>    - `ide_install_plugin`: Plugin-ZIP in die IDE installieren (`path` oder automatische Erkennung aus `build/distributions/*.zip`).
>    - `ide_restart`: IDE neu starten (beendet MCP-Verbindung; danach neu verbinden).
>    - `ide_sync_files`: Virtuelles Dateisystem mit externen Änderungen synchronisieren (unterstützt relative & absolute Pfade in `paths`, flache Refreshes für gelöschte Dateien, liefert `refreshedRoots` und `deletedPaths`).
>    - `ide_index_status`: Indexierungsstatus & Smart-/Dumb-Mode abfragen (`isDumbMode`, `isIndexing`, `indexingProgress`).
>
> 4. **Build & Tests:**
>    - `ide_build_project`: Projekt mit IDE-Build-System bauen und Fehler strukturiert erfassen (unterstützt asynchrones Polling via `buildId` & `waitSeconds`, `rebuild`, `includeRawOutput`, `timeoutSeconds`).
>    - `ide_list_tests`: Alle Unit-/Integrationstests im Projekt auflisten (optional `file`).
>    - `ide_run_tests`: Tests über den IDE-Test-Runner ausführen und auswerten (`target`: bestehender Run-Konfigurationsname oder Java/Kotlin-FQN `com.example.MyTest` bzw. `com.example.MyTest#testFoo`, asynchrones Polling via `runId` & `waitSeconds`, `timeoutSeconds`, `activateToolWindow`).
>
> 5. **Lifecycle Management:**
>    - `ide_project_status`: Projektstatus und Modi aller offenen/verwalteten Projekte anzeigen (Modi: `active`, `background`, `dormant`, `closed`; standardmäßig aktiviert).
>    - `ide_enroll_all_projects`: Alle offenen Projekte im Lifecycle-Manager registrieren.
>    - `ide_release_all_projects`: Alle verwalteten Projekte freigeben.
>    - `ide_release_project`: Einzelnes Projekt aus dem Lifecycle-Manager freigeben.
>    - `ide_get_project_modes`: Aktuelle Modi abfragen.
>    - `ide_set_project_mode`: Modus für ein einzelnes Projekt explizit setzen.
>    - `ide_set_all_project_modes`: Modus für alle Projekte global setzen.
>    - `ide_set_power_save_mode`: Power Save Mode gezielt umschalten.
>    - `ide_lifecycle_log`: Lifecycle-Ereignisprotokoll abrufen.
>    - `ide_set_lifecycle_log_file`: Protokolldatei für Lifecycle-Events konfigurieren.

---

## Runtime-Debugging & Inspektion (JetBrains Debugger MCP)

Die Anbindung an den Debugger erfolgt über das **jetbrains-debugger MCP** (`http://127.0.0.1:29190/debugger-mcp/streamable-http`).

> [!IMPORTANT]
> **Direkte MCP-Nutzung nach [`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md):**
> Bei unklarem Laufzeitverhalten, fehlerhaften Werten, NullPointern oder unvorhergesehenem Kontrollfluss wird nicht im Code geraten, sondern programmatisch über MCP gedebuggt:
> - **[`jetbrains-debugger/SKILL.md`](jetbrains-debugger/SKILL.md):** Umfassender Leitfaden, Debugging-Muster, Pausen-Handling und Best Practices.
> - **[`jetbrains-debugger/references/tool-reference.md`](jetbrains-debugger/references/tool-reference.md):** Vollständige Referenz aller Debugger-Werkzeuge (`start_debug_session`, `set_breakpoint`, `get_debug_session_status`, `wait_for_pause`, `evaluate_expression`, `resume_execution`, `pause_execution`, `step_over`, `step_into`, `step_out`, `run_to_line`, `jump_to_line`, etc.).
> - **Batch-Automatisierung:** Für das restlose Bereinigen aller Breakpoints steht [`tools/mcp_debugger.py`](tools/mcp_debugger.py) mit `python tools/mcp_debugger.py clear-all-bp` zur Verfügung.
>
> ### Vollständige Übersicht aller aktiven MCP-Tools (`jetbrains-debugger`):
>
> 1. **Session & Configuration Management:**
>    - `list_run_configurations`: Alle verfügbaren Run/Debug-Konfigurationen im Projekt auflisten (inkl. `can_debug`-Flag).
>    - `execute_run_configuration`: Run-Konfiguration im Debug- oder Run-Modus ausführen.
>    - `start_debug_session`: Neue Debug-Session für eine Konfiguration starten (z. B. `configuration_name: "Minecraft Client"`).
>    - `stop_debug_session`: Laufende Debug-Session beenden / terminieren (`session_id`).
>    - `list_debug_sessions`: Alle aktiven Debug-Sessions auflisten.
>
> 2. **Breakpoints:**
>    - `set_breakpoint`: Zeilen-Breakpoint mit absolutem Pfad (`file_path`, unterstützt auch JARs via `!/`), 1-basierter Zeile (`line`), optionaler Bedingung (`condition`), Log-Message (`log_message` mit `{expression}`-Platzhaltern für Tracepoints), Suspend-Policy (`suspend_policy`: `"all"`, `"thread"`, `"none"`), `enabled` und `temporary` (Einmal-Breakpoint).
>    - `remove_breakpoint`: Breakpoint anhand der ID entfernen (`breakpoint_id`).
>    - `list_breakpoints`: Alle Breakpoints im Projekt auflisten (liefert ID, Dateipfad, Zeile, Enabled-Status, Suspend-Policy etc.).
>
> 3. **Execution Control & Stepping:**
>    - `resume_execution`: Programmausführung nach Pause fortsetzen.
>    - `pause_execution`: Laufende Programmausführung anhalten.
>    - `wait_for_pause`: Blockierend auf den nächsten Breakpoint oder Step warten (liefert vollen Session-Status inkl. Stack, Variablen & Source; konfigurierbares `timeout`, z. B. 60s; optionales `breakpoint_ids`-Array zum selektiven Warten auf bestimmte Breakpoints; wartet bei weggelassenem `session_id` auch auf das Erscheinen einer Session).
>    - `step_over`: Nächste Zeile ausführen (Funktionsaufrufe überspringen).
>    - `step_into`: In den Funktionsaufruf auf aktueller Zeile hineinspringen.
>    - `step_out`: Ausführung bis zum Verlassen der aktuellen Funktion fortsetzen.
>    - `run_to_line`: Ausführung bis zu einer bestimmten Zielzeile laufen lassen (`file_path`: absolut, `line`: 1-basiert; führt den dazwischenliegenden Code aus).
>    - `jump_to_line`: Pausierten Ausführungspunkt ohne Codeausführung auf eine andere Zeile in der aktuellen Funktion verschieben ("Set Next Statement" / "Jump to Cursor"; überspringt Code dazwischen oder wiederholt frühere Zeilen, z. B. nach `set_variable`; `file_path`: absolut, `line`: 1-basiert; unterstützt für Python/pydevd, andere Debugger wie JVM/Java/Kotlin melden Fehler).
>
> 4. **Inspektion (Stack, Variablen, Threads & Source):**
>    - `get_debug_session_status`: Primäres Inspektions-Tool – bündelt Stack, Variablen, Source und Lokation in einem einzigen Aufruf ohne Wartezeit.
>    - `get_variables`: Variablen im aktuellen oder per `frame_index` ausgewählten Stack-Frame auflisten.
>    - `set_variable`: Variablenwert zur Laufzeit manipulieren (`variable_name`, `new_value`).
>    - `get_stack_trace`: Vollständigen Aufruf-Stack abrufen.
>    - `select_stack_frame`: Stack-Frame per `frame_index` für Variablen- und Expressionskontext auswählen.
>    - `list_threads`: Alle Threads der JVM auflisten.
>    - `get_source_context`: Quellcode-Ausschnitt um eine Zeile / Lokation herum abrufen (`file_path`: absolut oder in JARs via `!/`, `line`, `lines_before`, `lines_after`).
>
> 5. **Expression Evaluation:**
>    - `evaluate_expression`: Beliebige Ausdrücke, Methodenaufrufe und Berechnungen im Kontext des aktuellen Frames auswerten (Sicherheitsregeln beachten: In eingeschränkten Modi keine Template-Strings wie `${...}`).
>
> **Kernregeln für Debugging:**
> 1. **Client-Start via IntelliJ Debugger MCP:** Den Minecraft Client immer über `start_debug_session(configuration_name: "Minecraft Client")` (oder `execute_run_configuration(name: "Minecraft Client", mode: "debug")`) starten, damit die JVM-Instanz dauerhaft im Debugger eingeklinkt ist.
> 2. Breakpoints **vor** dem Auslösen der Aktion setzen (`set_breakpoint`).
> 3. Nach Stepping (`step_over`, `step_into`, etc.) oder `resume_execution` immer mit `wait_for_pause` auf die Pause warten.
> 4. Dateipfade für Breakpoints, `run_to_line`, `jump_to_line` und `get_source_context` müssen **absolut** sein, Zeilennummern **1-basiert** (Unterstützung für JAR-Dateien via `!/`-Separator).
> 5. `run_to_line` führt Code bis zur Zielzeile regulär aus; `jump_to_line` (Set Next Statement) überspringt dazwischenliegenden Code.
> 6. Zur Status- und Variableninspektion primär `get_debug_session_status` nutzen (bündelt Stack, Variablen, Code und Lokation in einem Aufruf).
> 7. Sicherheitsfilter der IDE bei `evaluate_expression`, `condition` und `log_message` beachten (in restriktiven Modi keine Template-Interpolation wie `${...}`).
> 8. Nach Abschluss der Untersuchung Breakpoints mit `python tools/mcp_debugger.py clear-all-bp` aufräumen oder die Session mit `stop_debug_session` beenden.

---

## Fabric-API, Access Widener und Mixins

Fabric-API-Events, Access Widener und Mixins sind vollwertige Werkzeuge und können je nach Zweckmäßigkeit und Sauberkeit frei gewählt und kombiniert werden. **Mixins dürfen ausdrücklich und gerne verwendet werden**, insbesondere für Eingriffe in Rendering, Animationen, Vanilla-Logik oder Methodenflüsse.

### Vorgehen bei der Umsetzung:

1. **API-Prüfung per MCP:** Zielklasse und Methoden in Minecraft/Fabric semantisch über die IDE (`ide_find_class`, `ide_find_definition`, `ide_type_hierarchy`) verifizieren.
2. **Den passenden Weg wählen:**
   - Fabric-API-Events (wenn ein sauberes Callback existiert).
    - Access Widener in [`src/main/resources/oneshotonekill.accesswidener`](src/main/resources/oneshotonekill.accesswidener) (für Sichtbarkeit oder `mutable`).
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
- **Fabric Events:** `Event<T>`-Konstanten mit Callbacks werden per `EVENT.register(...)` abonniert. Das Abbruchverhalten ergibt sich aus dem Rückgabetyp des Callbacks (über MCP prüfbar).
- **Registrierungen:** Erfolgen über Vanilla-Registries mit `Identifier`.
- **Netzwerk:** Payload-Typen registrieren und über die Fabric Networking API (z. B. `ServerPlayNetworking`, `ClientPlayNetworking`) senden.
- **Mixins-Konfiguration:**
  - [`src/main/resources/oneshotonekill.mixins.json`](src/main/resources/oneshotonekill.mixins.json) & [`src/main/resources/oneshotonekill.client.mixins.json`](src/main/resources/oneshotonekill.client.mixins.json)
  - `compatibilityLevel` ist `JAVA_25`, `defaultRequire` ist `1` (Fehler fallen beim Start sofort auf).

---

## Minecraft 26.3 Besonderheiten

- `ResourceLocation` wurde durch `net.minecraft.resources.Identifier` ersetzt.
- `Minecraft.screen` / `setScreen` wurde zu `Minecraft.gui.screen()` / `gui.setScreen()`.
- Farbige Blöcke/Items liegen in `net.minecraft.world.level.block.ColorCollection` mit `pick(DyeColor)`.
- `EntityType.ITEM_DISPLAY` wurde zu `EntityTypes.ITEM_DISPLAY`.
- Portal- und Verzerrungs-Overlay liegen in `net.minecraft.client.gui.Hud`.
- `Entity.syncVelocity` ersetzt `Entity.hurtMarked` für Server-Velocity-Sync.
- `Entity.setPermanentlyInvulnerable` ersetzt `Entity.setInvulnerable`.
- `drop()`-Aufrufe und Item-Drop-Logik erfordern `Prediction.SERVER_ONLY`.
- Client Drop-Mixin zielt auf `MultiPlayerGameMode.dropItem` statt `LocalPlayer.drop`.
- `ItemInHandRenderer` wurde aufgeteilt in `FirstPersonHandsAndItemsMixin` und `FirstPersonHandsAndItemsRendererMixin`.
- `GameRenderer.render()` ist parameterlos.
- `PoseStack.rotate(quaternion)` ersetzt `PoseStack.mulPose(quaternion)`.
- GpuBuffer nutzt RenderPearl-Backend (`com.mojang.renderpearl.api.buffers`).
- `LayerRenderState` Quads nutzen `ItemQuads.split()`.
- SDL3-GUI-Maus-Button- und Tastatur-Handling: Verwendet `InputConstants` (keine rohen GLFW-Konstanten mehr in Screens).
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
