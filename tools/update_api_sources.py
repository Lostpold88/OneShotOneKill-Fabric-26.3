#!/usr/bin/env python3
"""Resolve and unpack Minecraft/Fabric source JARs into ``APIS``.

The script is intentionally project-independent.  It discovers a Fabric Loom
Gradle root, asks that project's Gradle wrapper for the artifacts selected by
the build, and never contains hard-coded Minecraft, Fabric, Loom, or Gradle
versions.

Minecraft sources come from Loom's decompiler (``genSources``); Fabric API,
Fabric Loader and the Mixin implementation are resolved as ``sources`` artifacts
of the dependencies and libraries the build itself uses.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Any, Iterable, Sequence
import uuid
import zipfile


OUTPUT_DIRECTORY = "APIS"
MANIFEST_DIRECTORY = "tools"
MANIFEST_NAME = "SOURCES.json"
COLLECT_TASK_PREFIX = "collectApiSourcesForPython"
MANIFEST_SCHEMA = 2

# A source JAR is classified by the Java packages it actually contains, never by
# a hard-coded artifact name or version.
KIND_PACKAGES: dict[str, tuple[str, ...]] = {
    "minecraft": ("net/minecraft/",),
    "fabric-api": ("net/fabricmc/fabric/",),
    "fabric-loader": ("net/fabricmc/loader/",),
    "mixin": ("org/spongepowered/asm/", "com/llamalad7/mixinextras/"),
}
KINDS: tuple[str, ...] = ("minecraft", "fabric-api", "fabric-loader", "mixin")
REQUIRED_KINDS: tuple[str, ...] = ("minecraft",)

ORIGIN_PRIORITY = {
    "explicit": 60,
    "decompile-task": 50,
    "dependency-sources": 45,
    "loom-cache": 20,
    "gradle-cache": 10,
}


class ApiSourceError(RuntimeError):
    """A user-facing failure while resolving or extracting API sources."""


@dataclasses.dataclass(frozen=True)
class Candidate:
    """One source JAR that might end up in ``APIS``."""

    path: Path
    origin: str
    coordinate: str | None = None


GRADLE_INIT_SCRIPT = r"""
import groovy.json.JsonOutput
import java.util.Locale

def reportPath = System.getProperty("apis.sources.report")
if (reportPath == null || reportPath.trim().isEmpty()) {
    throw new GradleException("Missing -Dapis.sources.report")
}

gradle.projectsEvaluated {
    def root = gradle.rootProject
    def taskName = System.getProperty("apis.sources.task")
    if (taskName == null || !(taskName ==~ /[A-Za-z][A-Za-z0-9]*/)) {
        throw new GradleException("Missing or invalid -Dapis.sources.task")
    }
    if (root.tasks.findByName(taskName) != null) {
        throw new GradleException("Task '${taskName}' already exists")
    }
    def decompile = Boolean.parseBoolean(System.getProperty("apis.sources.decompile", "false"))

    // Loom's decompiler tasks.  Their names differ per decompiler and per split
    // source set, so they are found by type plus the stable lifecycle name.
    def decompileTasks = root.allprojects.collectMany { project ->
        project.tasks.findAll { task -> task.class.name.contains("GenerateSourcesTask") }
    }
    def lifecycleTasks = root.allprojects.collectMany { project ->
        project.tasks.findAll { task -> task.name == "genSources" }
    }
    def triggerTasks = lifecycleTasks.isEmpty() ? decompileTasks : lifecycleTasks

    // Configurations a build script actually declares dependencies in.  Loom's
    // internal library configurations are deliberately left out.
    def declarablePattern = ~/(?i)^[a-z0-9]*(implementation|api|compileonly|compileonlyapi|runtimeonly|localruntime|include)$/

    root.tasks.register(taskName) {
        group = "help"
        description = "Internal temporary task used by update_api_sources.py"
        if (decompile) {
            dependsOn triggerTasks
        }

        doLast {
            def records = []
            def errors = []
            def projects = root.allprojects.collect { project ->
                [path: project.path,
                 projectDir: project.projectDir.absolutePath,
                 buildDir: project.layout.buildDirectory.get().asFile.absolutePath]
            }

            def unwrapFile
            unwrapFile = { value ->
                if (value == null) {
                    return null
                }
                if (value instanceof File) {
                    return value
                }
                if (value instanceof org.gradle.api.file.FileSystemLocation) {
                    return value.asFile
                }
                if (value instanceof org.gradle.api.provider.Provider) {
                    try {
                        return value.isPresent() ? unwrapFile(value.get()) : null
                    } catch (Exception ignored) {
                        return null
                    }
                }
                return null
            }

            def addJar = { File file, String origin, String projectPath, String detail, String coordinate ->
                if (file == null) {
                    return
                }
                def name = file.name.toLowerCase(Locale.ROOT)
                if (!name.endsWith(".jar")) {
                    return
                }
                def sources = name.endsWith("-sources.jar")
                    ? file
                    : new File(file.parentFile, file.name.replaceFirst(/(?i)\.jar$/, "-sources.jar"))
                if (!sources.isFile()) {
                    return
                }
                records << [path: sources.absolutePath,
                            origin: origin,
                            project: projectPath,
                            detail: detail,
                            coordinate: coordinate]
            }

            // 1. Decompiled Minecraft sources.  Loom writes them next to the
            //    mapped Minecraft JAR the decompiler task consumes.
            decompileTasks.each { task ->
                try {
                    task.outputs.files.files.each { file ->
                        addJar(file, "decompile-task", task.project.path, task.path, null)
                    }
                } catch (Exception failure) {
                    errors << [scope: task.path, message: failure.message ?: failure.class.name]
                }
                ["inputJar", "outputJar", "sourcesOutputJar", "runtimeJar"].each { property ->
                    try {
                        if (task.hasProperty(property)) {
                            addJar(unwrapFile(task."${property}"), "decompile-task", task.project.path,
                                   "${task.path}.${property}", null)
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // 2. ``sources`` artifacts of the declared external dependencies,
            //    which is where Fabric API and Fabric Loader come from.
            def coordinates = new LinkedHashSet<String>()
            root.allprojects.each { project ->
                project.configurations.each { configuration ->
                    if (!(configuration.name ==~ declarablePattern)) {
                        return
                    }
                    try {
                        configuration.dependencies.each { dependency ->
                            if (dependency instanceof org.gradle.api.artifacts.ExternalModuleDependency
                                    && dependency.group != null && dependency.version != null) {
                                coordinates << "${dependency.group}:${dependency.name}:${dependency.version}".toString()
                            }
                        }
                    } catch (Exception failure) {
                        errors << [scope: "${project.path}:${configuration.name}",
                                   message: failure.message ?: failure.class.name]
                    }
                }
            }

            // An aggregate artifact such as Fabric API ships its code in module
            // artifacts of the same group, so the graph is expanded within that
            // group.  Foreign groups stay untouched.
            def sourceCoordinates = new LinkedHashSet<String>(coordinates)
            coordinates.each { coordinate ->
                def group = coordinate.split(":")[0]
                try {
                    def graph = root.configurations.detachedConfiguration(root.dependencies.create(coordinate))
                    graph.transitive = true
                    graph.incoming.resolutionResult.allComponents.each { component ->
                        def id = component.id
                        if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
                                && id.group == group) {
                            sourceCoordinates << "${id.group}:${id.module}:${id.version}".toString()
                        }
                    }
                } catch (Exception failure) {
                    errors << [scope: coordinate, message: failure.message ?: failure.class.name]
                }
            }

            // Bibliotheken, die der Build selbst auf den Klassenpfad legt, etwa die
            // Mixin-Implementierung des Loaders.  Es wird nur nach ausgewählten Namen
            // gefragt, damit nicht die komplette Bibliotheksliste geladen wird.
            def extraModulePattern = ~/(?i).*(mixin).*/
            root.allprojects.each { project ->
                project.configurations.each { configuration ->
                    if (!configuration.canBeResolved) {
                        return
                    }
                    def name = configuration.name.toLowerCase(Locale.ROOT)
                    if (!name.endsWith("compileclasspath") && !name.endsWith("runtimeclasspath")) {
                        return
                    }
                    try {
                        configuration.incoming.resolutionResult.allComponents.each { component ->
                            def id = component.id
                            if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier
                                    && (id.group ==~ extraModulePattern || id.module ==~ extraModulePattern)) {
                                sourceCoordinates << "${id.group}:${id.module}:${id.version}".toString()
                            }
                        }
                    } catch (Exception failure) {
                        errors << [scope: "${project.path}:${configuration.name}",
                                   message: failure.message ?: failure.class.name]
                    }
                }
            }

            sourceCoordinates.each { coordinate ->
                try {
                    def dependency = root.dependencies.create("${coordinate}:sources@jar")
                    def detached = root.configurations.detachedConfiguration(dependency)
                    detached.transitive = false
                    detached.incoming.artifactView { view -> view.lenient = true }.artifacts.each { artifact ->
                        addJar(artifact.file, "dependency-sources", root.path, "sources artifact", coordinate)
                    }
                } catch (Exception failure) {
                    errors << [scope: coordinate, message: failure.message ?: failure.class.name]
                }
            }

            // 3. The Minecraft version the build selected.  It keeps stale
            //    decompiler output of other versions out of the selection.
            def minecraftDependencies = []
            root.allprojects.each { project ->
                def configuration = project.configurations.findByName("minecraft")
                if (configuration == null) {
                    return
                }
                try {
                    configuration.dependencies.each { dependency ->
                        if (dependency.version != null) {
                            minecraftDependencies << [group: dependency.group,
                                                      name: dependency.name,
                                                      version: dependency.version]
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            def report = new File(reportPath)
            report.parentFile.mkdirs()
            report.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                files: records,
                errors: errors,
                projects: projects,
                decompileTasks: decompileTasks.collect { task -> task.path },
                declaredCoordinates: coordinates as List,
                minecraftDependencies: minecraftDependencies,
                decompiled: decompile
            ])), "UTF-8")
        }
    }
}
"""


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Ermittelt die vom Fabric-Loom-Projekt verwendeten Minecraft-, Fabric-API- und "
            "Fabric-Loader-Source-JARs und entpackt sie atomar nach APIS/."
        )
    )
    parser.add_argument(
        "--project",
        type=Path,
        help="Projekt oder Unterordner davon (Standard: automatische Erkennung).",
    )
    parser.add_argument(
        "--minecraft-jar",
        type=Path,
        action="append",
        default=[],
        dest="minecraft_jars",
        help="Minecraft-Source-JAR explizit vorgeben (mehrfach erlaubt, etwa common und clientOnly).",
    )
    parser.add_argument(
        "--fabric-api-jar",
        type=Path,
        action="append",
        default=[],
        dest="fabric_api_jars",
        help="Fabric-API-Source-JAR explizit vorgeben.",
    )
    parser.add_argument(
        "--mixin-jar",
        type=Path,
        action="append",
        default=[],
        dest="mixin_jars",
        help="Mixin-Source-JAR explizit vorgeben.",
    )
    parser.add_argument(
        "--fabric-loader-jar",
        type=Path,
        action="append",
        default=[],
        dest="fabric_loader_jars",
        help="Fabric-Loader-Source-JAR explizit vorgeben.",
    )
    parser.add_argument(
        "--no-gradle",
        action="store_true",
        help="Gradle nicht starten; nur explizite und bereits vorhandene JARs verwenden.",
    )
    parser.add_argument(
        "--decompile",
        dest="decompile",
        action="store_true",
        default=None,
        help="Minecraft in jedem Fall neu dekompilieren lassen (genSources).",
    )
    parser.add_argument(
        "--no-decompile",
        dest="decompile",
        action="store_false",
        help="Nie dekompilieren; fehlende Minecraft-Sources sind dann ein Fehler.",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="Gradle im Offline-Modus starten.",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="Gradle-Abhängigkeiten neu prüfen (--refresh-dependencies).",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="APIS/ auch bei unveränderten JARs neu erzeugen.",
    )
    return parser.parse_args(argv)


def _is_gradle_root(path: Path) -> bool:
    wrapper = (path / "gradlew").is_file() or (path / "gradlew.bat").is_file()
    settings = (path / "settings.gradle").is_file() or (path / "settings.gradle.kts").is_file()
    build = (path / "build.gradle").is_file() or (path / "build.gradle.kts").is_file()
    return wrapper and (settings or build)


def find_project_root(explicit: Path | None, script_path: Path | None = None) -> Path:
    starts = [explicit] if explicit is not None else [Path.cwd(), script_path or Path(__file__)]
    checked: set[Path] = set()
    for start in starts:
        candidate = start.expanduser().resolve()
        if candidate.is_file():
            candidate = candidate.parent
        for directory in (candidate, *candidate.parents):
            if directory in checked:
                continue
            checked.add(directory)
            if _is_gradle_root(directory):
                return directory
    location = f" ab {explicit}" if explicit is not None else ""
    raise ApiSourceError(f"Keine Gradle-Projektwurzel mit Wrapper gefunden{location}.")


def gradle_command(project_root: Path) -> list[str]:
    if os.name == "nt" and (project_root / "gradlew.bat").is_file():
        return [str(project_root / "gradlew.bat")]
    wrapper = project_root / "gradlew"
    if wrapper.is_file():
        if os.access(wrapper, os.X_OK):
            return [str(wrapper)]
        shell = shutil.which("sh")
        if shell:
            return [shell, str(wrapper)]
    installed = shutil.which("gradle")
    if installed:
        return [installed]
    raise ApiSourceError("Weder ein ausführbarer Gradle-Wrapper noch 'gradle' wurde gefunden.")


def run_gradle_collector(
    project_root: Path,
    *,
    decompile: bool,
    offline: bool = False,
    refresh: bool = False,
) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="api-sources-gradle-") as temporary:
        temporary_dir = Path(temporary)
        init_script = temporary_dir / "collect-api-sources.gradle"
        report = temporary_dir / "report.json"
        init_script.write_text(GRADLE_INIT_SCRIPT, encoding="utf-8")
        task_name = f"{COLLECT_TASK_PREFIX}{uuid.uuid4().hex}"

        command = gradle_command(project_root)
        command.extend(
            [
                "--init-script",
                str(init_script),
                "--no-configuration-cache",
                "--console=plain",
                f"-Dapis.sources.report={report}",
                f"-Dapis.sources.task={task_name}",
                f"-Dapis.sources.decompile={'true' if decompile else 'false'}",
            ]
        )
        if offline:
            command.append("--offline")
        if refresh:
            command.append("--refresh-dependencies")
        command.append(task_name)

        if decompile:
            print("Gradle dekompiliert Minecraft (genSources). Das dauert beim ersten Mal einige Minuten ...")
        else:
            print("Gradle löst die zum Projekt passenden API-Sources auf ...")
        try:
            result = subprocess.run(command, cwd=project_root, check=False)
        except OSError as failure:
            raise ApiSourceError(f"Gradle konnte nicht gestartet werden: {failure}") from failure
        if result.returncode != 0:
            raise ApiSourceError(f"Gradle ist mit Exitcode {result.returncode} fehlgeschlagen.")
        if not report.is_file():
            raise ApiSourceError("Gradle war erfolgreich, hat aber keinen Source-Bericht erzeugt.")
        try:
            payload = json.loads(report.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as failure:
            raise ApiSourceError(f"Der Gradle-Source-Bericht ist ungültig: {failure}") from failure
        if not isinstance(payload, dict):
            raise ApiSourceError("Der Gradle-Source-Bericht hat ein unerwartetes Format.")
        return payload


def _existing_file(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise ApiSourceError(f"{label} existiert nicht oder ist keine Datei: {resolved}")
    return resolved


def _gradle_user_homes(project_root: Path) -> list[Path]:
    roots: list[Path] = []
    configured = os.environ.get("GRADLE_USER_HOME")
    if configured:
        candidate = Path(configured).expanduser()
        if candidate.is_absolute():
            roots.append(candidate)
        else:
            # GRADLE_USER_HOME may be relative and is then resolved per process.
            roots.append(project_root / candidate)
            roots.append(Path.cwd() / candidate)
    roots.append(Path.home() / ".gradle")
    roots.append(project_root / ".gradle")
    unique: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        try:
            resolved = root.resolve()
        except OSError:
            continue
        if resolved not in seen:
            seen.add(resolved)
            unique.append(resolved)
    return unique


def discover_fallback_jars(project_root: Path, project_dirs: Iterable[Path]) -> list[Candidate]:
    """Find source JARs without Gradle, for example for ``--no-gradle`` runs."""

    found: list[Candidate] = []
    for directory in {project_root, *(path.resolve() for path in project_dirs)}:
        loom_cache = directory / ".gradle" / "loom-cache"
        if loom_cache.is_dir():
            found.extend(
                Candidate(path.resolve(), "loom-cache") for path in loom_cache.rglob("*-sources.jar")
            )

    for home in _gradle_user_homes(project_root):
        loom_cache = home / "caches" / "fabric-loom"
        if loom_cache.is_dir():
            found.extend(
                Candidate(path.resolve(), "loom-cache") for path in loom_cache.rglob("*-sources.jar")
            )
        modules = home / "caches" / "modules-2" / "files-2.1"
        if modules.is_dir():
            found.extend(
                Candidate(path.resolve(), "gradle-cache")
                for path in modules.glob("*/*/*/*/*-sources.jar")
            )
    return found


def jar_kinds(path: Path) -> set[str]:
    """Classify a source JAR by the Java packages it contains."""

    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    except (OSError, zipfile.BadZipFile) as failure:
        raise ApiSourceError(f"Ungültiges Source-JAR {path}: {failure}") from failure

    kinds: set[str] = set()
    for kind, prefixes in KIND_PACKAGES.items():
        if any(name.startswith(prefixes) and name.endswith(".java") for name in names):
            kinds.add(kind)

    lower_name = path.name.casefold()
    for kind in kinds:
        if lower_name.startswith(f"{kind}-"):
            return {kind}
    return kinds


def _version_text(path: Path, coordinate: str | None = None) -> str:
    if coordinate is not None and coordinate.count(":") >= 2:
        return coordinate.split(":", 2)[2]
    stem = re.sub(r"(?i)-sources\.jar$", "", path.name)
    for token in reversed(stem.split("-")):
        if token and token[0].isdigit():
            return token
    return stem


def _module_key(path: Path, coordinate: str | None) -> str:
    """Identify the artifact a JAR belongs to, ignoring its version."""

    if coordinate is not None and coordinate.count(":") >= 2:
        group, name, _ = coordinate.split(":", 2)
        return f"{group}:{name}".casefold()
    stem = re.sub(r"(?i)-sources\.jar$", "", path.name)
    version = _version_text(path)
    if stem.endswith(f"-{version}"):
        stem = stem[: -len(version) - 1]
    return stem.casefold()


def _natural_version_key(version: str) -> tuple[tuple[int, int | str], ...]:
    return tuple(
        (1, int(token)) if token.isdigit() else (0, token.casefold())
        for token in re.findall(r"\d+|[A-Za-z]+", version)
    )


def _rank(candidate: Candidate) -> tuple[Any, ...]:
    return (
        _natural_version_key(_version_text(candidate.path, candidate.coordinate)),
        candidate.path.stat().st_mtime_ns,
        str(candidate.path),
    )


def choose_jars(
    candidates: Iterable[Candidate],
    explicit: dict[str, Sequence[Path]] | None = None,
    minecraft_version: str | None = None,
) -> dict[str, list[Candidate]]:
    """Pick the source JARs per kind, keeping split Minecraft JARs together."""

    explicit = explicit or {}
    kinds_by_path: dict[Path, set[str]] = {}
    by_kind: dict[str, dict[Path, Candidate]] = {kind: {} for kind in KINDS}

    for candidate in candidates:
        path = candidate.path.expanduser().resolve()
        if not path.is_file() or not path.name.casefold().endswith("-sources.jar"):
            continue
        if path not in kinds_by_path:
            kinds_by_path[path] = jar_kinds(path)
        for kind in kinds_by_path[path]:
            if explicit.get(kind):
                continue
            previous = by_kind[kind].get(path)
            if previous is None or ORIGIN_PRIORITY.get(candidate.origin, 0) > ORIGIN_PRIORITY.get(
                previous.origin, 0
            ):
                by_kind[kind][path] = dataclasses.replace(candidate, path=path)

    for kind, paths in explicit.items():
        if kind not in by_kind:
            raise ApiSourceError(f"Unbekannte Source-Art: {kind}")
        for raw_path in paths:
            path = _existing_file(raw_path, f"Explizites {kind}-JAR")
            if kind not in jar_kinds(path):
                raise ApiSourceError(
                    f"Das explizite {kind}-JAR enthält keine passenden Java-Quellen: {path}"
                )
            by_kind[kind][path] = Candidate(path, "explicit")

    selected: dict[str, list[Candidate]] = {}
    for kind in KINDS:
        found = list(by_kind[kind].values())
        if not found:
            continue

        if kind == "minecraft" and minecraft_version and not any(c.origin == "explicit" for c in found):
            matching = [
                candidate
                for candidate in found
                if minecraft_version in candidate.path.name or minecraft_version in candidate.path.parts
            ]
            if matching:
                found = matching

        best = max(ORIGIN_PRIORITY.get(candidate.origin, 0) for candidate in found)
        found = [candidate for candidate in found if ORIGIN_PRIORITY.get(candidate.origin, 0) == best]

        # One JAR per artifact: a split-environment build contributes several
        # Minecraft artifacts (common, clientOnly), an outdated build does not.
        newest: dict[str, Candidate] = {}
        for candidate in found:
            key = _module_key(candidate.path, candidate.coordinate)
            current = newest.get(key)
            if current is None or _rank(candidate) > _rank(current):
                newest[key] = candidate
        selected[kind] = sorted(newest.values(), key=lambda candidate: str(candidate.path))

    for kind in REQUIRED_KINDS:
        if not selected.get(kind):
            raise ApiSourceError(
                f"Kein {kind}-Source-JAR gefunden. Prüfe die Loom-Konfiguration, führe das Skript "
                "ohne --no-gradle aus oder gib das JAR explizit an."
            )
    return selected


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe_member_path(name: str) -> PurePosixPath:
    if "\x00" in name:
        raise ApiSourceError("Ein Source-JAR enthält einen Dateinamen mit NUL-Byte.")
    normalized = name.replace("\\", "/")
    member = PurePosixPath(normalized)
    if not member.parts or member.is_absolute() or any(part in {"", ".", ".."} for part in member.parts):
        raise ApiSourceError(f"Unsicherer Pfad im Source-JAR: {name!r}")
    if member.parts and re.match(r"^[A-Za-z]:", member.parts[0]):
        raise ApiSourceError(f"Absoluter Windows-Pfad im Source-JAR: {name!r}")
    if any(":" in part for part in member.parts):
        raise ApiSourceError(f"Doppelpunkt im Pfad eines Source-JARs: {name!r}")
    return member


def extract_source_jar(
    jar: Path, destination: Path, seen: dict[str, tuple[int, int]] | None = None
) -> tuple[int, int]:
    """Extract one JAR; ``seen`` merges several JARs into one directory."""

    seen = {} if seen is None else seen
    file_count = 0
    byte_count = 0
    try:
        with zipfile.ZipFile(jar) as archive:
            for info in archive.infolist():
                member = _safe_member_path(info.filename)
                member_key = os.path.normcase(str(member))
                mode = (info.external_attr >> 16) & 0o170000
                if mode == stat.S_IFLNK:
                    raise ApiSourceError(f"Symbolischer Link im Source-JAR {jar}: {info.filename}")

                target = destination.joinpath(*member.parts)
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                if member_key in seen:
                    if seen[member_key] == (info.file_size, info.CRC):
                        continue
                    # Beipack wie META-INF/MANIFEST.MF, fabric.mod.json oder LICENSE
                    # unterscheidet sich je Modul-JAR; eindeutig sein muss nur der Quelltext.
                    if member.name.casefold().endswith(".java"):
                        raise ApiSourceError(
                            f"Widersprüchlicher Quelltext {info.filename} in {jar}: Ein anderes "
                            "Source-JAR liefert denselben Pfad mit anderem Inhalt."
                        )
                    continue
                seen[member_key] = (info.file_size, info.CRC)
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info, "r") as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                file_count += 1
                byte_count += info.file_size
    except ApiSourceError:
        raise
    except (OSError, zipfile.BadZipFile) as failure:
        raise ApiSourceError(f"Ungültiges Source-JAR {jar}: {failure}") from failure
    return file_count, byte_count


def _display_source_path(path: Path, project_root: Path) -> str:
    try:
        return str(path.relative_to(project_root))
    except ValueError:
        return str(path)


def _manifest_jars(candidates: Iterable[Candidate], hashes: dict[Path, str], project_root: Path) -> list[dict[str, str]]:
    return [
        {
            "path": _display_source_path(candidate.path, project_root),
            "version": _version_text(candidate.path, candidate.coordinate),
            "sha256": hashes[candidate.path],
        }
        for candidate in candidates
    ]


def _manifest_matches(
    output: Path,
    manifest: Path,
    jars: dict[str, list[Candidate]],
    hashes: dict[Path, str],
    project_root: Path,
) -> bool:
    if not manifest.is_file():
        return False
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if data.get("schema") != MANIFEST_SCHEMA:
            return False
        artifacts = data["artifacts"]
        if set(artifacts) != set(jars):
            return False
        for kind, candidates in jars.items():
            directory = output / kind
            if not directory.is_dir():
                return False
            if artifacts[kind]["sourceJars"] != _manifest_jars(candidates, hashes, project_root):
                return False
            expected_files = artifacts[kind]["files"]
            actual_files = sum(1 for path in directory.rglob("*") if path.is_file())
            if not isinstance(expected_files, int) or actual_files != expected_files:
                return False
        return True
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return False


def install_apis(
    project_root: Path,
    jars: dict[str, list[Candidate]],
    versions: dict[str, str] | None = None,
    *,
    force: bool = False,
) -> bool:
    project_root = project_root.resolve()
    versions = versions or {}
    output = project_root / OUTPUT_DIRECTORY
    manifest_directory = project_root / MANIFEST_DIRECTORY
    manifest = manifest_directory / MANIFEST_NAME
    if output.parent != project_root or output.name != OUTPUT_DIRECTORY:
        raise ApiSourceError("Interner Sicherheitsfehler: ungültiges APIS-Ziel.")
    if manifest_directory.parent != project_root or manifest_directory.name != MANIFEST_DIRECTORY:
        raise ApiSourceError("Interner Sicherheitsfehler: ungültiges Manifest-Ziel.")
    if output.is_symlink():
        raise ApiSourceError("APIS ist ein symbolischer Link und wird aus Sicherheitsgründen nicht ersetzt.")
    if output.exists() and not output.is_dir():
        raise ApiSourceError("APIS existiert, ist aber kein Verzeichnis.")
    if manifest_directory.is_symlink():
        raise ApiSourceError("tools ist ein symbolischer Link und wird aus Sicherheitsgründen nicht verwendet.")
    if manifest_directory.exists() and not manifest_directory.is_dir():
        raise ApiSourceError("tools existiert, ist aber kein Verzeichnis.")
    if manifest.is_symlink():
        raise ApiSourceError("tools/SOURCES.json ist ein symbolischer Link und wird nicht ersetzt.")
    if manifest.exists() and not manifest.is_file():
        raise ApiSourceError("tools/SOURCES.json existiert, ist aber keine Datei.")

    hashes = {
        candidate.path: sha256(candidate.path)
        for candidates in jars.values()
        for candidate in candidates
    }
    if not force and _manifest_matches(output, manifest, jars, hashes, project_root):
        print(f"APIS ist bereits aktuell: {output}")
        return False

    manifest_directory.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".APIS-new-", dir=project_root))
    transaction_id = uuid.uuid4().hex
    output_backup = project_root / f".APIS-backup-{transaction_id}"
    manifest_temporary = manifest_directory / f".SOURCES-new-{transaction_id}.json"
    manifest_backup = manifest_directory / f".SOURCES-backup-{transaction_id}.json"
    output_old_moved = False
    output_installed = False
    manifest_old_moved = False
    manifest_installed = False
    try:
        manifest_artifacts: dict[str, dict[str, Any]] = {}
        for kind in KINDS:
            candidates = jars.get(kind)
            if not candidates:
                continue
            seen: dict[str, tuple[int, int]] = {}
            count = 0
            size = 0
            for candidate in candidates:
                print(f"Entpacke {kind}: {candidate.path}")
                files, uncompressed = extract_source_jar(candidate.path, temporary / kind, seen)
                count += files
                size += uncompressed
            manifest_jars = _manifest_jars(candidates, hashes, project_root)
            own_versions = {entry["version"] for entry in manifest_jars}
            artifact: dict[str, Any] = {
                "sourceJars": manifest_jars,
                "files": count,
                "uncompressedBytes": size,
            }
            # Ein Bereich bekommt nur dann eine eigene Version, wenn sie eindeutig ist.
            version = versions.get(kind) or (own_versions.pop() if len(own_versions) == 1 else None)
            if version is not None:
                artifact = {"version": version, **artifact}
            manifest_artifacts[kind] = artifact

        manifest_data = {
            "schema": MANIFEST_SCHEMA,
            "generatedBy": Path(__file__).name,
            "platform": "fabric",
            "artifacts": manifest_artifacts,
        }
        manifest_temporary.write_text(
            json.dumps(manifest_data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )

        if output.exists():
            output.replace(output_backup)
            output_old_moved = True
        if manifest.exists():
            manifest.replace(manifest_backup)
            manifest_old_moved = True
        temporary.replace(output)
        output_installed = True
        manifest_temporary.replace(manifest)
        manifest_installed = True
        if output_old_moved:
            try:
                shutil.rmtree(output_backup)
            except OSError as failure:
                print(f"Warnung: Alter APIS-Stand konnte nicht entfernt werden: {failure}", file=sys.stderr)
        if manifest_old_moved:
            try:
                manifest_backup.unlink()
            except OSError as failure:
                print(f"Warnung: Altes Source-Manifest konnte nicht entfernt werden: {failure}", file=sys.stderr)
        print(f"APIS wurde atomar aktualisiert: {output}")
        print(f"Source-Manifest wurde aktualisiert: {manifest}")
        return True
    except BaseException:
        if temporary.exists():
            shutil.rmtree(temporary, ignore_errors=True)
        if manifest_temporary.exists():
            manifest_temporary.unlink(missing_ok=True)
        if manifest_installed and manifest.exists():
            manifest.unlink(missing_ok=True)
        if manifest_old_moved and manifest_backup.exists() and not manifest.exists():
            manifest_backup.replace(manifest)
        if output_installed and output.exists():
            shutil.rmtree(output, ignore_errors=True)
        if output_old_moved and output_backup.exists() and not output.exists():
            output_backup.replace(output)
        raise


def _report_candidates(payload: dict[str, Any]) -> tuple[list[Candidate], list[Path]]:
    candidates: list[Candidate] = []
    for record in payload.get("files", []):
        if isinstance(record, dict) and isinstance(record.get("path"), str):
            coordinate = record.get("coordinate")
            candidates.append(
                Candidate(
                    Path(record["path"]),
                    str(record.get("origin", "gradle")),
                    coordinate if isinstance(coordinate, str) else None,
                )
            )
    project_dirs = [
        Path(record["projectDir"])
        for record in payload.get("projects", [])
        if isinstance(record, dict) and isinstance(record.get("projectDir"), str)
    ]
    return candidates, project_dirs


def _declared_versions_by_group(payload: dict[str, Any]) -> dict[str, str]:
    """Map a group to its declared version, if the build declares exactly one."""

    groups: dict[str, set[str]] = {}
    for coordinate in payload.get("declaredCoordinates", []):
        if not isinstance(coordinate, str) or coordinate.count(":") < 2:
            continue
        group, _, version = coordinate.split(":", 2)
        groups.setdefault(group, set()).add(version)
    return {group: versions.pop() for group, versions in groups.items() if len(versions) == 1}


def _aggregate_version(candidates: Sequence[Candidate], declared: dict[str, str]) -> str | None:
    """Version of an aggregate such as Fabric API, whose modules carry own versions."""

    if len(candidates) < 2:
        return None
    groups = {
        candidate.coordinate.split(":", 1)[0] for candidate in candidates if candidate.coordinate
    }
    if len(groups) != 1:
        return None
    return declared.get(groups.pop())


def _minecraft_version(payload: dict[str, Any]) -> str | None:
    for record in payload.get("minecraftDependencies", []):
        if isinstance(record, dict) and isinstance(record.get("version"), str):
            return record["version"]
    return None


def _print_report_errors(payload: dict[str, Any]) -> None:
    for error in payload.get("errors", []):
        if isinstance(error, dict):
            print(
                f"Gradle-Hinweis: {error.get('scope', '?')} konnte nicht aufgelöst werden: "
                f"{error.get('message', '?')}",
                file=sys.stderr,
            )


def _has_kind(candidates: Iterable[Candidate], kind: str) -> bool:
    for candidate in candidates:
        path = candidate.path.expanduser()
        if path.is_file() and kind in jar_kinds(path):
            return True
    return False


def _explicit_jars(args: argparse.Namespace) -> dict[str, list[Path]]:
    return {
        "minecraft": list(args.minecraft_jars),
        "fabric-api": list(args.fabric_api_jars),
        "fabric-loader": list(args.fabric_loader_jars),
        "mixin": list(args.mixin_jars),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.no_gradle and args.offline:
        raise ApiSourceError("--offline hat zusammen mit --no-gradle keine Wirkung.")
    if args.no_gradle and args.refresh:
        raise ApiSourceError("--refresh kann nicht zusammen mit --no-gradle verwendet werden.")
    if args.no_gradle and args.decompile:
        raise ApiSourceError("--decompile kann nicht zusammen mit --no-gradle verwendet werden.")

    project_root = find_project_root(args.project)
    print(f"Projekt: {project_root}")

    explicit = _explicit_jars(args)
    candidates: list[Candidate] = []
    project_dirs: list[Path] = []
    minecraft_version: str | None = None
    declared_versions: dict[str, str] = {}

    if not args.no_gradle:
        decompile = bool(args.decompile)
        report = run_gradle_collector(
            project_root, decompile=decompile, offline=args.offline, refresh=args.refresh
        )
        candidates, project_dirs = _report_candidates(report)
        minecraft_version = _minecraft_version(report)
        declared_versions = _declared_versions_by_group(report)
        _print_report_errors(report)
        if not report.get("decompileTasks"):
            print(
                "Hinweis: Das Projekt bietet keine Loom-Dekompilier-Task; vorhandene Source-JARs "
                "werden als Fallback gesucht.",
                file=sys.stderr,
            )

        # Ohne vorhandene Minecraft-Sources wird einmalig dekompiliert.
        if not decompile and args.decompile is None and not explicit["minecraft"]:
            if not _has_kind(candidates, "minecraft"):
                report = run_gradle_collector(
                    project_root, decompile=True, offline=args.offline, refresh=args.refresh
                )
                candidates, project_dirs = _report_candidates(report)
                minecraft_version = _minecraft_version(report) or minecraft_version
                declared_versions = _declared_versions_by_group(report) or declared_versions
                _print_report_errors(report)

    candidates.extend(discover_fallback_jars(project_root, project_dirs))
    jars = choose_jars(candidates, explicit, minecraft_version)

    versions: dict[str, str] = {}
    if minecraft_version:
        versions["minecraft"] = minecraft_version
    for kind, selected in jars.items():
        aggregate = _aggregate_version(selected, declared_versions)
        if aggregate and kind not in versions:
            versions[kind] = aggregate
    for kind in KINDS:
        for candidate in jars.get(kind, ()):
            print(f"{kind}: {candidate.path}")
        if not jars.get(kind):
            print(f"Hinweis: Kein {kind}-Source-JAR gefunden; APIS/{kind} entfällt.", file=sys.stderr)

    install_apis(project_root, jars, versions, force=args.force)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ApiSourceError as failure:
        print(f"Fehler: {failure}", file=sys.stderr)
        raise SystemExit(1)
