# OneShotOneKill — Fabric 26.2

Neuaufsetzung des PvP-Minigames für Minecraft 26.2 auf **Fabric**. Dieses Repository enthält
das aufgesetzte Projektgerüst samt Werkzeug für die API-Recherche. Die Spielinhalte der
[NeoForge-Fassung](https://github.com/Lostpold88/OneShotOneKill-NeoForge-26.2) sind noch nicht
portiert.

---

## Auf einen Blick

| Komponente | Version / Wert |
| :--- | :--- |
| **Minecraft** | 26.2 |
| **Fabric Loader** | 0.19.4 |
| **Fabric API** | 0.158.0+26.2 |
| **Fabric Loom** | 1.17.20 |
| **Mod-Version** | 1.0.0 |
| **Java** | 25 |
| **Gradle** | 9.5.1 |
| **Abhängigkeiten** | keine über Fabric API hinaus |
| **Mixins** | 2 Platzhalter ohne Wirkung |

---

## Stand

**Fertig aufgesetzt:**

- Mod-ID `oneshotonekill`, Package `com.oneshotonekill`, Java 25.
- Geteilte Source-Sets: `src/main/java` läuft auf Server und Client, `src/client/java` nur
  auf dem Client.
- Access Widener (`src/main/resources/oneshotonekill.accesswidener`), in `build.gradle` und
  `fabric.mod.json` angemeldet.
- Einstiegspunkte für Mod, Client und Datengenerierung.
- Build und Deployment über `build.ps1`.

**Noch offen:** Arenen, Spezial-Items, Killstreaks, HUD und Netzwerkschicht der
NeoForge-Fassung.

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

## Bauen & Deployment

```powershell
# Build und automatisches Deployment in Server- und Client-Verzeichnis:
powershell -ExecutionPolicy Bypass -File .\build.ps1

# Oder reiner Gradle-Build:
.\gradlew.bat build
```

Das fertige Jar landet in `build/libs/`. Die Zielordner für das Deployment stehen in
`deploy.properties` und lassen sich mit `.\build.ps1 -Reconfigure` neu setzen.

---

## Projektregeln

Die verbindlichen Regeln für die Arbeit an diesem Projekt stehen in [AGENTS.md](AGENTS.md):
Fabric-API vor Access Widener vor Mixin, Rechercheablauf über `APIS/` mit `rg` und `ast-grep`,
und wann für ein Mixin zusätzlich der Bytecode zu prüfen ist.

---

## Lizenz

MIT
