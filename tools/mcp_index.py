#!/usr/bin/env python3
"""
IntelliJ Index MCP CLI & Comprehensive Client Library
Exposes the complete feature set of the IntelliJ Index MCP Server (36+ tools):
- Inspection & Status: status, sync, diagnostics, scan-project, project-diagnostics
- Semantic Navigation: find-def, find-refs, find-class, find-file, find-symbol, symbol-info, search-text
- Hierarchies: type-hierarchy, call-hierarchy, implementations, super-methods, structure
- Refactoring: rename, move, safe-delete, reformat
- Editor & Build: read-file, open-file, active-file, build, reload, list-tests, run-tests
- Generic invocation: call <tool> [json_args]

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

    # --- 1. Inspection & Diagnostics ---
    def status(self):
        return self.call("ide_index_status")

    def sync_files(self, paths=None):
        args = {}
        if paths:
            args["paths"] = paths
        return self.call("ide_sync_files", args)

    def diagnostics(self, file=None, severity="all", include_build_errors=False, include_test_results=False, start_line=None, end_line=None):
        args = {"severity": severity}
        if file:
            args["file"] = file
        if include_build_errors:
            args["includeBuildErrors"] = True
        if include_test_results:
            args["includeTestResults"] = True
        if start_line is not None:
            args["startLine"] = int(start_line)
        if end_line is not None:
            args["endLine"] = int(end_line)
        return self.call("ide_diagnostics", args)

    def project_diagnostics(self, severity="all", paths=None, include_build_errors=True):
        args = {"severity": severity, "includeBuildErrors": include_build_errors}
        if paths:
            args["paths"] = paths
        return self.call("ide_project_diagnostics", args)

    def scan_project_diagnostics(self, src_dir="E:/OneShotOneKill/MOD/src"):
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

    # --- 2. Code Navigation & Search ---
    def find_definition(self, file=None, line=None, column=None, symbol=None, language="Java", full_preview=False):
        args = {}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        if full_preview:
            args["fullElementPreview"] = True
        return self.call("ide_find_definition", args)

    def find_references(self, file=None, line=None, column=None, symbol=None, language="Java", scope="project_files", include_generated=True):
        args = {"scope": scope, "includeGenerated": include_generated}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_references", args)

    def find_class(self, query, scope="project_and_libraries", language=None, match_mode="substring"):
        args = {"query": query, "scope": scope, "matchMode": match_mode}
        if language:
            args["language"] = language
        return self.call("ide_find_class", args)

    def find_file(self, pattern, scope="project_and_libraries", include_generated=False):
        return self.call("ide_find_file", {"query": pattern, "scope": scope, "includeGenerated": include_generated})

    def find_symbol(self, query, scope="project_files", language=None):
        args = {"query": query, "scope": scope}
        if language:
            args["language"] = language
        return self.call("ide_find_symbol", args)

    def symbol_info(self, file=None, line=None, column=None, symbol=None, language="Java", include_doc=True):
        args = {"includeDoc": include_doc}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_symbol_info", args)

    def search_text(self, query, case_sensitive=False, is_regex=False, file_mask=None, scope="project_files", paths=None):
        args = {
            "query": query,
            "caseSensitive": case_sensitive,
            "regex": is_regex,
            "scope": scope
        }
        if file_mask:
            args["filePattern"] = file_mask
        if paths:
            args["paths"] = paths
        return self.call("ide_search_text", args)

    # --- 3. Hierarchies & Structure ---
    def type_hierarchy(self, file=None, line=None, column=None, symbol=None, language="Java", direction="both"):
        args = {"direction": direction}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_type_hierarchy", args)

    def call_hierarchy(self, file=None, line=None, column=None, symbol=None, language="Java", direction="callers", scope="project_files"):
        args = {"direction": direction, "scope": scope}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_call_hierarchy", args)

    def find_implementations(self, file=None, line=None, column=None, symbol=None, language="Java", scope="project_files"):
        args = {"scope": scope}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_implementations", args)

    def find_super_methods(self, file=None, line=None, column=None, symbol=None, language="Java"):
        args = {}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_find_super_methods", args)

    def file_structure(self, file):
        return self.call("ide_file_structure", {"file": file})

    # --- 4. Refactoring & Code Modification ---
    def rename(self, new_name, file=None, line=None, column=None, symbol=None, language="Java"):
        args = {"newName": new_name}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_refactor_rename", args)

    def move_file(self, file, destination_directory):
        return self.call("ide_move_file", {"file": file, "destinationDirectory": destination_directory})

    def safe_delete(self, file=None, line=None, column=None, symbol=None, language="Java", search_in_comments=True):
        args = {"searchInComments": search_in_comments}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = int(line)
            args["column"] = int(column) if column else 1
        return self.call("ide_refactor_safe_delete", args)

    def reformat_code(self, file, start_line=None, end_line=None):
        args = {"file": file}
        if start_line is not None:
            args["startLine"] = int(start_line)
        if end_line is not None:
            args["endLine"] = int(end_line)
        return self.call("ide_reformat_code", args)

    # --- 5. Files, Editor & Tests ---
    def read_file(self, file, start_line=None, end_line=None):
        args = {"file": file}
        if start_line is not None:
            args["startLine"] = int(start_line)
        if end_line is not None:
            args["endLine"] = int(end_line)
        return self.call("ide_read_file", args)

    def open_file(self, file, line=1, column=1):
        return self.call("ide_open_file", {"file": file, "line": int(line), "column": int(column)})

    def get_active_file(self):
        return self.call("ide_get_active_file")

    def build_project(self):
        return self.call("ide_build_project")

    def reload_project(self):
        return self.call("ide_reload_project")

    def list_tests(self, file=None, scope="project_files"):
        args = {"scope": scope}
        if file:
            args["file"] = file
        return self.call("ide_list_tests", args)

    def run_tests(self, file=None, class_name=None, method_name=None):
        args = {}
        if file:
            args["file"] = file
        if class_name:
            args["className"] = class_name
        if method_name:
            args["methodName"] = method_name
        return self.call("ide_run_tests", args)

def main():
    parser = argparse.ArgumentParser(description="IntelliJ Index MCP CLI & Comprehensive Client")
    subparsers = parser.add_subparsers(dest="command")

    # Status & Diagnostics
    subparsers.add_parser("status", help="Get index status (dumb mode, progress)")
    
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

    pdiag_p = subparsers.add_parser("project-diagnostics", help="Project-wide diagnostics via IDE")
    pdiag_p.add_argument("--severity", default="all", choices=["all", "errors", "warnings"])

    # Search & Navigation
    fc_p = subparsers.add_parser("find-class", help="Find class by name/query in project and libraries")
    fc_p.add_argument("query", help="Class name or substring")
    fc_p.add_argument("--scope", default="project_and_libraries", choices=["project_files", "project_and_libraries", "project_production_files", "project_test_files"])
    fc_p.add_argument("--match-mode", default="substring", choices=["substring", "prefix", "exact"])

    ff_p = subparsers.add_parser("find-file", help="Find file by pattern")
    ff_p.add_argument("pattern", help="File pattern / glob")
    ff_p.add_argument("--scope", default="project_and_libraries")

    fs_p = subparsers.add_parser("find-symbol", help="Find any symbol (class, method, field)")
    fs_p.add_argument("query", help="Symbol query")
    fs_p.add_argument("--scope", default="project_files")

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

    sym_p = subparsers.add_parser("symbol-info", help="Get resolved signature, modifiers and doc comment")
    sym_p.add_argument("--symbol", help="Qualified symbol name")
    sym_p.add_argument("--file", help="File path")
    sym_p.add_argument("--line", type=int, help="1-based line")
    sym_p.add_argument("--col", type=int, default=1, help="1-based column")

    st_p = subparsers.add_parser("search-text", help="Semantic text search in IDE index")
    st_p.add_argument("query", help="Search text")
    st_p.add_argument("--regex", action="store_true", help="Treat query as regex")
    st_p.add_argument("--case-sensitive", action="store_true", help="Case sensitive search")
    st_p.add_argument("--mask", help="File mask (e.g. *.java)")

    # Hierarchies & Structure
    th_p = subparsers.add_parser("type-hierarchy", help="Get type hierarchy (supertypes/subtypes)")
    th_p.add_argument("--symbol", help="Qualified symbol name")
    th_p.add_argument("--file", help="File path")
    th_p.add_argument("--line", type=int, help="1-based line")
    th_p.add_argument("--col", type=int, default=1, help="1-based column")
    th_p.add_argument("--dir", default="both", choices=["supertypes", "subtypes", "both"])

    ch_p = subparsers.add_parser("call-hierarchy", help="Get call hierarchy (callers/callees)")
    ch_p.add_argument("--symbol", help="Qualified symbol name")
    ch_p.add_argument("--file", help="File path")
    ch_p.add_argument("--line", type=int, help="1-based line")
    ch_p.add_argument("--col", type=int, default=1, help="1-based column")
    ch_p.add_argument("--dir", default="callers", choices=["callers", "callees"])

    impl_p = subparsers.add_parser("implementations", help="Find implementations of interface/method")
    impl_p.add_argument("--symbol", help="Qualified symbol name")
    impl_p.add_argument("--file", help="File path")
    impl_p.add_argument("--line", type=int, help="1-based line")
    impl_p.add_argument("--col", type=int, default=1, help="1-based column")

    super_p = subparsers.add_parser("super-methods", help="Find parent methods overridden/implemented")
    super_p.add_argument("--symbol", help="Qualified symbol name")
    super_p.add_argument("--file", help="File path")
    super_p.add_argument("--line", type=int, help="1-based line")
    super_p.add_argument("--col", type=int, default=1, help="1-based column")

    struct_p = subparsers.add_parser("structure", help="Get file structure (classes, methods, fields)")
    struct_p.add_argument("file", help="File path")

    # Refactoring
    ren_p = subparsers.add_parser("rename", help="Rename symbol across entire project")
    ren_p.add_argument("new_name", help="New symbol name")
    ren_p.add_argument("--symbol", help="Qualified symbol name")
    ren_p.add_argument("--file", help="File path")
    ren_p.add_argument("--line", type=int, help="1-based line")
    ren_p.add_argument("--col", type=int, default=1, help="1-based column")

    mv_p = subparsers.add_parser("move", help="Move file safely with package and import updates")
    mv_p.add_argument("file", help="File to move")
    mv_p.add_argument("dest", help="Destination directory")

    del_p = subparsers.add_parser("safe-delete", help="Safely delete a symbol or file")
    del_p.add_argument("--symbol", help="Qualified symbol name")
    del_p.add_argument("--file", help="File path")
    del_p.add_argument("--line", type=int, help="1-based line")
    del_p.add_argument("--col", type=int, default=1, help="1-based column")

    fmt_p = subparsers.add_parser("reformat", help="Reformat code according to IDE style")
    fmt_p.add_argument("file", help="File to reformat")
    fmt_p.add_argument("--start", type=int, help="Start line")
    fmt_p.add_argument("--end", type=int, help="End line")

    # Editor & Build
    read_p = subparsers.add_parser("read-file", help="Read file/jar source with IDE resolution")
    read_p.add_argument("file", help="File or jar:// URL")
    read_p.add_argument("--start", type=int, help="Start line")
    read_p.add_argument("--end", type=int, help="End line")

    open_p = subparsers.add_parser("open-file", help="Open file in IDE editor")
    open_p.add_argument("file", help="File path")
    open_p.add_argument("--line", type=int, default=1)
    open_p.add_argument("--col", type=int, default=1)

    subparsers.add_parser("active-file", help="Get currently active file in IDE")
    subparsers.add_parser("build", help="Build project in IDE")
    subparsers.add_parser("reload", help="Reload project model in IDE")

    # Tests
    lt_p = subparsers.add_parser("list-tests", help="List unit/integration tests")
    lt_p.add_argument("--file", help="Optional test file")

    rt_p = subparsers.add_parser("run-tests", help="Run tests in IDE")
    rt_p.add_argument("--file", help="Test file")
    rt_p.add_argument("--class-name", help="Class name")
    rt_p.add_argument("--method-name", help="Method name")

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
    elif args.command == "project-diagnostics":
        result = client.project_diagnostics(severity=args.severity)
    elif args.command == "find-class":
        result = client.find_class(args.query, scope=args.scope, match_mode=args.match_mode)
    elif args.command == "find-file":
        result = client.find_file(args.pattern, scope=args.scope)
    elif args.command == "find-symbol":
        result = client.find_symbol(args.query, scope=args.scope)
    elif args.command == "find-def":
        result = client.find_definition(file=args.file, line=args.line, column=args.col, symbol=args.symbol, full_preview=args.full_preview)
    elif args.command == "find-refs":
        result = client.find_references(file=args.file, line=args.line, column=args.col, symbol=args.symbol, scope=args.scope)
    elif args.command == "symbol-info":
        result = client.symbol_info(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "search-text":
        result = client.search_text(args.query, is_regex=args.regex, case_sensitive=args.case_sensitive, file_mask=args.mask)
    elif args.command == "type-hierarchy":
        result = client.type_hierarchy(file=args.file, line=args.line, column=args.col, symbol=args.symbol, direction=args.dir)
    elif args.command == "call-hierarchy":
        result = client.call_hierarchy(file=args.file, line=args.line, column=args.col, symbol=args.symbol, direction=args.dir)
    elif args.command == "implementations":
        result = client.find_implementations(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "super-methods":
        result = client.find_super_methods(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "structure":
        result = client.file_structure(args.file)
    elif args.command == "rename":
        result = client.rename(args.new_name, file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "move":
        result = client.move_file(args.file, args.dest)
    elif args.command == "safe-delete":
        result = client.safe_delete(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
    elif args.command == "reformat":
        result = client.reformat_code(args.file, start_line=args.start, end_line=args.end)
    elif args.command == "read-file":
        result = client.read_file(args.file, start_line=args.start, end_line=args.end)
    elif args.command == "open-file":
        result = client.open_file(args.file, line=args.line, column=args.col)
    elif args.command == "active-file":
        result = client.get_active_file()
    elif args.command == "build":
        result = client.build_project()
    elif args.command == "reload":
        result = client.reload_project()
    elif args.command == "list-tests":
        result = client.list_tests(file=args.file)
    elif args.command == "run-tests":
        result = client.run_tests(file=args.file, class_name=args.class_name, method_name=args.method_name)
    elif args.command == "call":
        parsed_args = json.loads(args.args)
        result = client.call(args.tool, parsed_args)

    print(json.dumps(result, indent=2))
    if isinstance(result, dict) and result.get("isError"):
        sys.exit(1)

if __name__ == "__main__":
    main()
