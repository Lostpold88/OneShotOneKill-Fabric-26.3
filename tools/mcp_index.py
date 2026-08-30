#!/usr/bin/env python3
"""
IntelliJ Index MCP Automation Script
Provides batch & automated workflows that require multi-step scripting.
For standard single operations (find-class, find-def, find-refs, diagnostics, etc.),
call the MCP tools directly via Antigravity MCP tooling as instructed in SKILL.md.

Automated Workflows:
- scan-project: Sequentially scans all Java source files in the project for compiler errors & lint warnings.
"""

import os
import sys
import json
import time
import argparse
import urllib.request
import urllib.error

ENDPOINT = "http://127.0.0.1:29170/index-mcp/streamable-http"
DEFAULT_PROJECT_PATH = "E:/OneShotOneKill"

def call_index_mcp(tool_name, arguments=None, project_path=DEFAULT_PROJECT_PATH):
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
            "arguments": arguments
        }
    }
    data = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"
    }

    req = urllib.request.Request(ENDPOINT, data=data, headers=headers, method="POST")
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
        try:
            return json.loads(body)
        except Exception:
            return {"error": body, "status": e.code, "isError": True}
    except Exception as e:
        return {"error": str(e), "isError": True}

def scan_project(src_dir="E:/OneShotOneKill/MOD/src", project_path=DEFAULT_PROJECT_PATH):
    java_files = []
    for root, _, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java"):
                full = os.path.join(root, f).replace("\\", "/")
                rel = os.path.relpath(full, project_path).replace("\\", "/")
                java_files.append(rel)

    total = len(java_files)
    problematic = {}
    for rel_file in java_files:
        res = call_index_mcp("ide_diagnostics", {"file": rel_file, "severity": "all"}, project_path=project_path)
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
    parser = argparse.ArgumentParser(description="IntelliJ Index MCP Batch Automation Helper")
    subparsers = parser.add_subparsers(dest="command")

    scan_p = subparsers.add_parser("scan-project", help="Scan all Java files in project for IDE warnings/errors")
    scan_p.add_argument("--src", default="E:/OneShotOneKill/MOD/src", help="Source directory to scan")
    scan_p.add_argument("--project-path", default=DEFAULT_PROJECT_PATH, help="Project root path")

    args = parser.parse_args()

    if args.command == "scan-project":
        result = scan_project(src_dir=args.src, project_path=args.project_path)
        print(json.dumps(result, indent=2))
        if result["problematicCount"] > 0:
            sys.exit(1)
    else:
        parser.print_help()
        sys.exit(1)

if __name__ == "__main__":
    main()
