#!/usr/bin/env python3
"""
IntelliJ Index MCP CLI & Client Library
Provides full access to IntelliJ indexing, semantic search, refactoring, and diagnostic tools.
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
                return {"error": body, "status": e.code}

    def status(self):
        return self.call("ide_index_status")

    def sync_files(self, paths=None):
        args = {}
        if paths:
            args["paths"] = paths
        return self.call("ide_sync_files", args)

    def diagnostics(self, file=None, severity="all", include_build_errors=False, include_test_results=False):
        args = {"severity": severity}
        if file:
            args["file"] = file
        if include_build_errors:
            args["includeBuildErrors"] = True
        if include_test_results:
            args["includeTestResults"] = True
        return self.call("ide_diagnostics", args)

    def find_definition(self, file=None, line=None, column=None, symbol=None, language="Java", full_preview=False):
        args = {}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = line
            args["column"] = column
        if full_preview:
            args["fullElementPreview"] = True
        return self.call("ide_find_definition", args)

    def find_references(self, file=None, line=None, column=None, symbol=None, language="Java", scope="project_files"):
        args = {"scope": scope}
        if symbol:
            args["symbol"] = symbol
            args["language"] = language
        else:
            args["file"] = file
            args["line"] = line
            args["column"] = column
        return self.call("ide_find_references", args)

    def find_class(self, query, scope="project_and_libraries"):
        return self.call("ide_find_class", {"query": query, "scope": scope})

    def find_file(self, pattern, scope="project_and_libraries"):
        return self.call("ide_find_file", {"pattern": pattern, "scope": scope})

    def search_text(self, query, case_sensitive=False, is_regex=False, file_mask=None, scope="project_files"):
        args = {
            "query": query,
            "caseSensitive": case_sensitive,
            "isRegex": is_regex,
            "scope": scope
        }
        if file_mask:
            args["fileMask"] = file_mask
        return self.call("ide_search_text", args)

    def scan_project_diagnostics(self, src_dir="E:/OneShotOneKill/MOD/src"):
        """Scans all Java files in src_dir for IDE warnings/errors."""
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

def main():
    parser = argparse.ArgumentParser(description="IntelliJ Index MCP CLI")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("status", help="Get index status (dumb mode, readiness)")

    sync_p = subparsers.add_parser("sync", help="Synchronize file changes with IDE")
    sync_p.add_argument("paths", nargs="*", help="Optional specific paths to sync")

    scan_p = subparsers.add_parser("scan-project", help="Scan all Java files in project for IDE warnings/errors")
    scan_p.add_argument("--src", default="E:/OneShotOneKill/MOD/src", help="Source directory to scan")

    diag_p = subparsers.add_parser("diagnostics", help="Get file diagnostics or build errors")
    diag_p.add_argument("--file", help="File relative to project root (e.g. MOD/src/main/...)")
    diag_p.add_argument("--severity", default="all", choices=["all", "errors", "warnings"])
    diag_p.add_argument("--build-errors", action="store_true", help="Include compiler build errors")
    diag_p.add_argument("--test-results", action="store_true", help="Include test results")

    fc_p = subparsers.add_parser("find-class", help="Find class by name/query")
    fc_p.add_argument("query", help="Class name or substring")
    fc_p.add_argument("--scope", default="project_and_libraries", choices=["project_files", "project_and_libraries"])

    ff_p = subparsers.add_parser("find-file", help="Find file by pattern")
    ff_p.add_argument("pattern", help="File pattern / glob")

    def_p = subparsers.add_parser("find-def", help="Go to definition")
    def_p.add_argument("--symbol", help="Qualified symbol name")
    def_p.add_argument("--file", help="File path")
    def_p.add_argument("--line", type=int, help="1-based line")
    def_p.add_argument("--col", type=int, default=1, help="1-based column")

    ref_p = subparsers.add_parser("find-refs", help="Find references of a symbol")
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

    call_p = subparsers.add_parser("call", help="Call any arbitrary Index MCP tool")
    call_p.add_argument("tool", help="Tool name")
    call_p.add_argument("args", nargs="?", default="{}", help="JSON arguments")

    args = parser.parse_args()

    client = IndexMcpClient()

    if args.command == "status":
        res = client.status()
        print(json.dumps(res, indent=2))
    elif args.command == "sync":
        res = client.sync_files(args.paths if args.paths else None)
        print(json.dumps(res, indent=2))
    elif args.command == "scan-project":
        res = client.scan_project_diagnostics(src_dir=args.src)
        print(json.dumps(res, indent=2))
    elif args.command == "diagnostics":
        res = client.diagnostics(
            file=args.file,
            severity=args.severity,
            include_build_errors=args.build_errors,
            include_test_results=args.test_results
        )
        print(json.dumps(res, indent=2))
    elif args.command == "find-class":
        res = client.find_class(args.query, scope=args.scope)
        print(json.dumps(res, indent=2))
    elif args.command == "find-file":
        res = client.find_file(args.pattern)
        print(json.dumps(res, indent=2))
    elif args.command == "find-def":
        res = client.find_definition(file=args.file, line=args.line, column=args.col, symbol=args.symbol)
        print(json.dumps(res, indent=2))
    elif args.command == "find-refs":
        res = client.find_references(file=args.file, line=args.line, column=args.col, symbol=args.symbol, scope=args.scope)
        print(json.dumps(res, indent=2))
    elif args.command == "search-text":
        res = client.search_text(args.query, is_regex=args.regex, case_sensitive=args.case_sensitive, file_mask=args.mask)
        print(json.dumps(res, indent=2))
    elif args.command == "call":
        parsed_args = json.loads(args.args)
        res = client.call(args.tool, parsed_args)
        print(json.dumps(res, indent=2))
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
