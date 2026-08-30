#!/usr/bin/env python3
"""
JetBrains Debugger MCP CLI & Client Library
Provides full programmatic control over JetBrains Debugger:
- Start/stop debug sessions & run configurations
- Breakpoints management (line, conditional, log points, remove, clear)
- Execution flow (pause, resume, step over/into/out, run to line, wait for pause)
- Inspection (stack traces, threads, variables, source context, evaluate expression)

Endpoint: http://127.0.0.1:29190/debugger-mcp/streamable-http
"""

import os
import sys
import json
import time
import argparse
import urllib.request
import urllib.error

ENDPOINT = "http://127.0.0.1:29190/debugger-mcp/streamable-http"
SESSION_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".debugger_session.json")

class DebuggerMcpClient:
    def __init__(self, endpoint=ENDPOINT):
        self.endpoint = endpoint
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
            res = self.call("list_debug_sessions", {})
            if res and not res.get("isError") and "error" not in res:
                return

        init_payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "AntigravityDebugger", "version": "1.0.0"}
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
                return {"error": body, "status": e.code}

    # High-level convenience methods
    def list_run_configurations(self):
        return self.call("list_run_configurations")

    def start_session(self, configuration_name="Minecraft Client"):
        return self.call("start_debug_session", {"configuration_name": configuration_name})

    def stop_session(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("stop_debug_session", args)

    def list_sessions(self):
        return self.call("list_debug_sessions")

    def session_status(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("get_debug_session_status", args)

    def list_breakpoints(self):
        return self.call("list_breakpoints")

    def set_breakpoint(self, file, line, condition=None, log_message=None, suspend_policy="all", hit_count=None):
        args = {
            "file": os.path.abspath(file),
            "line": int(line),
            "suspend_policy": suspend_policy
        }
        if condition:
            args["condition"] = condition
        if log_message:
            args["log_message"] = log_message
        if hit_count:
            args["hit_count"] = int(hit_count)
        return self.call("set_breakpoint", args)

    def remove_breakpoint(self, breakpoint_id):
        return self.call("remove_breakpoint", {"breakpoint_id": str(breakpoint_id)})

    def clear_all_breakpoints(self):
        res = self.list_breakpoints()
        structured = res.get("structured", {})
        bps = structured.get("breakpoints", [])
        removed = []
        for bp in bps:
            bp_id = bp.get("id")
            if bp_id:
                self.remove_breakpoint(bp_id)
                removed.append(bp_id)
        return {"clearedCount": len(removed), "clearedIds": removed}

    def resume(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("resume_execution", args)

    def pause(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("pause_execution", args)

    def step_over(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("step_over", args)

    def step_into(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("step_into", args)

    def step_out(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("step_out", args)

    def run_to_line(self, file, line, session_id=None):
        args = {"file": os.path.abspath(file), "line": int(line)}
        if session_id:
            args["session_id"] = session_id
        return self.call("run_to_line", args)

    def wait_for_pause(self, timeout=60, session_id=None):
        args = {"timeout": int(timeout)}
        if session_id:
            args["session_id"] = session_id
        return self.call("wait_for_pause", args)

    def evaluate(self, expression, session_id=None, frame_index=0):
        args = {"expression": expression, "frame_index": int(frame_index)}
        if session_id:
            args["session_id"] = session_id
        return self.call("evaluate_expression", args)

    def get_variables(self, session_id=None, frame_index=0, group_by="scope"):
        args = {"frame_index": int(frame_index), "group_by": group_by}
        if session_id:
            args["session_id"] = session_id
        return self.call("get_variables", args)

    def list_threads(self, session_id=None):
        args = {}
        if session_id:
            args["session_id"] = session_id
        return self.call("list_threads", args)

    def get_stack_trace(self, session_id=None, thread_id=None, max_frames=20):
        args = {"max_frames": int(max_frames)}
        if session_id:
            args["session_id"] = session_id
        if thread_id:
            args["thread_id"] = thread_id
        return self.call("get_stack_trace", args)

def main():
    parser = argparse.ArgumentParser(description="JetBrains Debugger MCP CLI")
    subparsers = parser.add_subparsers(dest="command")

    # start
    start_p = subparsers.add_parser("start", help="Start debug session")
    start_p.add_argument("config", nargs="?", default="Minecraft Client", help="Run configuration name")

    # stop
    stop_p = subparsers.add_parser("stop", help="Stop debug session")
    stop_p.add_argument("--session-id", help="Optional debug session ID")

    # status
    stat_p = subparsers.add_parser("status", help="Get full status of current debug session")
    stat_p.add_argument("--session-id", help="Optional debug session ID")

    # list-sessions
    subparsers.add_parser("list-sessions", help="List all active debug sessions")

    # list-configs
    subparsers.add_parser("list-configs", help="List available run configurations")

    # list-bp
    subparsers.add_parser("list-bp", help="List all breakpoints")

    # set-bp
    setbp_p = subparsers.add_parser("set-bp", help="Set a breakpoint")
    setbp_p.add_argument("file", help="File path (absolute or relative)")
    setbp_p.add_argument("line", type=int, help="1-based line number")
    setbp_p.add_argument("--condition", help="Breakpoint expression condition")
    setbp_p.add_argument("--log", help="Log message expression (tracepoint)")
    setbp_p.add_argument("--suspend", default="all", choices=["all", "thread", "none"])

    # remove-bp
    rmbp_p = subparsers.add_parser("remove-bp", help="Remove a breakpoint by ID")
    rmbp_p.add_argument("id", help="Breakpoint ID")

    # clear-all-bp
    subparsers.add_parser("clear-all-bp", help="Clear all breakpoints")

    # eval
    eval_p = subparsers.add_parser("eval", help="Evaluate expression in paused session")
    eval_p.add_argument("expression", help="Expression to evaluate")
    eval_p.add_argument("--frame", type=int, default=0, help="Stack frame index")
    eval_p.add_argument("--session-id", help="Optional debug session ID")

    # resume
    res_p = subparsers.add_parser("resume", help="Resume execution")
    res_p.add_argument("--session-id", help="Optional debug session ID")

    # pause
    pause_p = subparsers.add_parser("pause", help="Pause execution")
    pause_p.add_argument("--session-id", help="Optional debug session ID")

    # step
    step_p = subparsers.add_parser("step", help="Step over / into / out")
    step_p.add_argument("mode", choices=["over", "into", "out"], default="over", nargs="?")
    step_p.add_argument("--session-id", help="Optional debug session ID")

    # wait
    wait_p = subparsers.add_parser("wait", help="Wait for breakpoint pause")
    wait_p.add_argument("--timeout", type=int, default=60, help="Timeout in seconds")
    wait_p.add_argument("--session-id", help="Optional debug session ID")

    # vars
    var_p = subparsers.add_parser("vars", help="Get variables in current frame")
    var_p.add_argument("--frame", type=int, default=0, help="Stack frame index")
    var_p.add_argument("--session-id", help="Optional debug session ID")

    # threads
    th_p = subparsers.add_parser("threads", help="List threads")
    th_p.add_argument("--session-id", help="Optional debug session ID")

    # stack
    st_p = subparsers.add_parser("stack", help="Get stack trace")
    st_p.add_argument("--max-frames", type=int, default=20, help="Max frames")
    st_p.add_argument("--session-id", help="Optional debug session ID")

    # raw call
    call_p = subparsers.add_parser("call", help="Call any arbitrary Debugger MCP tool")
    call_p.add_argument("tool", help="Tool name")
    call_p.add_argument("args", nargs="?", default="{}", help="JSON arguments")

    args = parser.parse_args()
    client = DebuggerMcpClient()

    if args.command == "start":
        print(json.dumps(client.start_session(args.config), indent=2))
    elif args.command == "stop":
        print(json.dumps(client.stop_session(args.session_id), indent=2))
    elif args.command == "status":
        print(json.dumps(client.session_status(args.session_id), indent=2))
    elif args.command == "list-sessions":
        print(json.dumps(client.list_sessions(), indent=2))
    elif args.command == "list-configs":
        print(json.dumps(client.list_run_configurations(), indent=2))
    elif args.command == "list-bp":
        print(json.dumps(client.list_breakpoints(), indent=2))
    elif args.command == "set-bp":
        print(json.dumps(client.set_breakpoint(args.file, args.line, condition=args.condition, log_message=args.log, suspend_policy=args.suspend), indent=2))
    elif args.command == "remove-bp":
        print(json.dumps(client.remove_breakpoint(args.id), indent=2))
    elif args.command == "clear-all-bp":
        print(json.dumps(client.clear_all_breakpoints(), indent=2))
    elif args.command == "eval":
        print(json.dumps(client.evaluate(args.expression, session_id=args.session_id, frame_index=args.frame), indent=2))
    elif args.command == "resume":
        print(json.dumps(client.resume(args.session_id), indent=2))
    elif args.command == "pause":
        print(json.dumps(client.pause(args.session_id), indent=2))
    elif args.command == "step":
        if args.mode == "over":
            print(json.dumps(client.step_over(args.session_id), indent=2))
        elif args.mode == "into":
            print(json.dumps(client.step_into(args.session_id), indent=2))
        elif args.mode == "out":
            print(json.dumps(client.step_out(args.session_id), indent=2))
    elif args.command == "wait":
        print(json.dumps(client.wait_for_pause(timeout=args.timeout, session_id=args.session_id), indent=2))
    elif args.command == "vars":
        print(json.dumps(client.get_variables(session_id=args.session_id, frame_index=args.frame), indent=2))
    elif args.command == "threads":
        print(json.dumps(client.list_threads(session_id=args.session_id), indent=2))
    elif args.command == "stack":
        print(json.dumps(client.get_stack_trace(session_id=args.session_id, max_frames=args.max_frames), indent=2))
    elif args.command == "call":
        parsed_args = json.loads(args.args)
        print(json.dumps(client.call(args.tool, parsed_args), indent=2))
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
