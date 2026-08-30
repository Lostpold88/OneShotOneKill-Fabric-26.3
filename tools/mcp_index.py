#!/usr/bin/env python3
"""
IntelliJ Index MCP CLI & Comprehensive Client Library
Exposes the complete feature set of the IntelliJ Index MCP Server (Port 29170):

Active Tools (100% Covered):
1.  ide_index_status           -> status
2.  ide_project_status         -> project-status
3.  ide_sync_files             -> sync [paths...]
4.  ide_diagnostics            -> diagnostics [--file F] [--severity S] [--build-errors] [--test-results]
5.  ide_find_definition        -> find-def [--symbol S] [--file F --line L --col C] [--full-preview]
6.  ide_find_references        -> find-refs [--symbol S] [--file F --line L --col C] [--scope S]
7.  ide_find_class             -> find-class <query> [--scope S] [--match-mode M]
8.  ide_find_file              -> find-file <pattern> [--scope S]
9.  ide_search_text            -> search-text <query> [--regex] [--case-sensitive] [--mask M] [--paths P...]
10. ide_find_implementations   -> implementations [--symbol S] [--file F --line L --col C] [--scope S]
11. ide_find_super_methods     -> super-methods [--symbol S] [--file F --line L --col C]
12. ide_call_hierarchy         -> call-hierarchy [--symbol S] [--file F --line L --col C] [--dir callers|callees] [--depth N]
13. ide_type_hierarchy         -> type-hierarchy [--symbol S] [--file F --line L --col C] [--scope S]
14. ide_refactor_rename        -> rename <new_name> --file F [--line L --col C] [--target-type symbol|file]
15. ide_move_file              -> move <file> <destination_directory>
16. ide_refactor_safe_delete   -> safe-delete [--symbol S] [--file F --line L --col C]

Composite Tools:
17. scan-project               -> Iterates across all project Java files for complete diagnostic scan
18. call                       -> Generic invocation for any MCP tool and arbitrary JSON arguments

Endpoint: http://127.0.0.1:29170/index-mcp/streamable-http
"""

import os
import sys
import json
import time
import argparse
import urllib.request
import urllib.error

ENDPOINT = "http://127.0.0.1:29170/index-mcp/streamable-http"
SESSION_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".index_session.json")
DEFAULT_PROJECT_PATH = "E:/OneShotOneKill"

class IndexMcpClient:
    def __init__(self, endpoint=ENDPOINT, project_path=DEFAULT_PROJECT_PATH):
        self.endpoint = endpoint
        self.project_path = project_path
        self.session_id = None
        self._load_session()

    def _load_session(self):
        try:
            if os.path.exists(SESSION_FILE):
                with open(SESSION_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.session_id = data.get("session_id")
        except Exception:
            self.session_id = None

    def _save_session(self):
        try:
            with open(SESSION_FILE, "w", encoding="utf-8") as f:
                json.dump({"session_id": self.session_id}, f)
        except Exception:
            pass

    def connect(self):
        if self.session_id:
            res = self.call("ide_index_status", {})
            if res and not res.get("isError") and "error" not in res:
                return

        init_payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "AntigravityIndex", "version": "1.0.0"}
            }
        }
        data = json.dumps(init_payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream"
        }
        req = urllib.request.Request(self.endpoint, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req) as resp:
            self.session_id = resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")
            self._save_session()

        notif_payload = {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}
        notif_data = json.dumps(notif_payload).encode("utf-8")
        notif_headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream"
        }
        if self.session_id:
            notif_headers["Mcp-Session-Id"] = self.session_id
        notif_req = urllib.request.Request(self.endpoint, data=notif_data, headers=notif_headers, method="POST")
        try:
            with urllib.request.urlopen(notif_req) as resp:
                pass
        except Exception:
            pass

    def call(self, tool_name, arguments=None):
        if arguments is None:
            arguments = {}
        if "project_path" not in arguments and self.project_path:
            arguments["project_path"] = self.project_path

        payload = {
            "jsonrpc": "2.0",
            "id": int(time.time() * 1000) % 1000000,
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments
            }
        }
        data = json.dumps(payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream"
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id

        req = urllib.request.Request(self.endpoint, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req) as resp:
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
            if e.code == 400 and "Server not initialized" in body:
                self.session_id = None
                self.connect()
                return self.call(tool_name, arguments)
            try:
                return json.loads(body)
            except Exception:
                return {"error": body, "status": e.code, "isError": True}
        except Exception as e:
            return {"error": str(e), "isError": True}

    # ==========================================
    # 1. Inspection & Project Status
    # ==========================================
    def status(self):
        """Check index status, smart/dumb mode, indexing state."""
        return self.call("ide_index_status")

    def project_status(self):
        """Check project open status and model state."""
        return self.call("ide_project_status")

    def sync_files(self, paths=None):
        """Notify IntelliJ IDEA to refresh external filesystem changes."""
        args = {}
        if paths:
            args["paths"] = paths
        return self.call("ide_sync_files", args)

    def diagnostics(self, file=None, severity="all", include_build_errors=False, include_test_results=False, start_line=None, end_line=None):
        """Retrieve code inspections, lint warnings, and compiler errors."""
        args = {"severity": severity}
        if file:
            args["file"] = file.replace("\\", "/")
        if include_build_errors:
            args["includeBuildErrors"] = True
        if include_test_results:
            args["includeTestResults"] = True
        if start_line is not None:
            args["startLine"] = int(start_line)
        if end_line is not None:
            args["endLine"] = int(end_line)
        return self.call("ide_diagnostics", args)

    def scan_project_diagnostics(self, src_dir="E:/OneShotOneKill/MOD/src"):
        """Scan all project Java files sequentially for zero warnings/errors."""
        java_files = []
        for root, _, files in os.walk(src_dir):
            for f in files:
                if f.endswith(".java"):
                    full = os.path.join(root, f).replace("\\", "/")
                    rel = os.path.relpath(full, self.project_path).replace("\\", "/")
                    java_files.append(rel)

        total = len(java_files)
        problematic = {}
        for rel_file in java_files:
            res = self.diagnostics(file=rel_file, severity="all")
            structured = res.get("structured", {})
            probs = structured.get("problems", [])
            if probs:
                problematic[rel_file] = probs

        return {
            "totalScanned": total,
            "cleanFiles": total - len(problematic),
            "problematicCount": len(problematic),
            "problemsByFile": problematic
        }

    # ==========================================
    # 2. Semantic Navigation & Search
    # ==========================================
    def find_definition(self, file=None, line=None, column=None, symbol=None, language="Java", full_preview=False):
        """Navigate to definition of a symbol by qualified name or file location."""
        args = {}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        if full_preview:
            args["fullElementPreview"] = True
        return self.call("ide_find_definition", args)

    def find_references(self, file=None, line=None, column=None, symbol=None, language="Java", scope="project_files", include_generated=True, page_size=100):
        """Find all usages and references of a symbol or position."""
        args = {"scope": scope, "includeGenerated": include_generated, "pageSize": page_size}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_references", args)

    def find_class(self, query, scope="project_and_libraries", language=None, match_mode="substring", page_size=50):
        """Find class or interface by name across project and dependency JARs."""
        args = {"query": query, "scope": scope, "matchMode": match_mode, "pageSize": page_size}
        if language:
            args["language"] = language
        return self.call("ide_find_class", args)

    def find_file(self, pattern, scope="project_and_libraries", include_generated=False, page_size=50):
        """Find files matching name or glob in project and libraries."""
        return self.call("ide_find_file", {"query": pattern, "scope": scope, "includeGenerated": include_generated, "pageSize": page_size})

    def search_text(self, query, case_sensitive=False, is_regex=False, file_mask=None, scope="project_files", paths=None, context="all", page_size=100):
        """Search text using IntelliJ's Find in Files index."""
        args = {
            "query": query,
            "caseSensitive": case_sensitive,
            "regex": is_regex,
            "scope": scope,
            "context": context,
            "pageSize": page_size
        }
        if file_mask:
            args["filePattern"] = file_mask
        if paths:
            args["paths"] = paths
        return self.call("ide_search_text", args)

    # ==========================================
    # 3. Hierarchies & Relationships
    # ==========================================
    def type_hierarchy(self, file=None, line=None, column=None, symbol=None, class_name=None, scope="project_files", include_generated=True):
        """Inspect supertypes and subtypes of a class or interface."""
        args = {"scope": scope, "includeGenerated": include_generated}
        target_class = class_name or symbol
        if target_class:
            args["className"] = target_class
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_type_hierarchy", args)

    def call_hierarchy(self, file=None, line=None, column=None, symbol=None, language="Java", direction="callers", depth=3, scope="project_files"):
        """Inspect callers or callees of a method up to depth N."""
        args = {"direction": direction, "depth": int(depth), "scope": scope}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_call_hierarchy", args)

    def find_implementations(self, file=None, line=None, column=None, symbol=None, language="Java", scope="project_files", page_size=100):
        """Find polymorphic implementations of an interface or abstract method."""
        args = {"scope": scope, "pageSize": page_size}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_implementations", args)

    def find_super_methods(self, file=None, line=None, column=None, symbol=None, language="Java"):
        """Find parent methods that a method overrides or implements."""
        args = {}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file.replace("\\", "/") if file else None
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_super_methods", args)

    # ==========================================
    # 4. Refactoring & Code Modification
    # ==========================================
    def rename(self, new_name, file, line=None, column=None, target_type="symbol", override_strategy="rename_base", related_renaming_strategy="all"):
        """Perform semantic rename of symbol or file across whole project."""
        args = {
            "file": file.replace("\\", "/"),
            "newName": new_name,
            "targetType": target_type,
            "overrideStrategy": override_strategy,
            "relatedRenamingStrategy": related_renaming_strategy
        }
        if line is not None:
            args["line"] = int(line)
        if column is not None:
            args["column"] = int(column)
        return self.call("ide_refactor_rename", args)

    def move_file(self, file, destination):
        """Safely move a file updating packages and imports."""
        return self.call("ide_move_file", {
            "file": file.replace("\\", "/"),
            "destination": destination.replace("\\", "/")
        })

    def safe_delete(self, file, line=None, column=None, target_type="symbol", force=False):
        """Safely delete symbol or file checking for usages across project."""
        args = {
            "file": file.replace("\\", "/"),
            "target_type": target_type,
            "force": force
        }
        if line is not None:
            args["line"] = int(line)
        if column is not None:
            args["column"] = int(column)
        return self.call("ide_refactor_safe_delete", args)


def main():
    parser = argparse.ArgumentParser(description="IntelliJ Index MCP CLI & Comprehensive Client")
    subparsers = parser.add_subparsers(dest="command")

    # 1. Inspection & Status
    subparsers.add_parser("status", help="Get index status (dumb mode, progress)")
    subparsers.add_parser("project-status", help="Get project open status")
    
    sync_p = subparsers.add_parser("sync", help="Synchronize file changes with IDE")
    sync_p.add_argument("paths", nargs="*", help="Optional specific paths to sync")

    scan_p = subparsers.add_parser("scan-project", help="Scan all Java files in project for IDE warnings/errors")
    scan_p.add_argument("--src", default="E:/OneShotOneKill/MOD/src", help="Source directory to scan")

    diag_p = subparsers.add_parser("diagnostics", help="Get file diagnostics or build errors")
    diag_p.add_argument("--file", help="File relative to project root")
    diag_p.add_argument("--severity", default="all", choices=["all", "errors", "warnings"])
    diag_p.add_argument("--build-errors", action="store_true", help="Include compiler build errors")
    diag_p.add_argument("--test-results", action="store_true", help="Include test results")
    diag_p.add_argument("--start-line", type=int, help="Filter start line")
    diag_p.add_argument("--end-line", type=int, help="Filter end line")

    # 2. Search & Navigation
    fc_p = subparsers.add_parser("find-class", help="Find class by name/query in project and libraries")
    fc_p.add_argument("query", help="Class name or substring")
    fc_p.add_argument("--scope", default="project_and_libraries", choices=["project_files", "project_and_libraries", "project_production_files", "project_test_files"])
    fc_p.add_argument("--match-mode", default="substring", choices=["substring", "prefix", "exact"])

    ff_p = subparsers.add_parser("find-file", help="Find file by pattern")
    ff_p.add_argument("pattern", help="File pattern / glob")
    ff_p.add_argument("--scope", default="project_and_libraries")

    def_p = subparsers.add_parser("find-def", help="Go to definition")
    def_p.add_argument("--symbol", help="Qualified symbol name")
    def_p.add_argument("--file", help="File path")
    def_p.add_argument("--line", type=int, help="1-based line")
    def_p.add_argument("--col", type=int, default=1, help="1-based column")
    def_p.add_argument("--full-preview", action="store_true", help="Include full preview")

    ref_p = subparsers.add_parser("find-refs", help="Find all usages/references of a symbol")
    ref_p.add_argument("--symbol", help="Qualified symbol name")
    ref_p.add_argument("--file", help="File path")
    ref_p.add_argument("--line", type=int, help="1-based line")
    ref_p.add_argument("--col", type=int, default=1, help="1-based column")
    ref_p.add_argument("--scope", default="project_files")

    st_p = subparsers.add_parser("search-text", help="Semantic text search in IDE index")
    st_p.add_argument("query", help="Search text")
    st_p.add_argument("--regex", action="store_true", help="Treat query as regex")
    st_p.add_argument("--case-sensitive", action="store_true", help="Case sensitive search")
    st_p.add_argument("--mask", help="File mask (e.g. *.java)")
    st_p.add_argument("--paths", nargs="*", help="Path filter globs")

    # 3. Hierarchies & Relationships
    th_p = subparsers.add_parser("type-hierarchy", help="Get type hierarchy (supertypes/subtypes)")
    th_p.add_argument("--symbol", help="Qualified symbol / class name")
    th_p.add_argument("--file", help="File path")
    th_p.add_argument("--line", type=int, help="1-based line")
    th_p.add_argument("--col", type=int, default=1, help="1-based column")
    th_p.add_argument("--scope", default="project_files", choices=["project_files", "project_and_libraries"])

    ch_p = subparsers.add_parser("call-hierarchy", help="Get call hierarchy (callers/callees)")
    ch_p.add_argument("--symbol", help="Qualified symbol name")
    ch_p.add_argument("--file", help="File path")
    ch_p.add_argument("--line", type=int, help="1-based line")
    ch_p.add_argument("--col", type=int, default=1, help="1-based column")
    ch_p.add_argument("--dir", default="callers", choices=["callers", "callees"])
    ch_p.add_argument("--depth", type=int, default=3, help="Max depth (1-5)")

    impl_p = subparsers.add_parser("implementations", help="Find implementations of interface/method")
    impl_p.add_argument("--symbol", help="Qualified symbol name")
    impl_p.add_argument("--file", help="File path")
    impl_p.add_argument("--line", type=int, help="1-based line")
    impl_p.add_argument("--col", type=int, default=1, help="1-based column")
    impl_p.add_argument("--scope", default="project_files")

    super_p = subparsers.add_parser("super-methods", help="Find parent methods overridden/implemented")
    super_p.add_argument("--symbol", help="Qualified symbol name")
    super_p.add_argument("--file", help="File path")
    super_p.add_argument("--line", type=int, help="1-based line")
    super_p.add_argument("--col", type=int, default=1, help="1-based column")

    # 4. Refactoring
    ren_p = subparsers.add_parser("rename", help="Rename symbol or file across entire project")
    ren_p.add_argument("new_name", help="New symbol or file name")
    ren_p.add_argument("--file", required=True, help="File path")
    ren_p.add_argument("--line", type=int, help="1-based line")
    ren_p.add_argument("--col", type=int, default=1, help="1-based column")
    ren_p.add_argument("--target-type", default="symbol", choices=["symbol", "file"])

    mv_p = subparsers.add_parser("move", help="Move file safely with package and import updates")
    mv_p.add_argument("file", help="File to move")
    mv_p.add_argument("dest", help="Destination directory")

    del_p = subparsers.add_parser("safe-delete", help="Safely delete a symbol or file")
    del_p.add_argument("--file", required=True, help="File path")
    del_p.add_argument("--line", type=int, help="1-based line")
    del_p.add_argument("--col", type=int, default=1, help="1-based column")
    del_p.add_argument("--target-type", default="symbol", choices=["symbol", "file"])
    del_p.add_argument("--force", action="store_true", help="Force deletion")

    # Raw Call
    call_p = subparsers.add_parser("call", help="Call any Index MCP tool directly")
    call_p.add_argument("tool", help="Tool name")
    call_p.add_argument("args", nargs="?", default="{}", help="JSON arguments")

    args = parser.parse_args()
    client = IndexMcpClient()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    result = None
    if args.command == "status":
        result = client.status()
    elif args.command == "project-status":
        result = client.project_status()
    elif args.command == "sync":
        result = client.sync_files(args.paths if args.paths else None)
    elif args.command == "scan-project":
        result = client.scan_project_diagnostics(src_dir=args.src)
    elif args.command == "diagnostics":
        result = client.diagnostics(
            file=args.file,
            severity=args.severity,
            include_build_errors=args.build_errors,
            include_test_results=args.test_results,
            start_line=args.start_line,
            end_line=args.end_line
        )
    elif args.command == "find-class":
        result = client.find_class(args.query, scope=args.scope, match_mode=args.match_mode)
    elif args.command == "find-file":
        result = client.find_file(args.pattern, scope=args.scope)
    elif args.command == "find-def":
        result = client.find_definition(file=args.file, line=args.line, column=args.col, symbol=args.symbol, full_preview=args.full_preview)
    elif args.command == "find-refs":
        result = client.find_references(file=args.file, line=args.line, column=args.col, symbol=args.symbol, scope=args.scope)
    elif args.command == "search-text":
        result = client.search_text(args.query, is_regex=args.regex, case_sensitive=args.case_sensitive, file_mask=args.mask, paths=args.paths)
    elif args.command == "type-hierarchy":
        result = client.type_hierarchy(file=args.file, line=args.line, column=args.col, symbol=args.symbol, scope=args.scope)
    elif args.command == "call-hierarchy":
        result = client.call_hierarchy(file=args.file, line=args.line, column=args.col, symbol=args.symbol, direction=args.dir, depth=args.depth)
    elif args.command == "implementations":
        result = client.find_implementations(file=args.file, line=args.line, column=args.col, symbol=args.symbol, scope=args.scope)
    elif args.command == "super-methods":
        result = client.find_super_methods(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "rename":
        result = client.rename(args.new_name, file=args.file, line=args.line, column=args.col, target_type=args.target_type)
    elif args.command == "move":
        result = client.move_file(args.file, args.dest)
    elif args.command == "safe-delete":
        result = client.safe_delete(file=args.file, line=args.line, column=args.col, target_type=args.target_type, force=args.force)
    elif args.command == "call":
        parsed_args = json.loads(args.args)
        result = client.call(args.tool, parsed_args)

    print(json.dumps(result, indent=2))
    if isinstance(result, dict) and result.get("isError"):
        sys.exit(1)

if __name__ == "__main__":
    main()
