#!/usr/bin/env python3
"""
JetBrains Debugger MCP Automation Script
Provides batch & automated workflows that require multi-step scripting.
For standard single operations (start, pause, resume, step, eval, set-bp, etc.),
call the MCP tools directly via Antigravity MCP tooling as instructed in SKILL.md.

Automated Workflows:
- clear-all-bp: Queries all active breakpoints in the session and removes them in a single batch operation.
"""

import os
import sys
import json
import time
import argparse
import urllib.request
import urllib.error

ENDPOINT = "http://127.0.0.1:29190/debugger-mcp/streamable-http"

class DebuggerBatchClient:
    def __init__(self, endpoint=ENDPOINT):
        self.endpoint = endpoint
        self.session_id = None

    def connect(self):
        init_payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "AntigravityDebuggerBatch", "version": "1.0.0"}
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
        if not self.session_id:
            self.connect()

        payload = {
            "jsonrpc": "2.0",
            "id": int(time.time() * 1000) % 1000000,
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments or {}
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

    def clear_all_breakpoints(self):
        res = self.call("list_breakpoints")
        structured = res.get("structured", {})
        bps = structured.get("breakpoints", [])
        removed = []
        for bp in bps:
            bp_id = bp.get("id")
            if bp_id:
                self.call("remove_breakpoint", {"breakpoint_id": str(bp_id)})
                removed.append(bp_id)
        return {"clearedCount": len(removed), "clearedIds": removed}

def main():
    parser = argparse.ArgumentParser(description="JetBrains Debugger MCP Batch Automation Helper")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("clear-all-bp", help="Clear all breakpoints in one batch operation")

    args = parser.parse_args()
    client = DebuggerBatchClient()

    if args.command == "clear-all-bp":
        result = client.clear_all_breakpoints()
        print(json.dumps(result, indent=2))
    else:
        parser.print_help()
        sys.exit(1)

if __name__ == "__main__":
    main()
