#!/usr/bin/env python3
"""
IntelliJ Index MCP Automation Script
Provides modern batch & automated workflows leveraging the latest IntelliJ Index MCP features.
For standard single operations (find-class, find-def, find-refs, symbol-info, etc.),
call the MCP tools directly via Antigravity MCP tooling as instructed in SKILL.md.

Automated Workflows:
- scan-project: High-performance batch diagnostic scan over all source files using native 'files' batching
                with automatic IDE editor tab opening (open_daemon highlighting) and active editor state restoration.
- sync: Force sync IDE Virtual File System with disk changes (including refreshedRoots & deletedPaths).
- status: Query IDE indexing status (dumb mode, progress).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Optional

ENDPOINT = os.environ.get("INDEX_MCP_ENDPOINT", "http://127.0.0.1:29170/index-mcp/streamable-http")
DEFAULT_PROJECT_PATH = "E:/OneShotOneKill"


def call_index_mcp(
    tool_name: str,
    arguments: Optional[dict] = None,
    project_path: str = DEFAULT_PROJECT_PATH,
    timeout: float = 60.0,
) -> dict:
    """Sends a JSON-RPC tool call to the IntelliJ Index MCP server."""
    if arguments is None:
        arguments = {}
    if "project_path" not in arguments and project_path:
        arguments["project_path"] = project_path

    payload = {
        "jsonrpc": "2.0",
        "id": int(time.time() * 1000) % 1000000,
        "method": "tools/call",
        "params": {
            "name": tool_name,
            "arguments": arguments,
        },
    }
    data = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }

    req = urllib.request.Request(ENDPOINT, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            res = json.loads(body)
            if "result" in res:
                result_data = res["result"]
                if "content" in result_data and isinstance(result_data["content"], list):
                    for item in result_data["content"]:
                        if item.get("type") == "text":
                            try:
                                parsed = json.loads(item["text"])
                                result_data["structured"] = parsed
                            except Exception:
                                pass
                return result_data
            return res
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            return json.loads(body)
        except Exception:
            return {"error": body, "status": e.code, "isError": True}
    except Exception as e:
        return {"error": str(e), "isError": True}


def get_active_editors(project_path: str = DEFAULT_PROJECT_PATH) -> list[dict[str, Any]]:
    """Gets the currently active file(s) in IntelliJ IDEA editors."""
    res = call_index_mcp("ide_get_active_file", {}, project_path=project_path)
    structured = res.get("structured", {})
    return structured.get("activeFiles", [])


def open_editor_files(
    files: list[str],
    project_path: str = DEFAULT_PROJECT_PATH,
    settle_delay: float = 0.8,
    verbose: bool = False,
) -> int:
    """Opens a list of files in IntelliJ IDEA editor tabs and allows the daemon to settle."""
    opened = 0
    for f in files:
        res = call_index_mcp("ide_open_file", {"file": f}, project_path=project_path)
        if not res.get("isError"):
            opened += 1
    if settle_delay > 0 and opened > 0:
        time.sleep(settle_delay)
    return opened


def restore_editor_state(
    initial_active_files: list[dict[str, Any]],
    project_path: str = DEFAULT_PROJECT_PATH,
    verbose: bool = True,
) -> None:
    """Restores the previously active file(s) in IntelliJ IDEA."""
    if not initial_active_files:
        return
    for item in initial_active_files:
        f_path = item.get("file")
        if not f_path:
            continue
        args: dict[str, Any] = {"file": f_path}
        if "line" in item and item["line"] is not None:
            args["line"] = item["line"]
            if "column" in item and item["column"] is not None:
                args["column"] = item["column"]
        if verbose:
            loc = f" (line {args.get('line', 1)}:{args.get('column', 1)})" if "line" in args else ""
            print(f"[*] Restoring active editor: {f_path}{loc}")
        call_index_mcp("ide_open_file", args, project_path=project_path)


def scan_project(
    src_dir: str = "E:/OneShotOneKill/MOD/src",
    project_path: str = DEFAULT_PROJECT_PATH,
    batch_size: int = 25,
    severity: str = "all",
    max_problems: int = 500,
    open_files: bool = True,
    open_strategy: str = "batch",
    daemon_delay: float = 0.8,
    restore_active: bool = True,
    verbose: bool = True,
) -> dict[str, Any]:
    """
    Performs high-speed batch diagnostic scanning across all Java files using
    the updated ide_diagnostics tool with native 'files' batching (optimal batch size: 20-30 files).
    Optionally opens files in IDE editor tabs before scanning to activate the full open_daemon
    highlighting pass.
    """
    batch_size = max(1, min(batch_size, 50))
    java_files: list[str] = []

    for root, _, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java"):
                full = os.path.join(root, f).replace("\\", "/")
                rel = os.path.relpath(full, project_path).replace("\\", "/")
                java_files.append(rel)

    java_files.sort()
    total_files = len(java_files)

    if verbose:
        print(f"[*] Found {total_files} Java source files in '{src_dir}'.")
        strat_info = f", open_strategy: '{open_strategy}', daemon_delay: {daemon_delay}s" if open_files else ""
        print(f"[*] Starting batch diagnostic scan (batch size: {batch_size}, severity: {severity}{strat_info})...\n")

    problems_by_file: dict[str, list[dict[str, Any]]] = {}
    file_analyses_summary: dict[str, int] = {
        "analyzed": 0,
        "timed_out": 0,
        "failed": 0,
        "skipped": 0,
        "not_analyzed": 0,
        "not_found": 0,
    }
    analysis_modes_summary: dict[str, int] = {}
    unusual_file_states: dict[str, dict[str, Any]] = {}
    total_problems = 0
    batches_count = (total_files + batch_size - 1) // batch_size if total_files > 0 else 0

    initial_active_files: list[dict[str, Any]] = []
    if open_files and restore_active:
        initial_active_files = get_active_editors(project_path=project_path)

    start_time = time.time()

    try:
        if open_files and open_strategy == "all":
            if verbose:
                print(f"[*] Opening all {total_files} Java files in IDE editor tabs upfront...")
            open_editor_files(java_files, project_path=project_path, settle_delay=daemon_delay, verbose=verbose)

        for idx in range(0, total_files, batch_size):
            batch = java_files[idx : idx + batch_size]
            batch_num = (idx // batch_size) + 1
            if verbose:
                print(f"  -> Batch {batch_num}/{batches_count}: Scanning {len(batch)} files...", end="", flush=True)

            if open_files and open_strategy == "batch":
                open_editor_files(batch, project_path=project_path, settle_delay=daemon_delay, verbose=False)

            b_start = time.time()
            res = call_index_mcp(
                "ide_diagnostics",
                {
                    "files": batch,
                    "severity": severity,
                    "maxProblems": max_problems,
                },
                project_path=project_path,
            )
            b_duration = time.time() - b_start

            if res.get("isError"):
                if verbose:
                    print(f" ERROR ({b_duration:.2f}s): {res.get('error')}")
                for bf in batch:
                    unusual_file_states[bf] = {"state": "rpc_error", "error": res.get("error")}
                continue

            structured = res.get("structured", {})
            batch_problems = structured.get("problems", [])
            file_analyses = structured.get("fileAnalyses", [])

            # Process per-file states and analysis modes
            for fa in file_analyses:
                f_name = fa.get("file", "")
                state = fa.get("state", "unknown")
                file_analyses_summary[state] = file_analyses_summary.get(state, 0) + 1
                mode = fa.get("mode") or fa.get("analysisMode")
                if mode:
                    analysis_modes_summary[mode] = analysis_modes_summary.get(mode, 0) + 1
                if state != "analyzed":
                    unusual_file_states[f_name] = fa

            # Map problems to files according to requested severity
            filtered_batch_problems = []
            for prob in batch_problems:
                p_sev = prob.get("severity", "WARNING").upper()
                if severity == "errors" and p_sev != "ERROR":
                    continue
                if severity == "warnings" and p_sev not in ("WARNING", "ERROR"):
                    continue
                filtered_batch_problems.append(prob)
                p_file = prob.get("file", "")
                problems_by_file.setdefault(p_file, []).append(prob)
                total_problems += 1

            if verbose:
                prob_count = len(filtered_batch_problems)
                status_tag = f"{prob_count} problem(s)" if prob_count > 0 else "clean"
                print(f" OK in {b_duration:.2f}s ({status_tag})")
    finally:
        if open_files and restore_active and initial_active_files:
            restore_editor_state(initial_active_files, project_path=project_path, verbose=verbose)

    elapsed = time.time() - start_time
    problematic_count = len(problems_by_file)
    clean_files = total_files - problematic_count - len(unusual_file_states)

    result = {
        "totalScanned": total_files,
        "cleanFiles": max(0, clean_files),
        "problematicFileCount": problematic_count,
        "totalProblems": total_problems,
        "scanDurationSeconds": round(elapsed, 2),
        "fileStates": file_analyses_summary,
        "analysisModes": analysis_modes_summary,
        "unusualStates": unusual_file_states,
        "problemsByFile": problems_by_file,
    }

    if verbose:
        print("\n" + "=" * 60)
        print("DIAGNOSTIC SCAN SUMMARY")
        print("=" * 60)
        print(f"  Files Scanned:       {total_files}")
        print(f"  Clean Files:         {result['cleanFiles']}")
        print(f"  Problematic Files:   {problematic_count}")
        print(f"  Total Problems:      {total_problems}")
        print(f"  Duration:            {elapsed:.2f}s")
        print(f"  File Statuses:       {file_analyses_summary}")
        if analysis_modes_summary:
            print(f"  Analysis Modes:      {analysis_modes_summary}")
        if unusual_file_states:
            print(f"  [!] Files with unusual states: {len(unusual_file_states)}")
            for uf, data in list(unusual_file_states.items())[:5]:
                print(f"      - {uf}: {data}")

        if problems_by_file:
            print("\n" + "-" * 60)
            print("PROBLEMS DETAIL:")
            print("-" * 60)
            for p_file, probs in problems_by_file.items():
                print(f"  [!] {p_file} ({len(probs)} issue(s)):")
                for p in probs:
                    sev = p.get("severity", "UNKNOWN")
                    line = p.get("line", 1)
                    col = p.get("column", 1)
                    msg = p.get("message", "")
                    print(f"      - Line {line}:{col} [{sev}]: {msg}")
        print("=" * 60)

    return result


def sync_vfs(
    paths: Optional[list] = None,
    project_path: str = DEFAULT_PROJECT_PATH,
    verbose: bool = True,
) -> dict:
    """Force synchronization of the IDE Virtual File System with disk."""
    args: dict[str, Any] = {}
    if paths:
        args["paths"] = paths
    res = call_index_mcp("ide_sync_files", args, project_path=project_path)
    structured = res.get("structured", {})
    if verbose:
        print(f"[*] VFS Sync result:")
        print(f"    Message:         {structured.get('message')}")
        print(f"    Synced All:      {structured.get('syncedAll')}")
        print(f"    Refreshed Roots: {structured.get('refreshedRoots')}")
        print(f"    Deleted Paths:   {structured.get('deletedPaths')}")
    return structured


def get_status(project_path: str = DEFAULT_PROJECT_PATH, verbose: bool = True) -> dict[str, Any]:
    """Queries IDE indexing readiness and dumb mode status."""
    res = call_index_mcp("ide_index_status", {}, project_path=project_path)
    structured = res.get("structured", {})
    if verbose:
        is_dumb = structured.get("isDumbMode", False)
        is_indexing = structured.get("isIndexing", False)
        print(f"[*] IDE Index Status:")
        print(f"    Dumb Mode:       {is_dumb} ({'Indexing in progress' if is_dumb else 'Ready / Smart Mode'})")
        print(f"    Is Indexing:     {is_indexing}")
        if structured.get("indexingProgress"):
            print(f"    Progress:        {structured.get('indexingProgress')}")
    return structured


def main():
    parser = argparse.ArgumentParser(description="IntelliJ Index MCP Batch Automation Helper (Modern API)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # scan-project
    scan_p = subparsers.add_parser("scan-project", help="Batch scan all Java files in project using native 'files' API")
    scan_p.add_argument("--src", default="E:/OneShotOneKill/MOD/src", help="Source directory to scan")
    scan_p.add_argument("--project-path", default=DEFAULT_PROJECT_PATH, help="Project root path")
    scan_p.add_argument("--batch-size", type=int, default=25, help="Batch size per MCP request (default: 25, max: 50)")
    scan_p.add_argument("--severity", choices=["all", "errors", "warnings"], default="all", help="Severity filter")
    scan_p.add_argument("--max-problems", type=int, default=500, help="Max problems returned per batch (default: 500)")
    scan_p.add_argument("--open-files", dest="open_files", action="store_true", default=True, help="Open files in editor before scanning (default: True)")
    scan_p.add_argument("--no-open-files", dest="open_files", action="store_false", help="Do not open files in editor before scanning")
    scan_p.add_argument("--open-strategy", choices=["batch", "all"], default="batch", help="Strategy for opening files: 'batch' (per-batch window, default) or 'all' (all upfront)")
    scan_p.add_argument("--daemon-delay", type=float, default=0.8, help="Seconds to wait after opening files for daemon to settle (default: 0.8s)")
    scan_p.add_argument("--no-restore", dest="restore_active", action="store_false", default=True, help="Do not restore initial active editor tab after scanning")
    scan_p.add_argument("--json", action="store_true", help="Print result as raw JSON")

    # sync
    sync_p = subparsers.add_parser("sync", help="Force sync IDE Virtual File System with disk")
    sync_p.add_argument("--paths", nargs="*", default=None, help="Specific relative or absolute paths to sync")
    sync_p.add_argument("--project-path", default=DEFAULT_PROJECT_PATH, help="Project root path")
    sync_p.add_argument("--json", action="store_true", help="Print result as raw JSON")

    # status
    stat_p = subparsers.add_parser("status", help="Check IDE Index and Dumb-Mode status")
    stat_p.add_argument("--project-path", default=DEFAULT_PROJECT_PATH, help="Project root path")
    stat_p.add_argument("--json", action="store_true", help="Print result as raw JSON")

    args = parser.parse_args()

    if args.command == "scan-project":
        result = scan_project(
            src_dir=args.src,
            project_path=args.project_path,
            batch_size=args.batch_size,
            severity=args.severity,
            max_problems=args.max_problems,
            open_files=args.open_files,
            open_strategy=args.open_strategy,
            daemon_delay=args.daemon_delay,
            restore_active=args.restore_active,
            verbose=not args.json,
        )
        if args.json:
            print(json.dumps(result, indent=2))
        if result["problematicFileCount"] > 0 or len(result["unusualStates"]) > 0:
            sys.exit(1)

    elif args.command == "sync":
        result = sync_vfs(paths=args.paths, project_path=args.project_path, verbose=not args.json)
        if args.json:
            print(json.dumps(result, indent=2))

    elif args.command == "status":
        result = get_status(project_path=args.project_path, verbose=not args.json)
        if args.json:
            print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
