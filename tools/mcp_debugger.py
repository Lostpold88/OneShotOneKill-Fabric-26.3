#!/usr/bin/env python3
"""
JetBrains Debugger MCP CLI & Comprehensive Client Library
Exposes the complete feature set of the JetBrains Debugger MCP Server (23+ tools):
- Run Configurations & Lifecycle: list-configs, run-config, start, stop, list-sessions, status
- Breakpoints Management: list-bp, set-bp, remove-bp, clear-all-bp
- Execution Flow: resume, pause, step (over/into/out), run-to-line, wait
- Inspection: eval, vars, set-var, source, select-frame, threads, stack
- Generic invocation: call <tool> [json_args]

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
                return {"error": body, "status": e.code, "isError": True}
        except Exception as e:
            return {"error": str(e), "isError": True}

    # --- 1. Run Configurations & Lifecycle ---
    def list_run_configurations(self):
        return self.call("list_run_configurations")

    def execute_run_configuration(self, name, mode="debug"):
        return self.call("execute_run_configuration", {"name": name, "mode": mode})

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

    # --- 2. Breakpoints Management ---
    def list_breakpoints(self):
        return self.call("list_breakpoints")

    def set_breakpoint(self, file, line, condition=None, log_message=None, suspend_policy="all", hit_count=None):
        args = {
            "file_path": os.path.abspath(file),
            "line": int(line),
            "suspend_policy": suspend_policy
        }
        if condition:
            args["condition"] = condition
        if log_message:
            args["log_message"] = log_message
        if hit_count is not None:
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

    # --- 3. Execution Flow ---
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
        args = {"file_path": os.path.abspath(file), "line": int(line)}
        if session_id:
            args["session_id"] = session_id
        return self.call("run_to_line", args)

    def wait_for_pause(self, timeout=60, session_id=None):
        args = {"timeout": int(timeout)}
        if session_id:
            args["session_id"] = session_id
        return self.call("wait_for_pause", args)

    # --- 4. Inspection & Expressions ---
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

    def set_variable(self, name, value, session_id=None, frame_index=0):
        args = {"name": name, "value": str(value), "frame_index": int(frame_index)}
        if session_id:
            args["session_id"] = session_id
        return self.call("set_variable", args)

    def get_source_context(self, file=None, line=None, lines_before=5, lines_after=5, session_id=None):
        args = {
            "lines_before": int(lines_before),
            "lines_after": int(lines_after)
        }
        if file:
            args["file_path"] = os.path.abspath(file)
        if line:
            args["line"] = int(line)
        if session_id:
            args["session_id"] = session_id
        return self.call("get_source_context", args)

    def select_stack_frame(self, frame_index, session_id=None):
        args = {"frame_index": int(frame_index)}
        if session_id:
            args["session_id"] = session_id
        return self.call("select_stack_frame", args)

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
    parser = argparse.ArgumentParser(description="JetBrains Debugger MCP CLI & Comprehensive Client")
    subparsers = parser.add_subparsers(dest="command")

    # Lifecycle & Sessions
    start_p = subparsers.add_parser("start", help="Start debug session for configuration")
    start_p.add_argument("config", nargs="?", default="Minecraft Client", help="Run configuration name")

    stop_p = subparsers.add_parser("stop", help="Stop debug session")
    stop_p.add_argument("--session-id", help="Optional debug session ID")

    stat_p = subparsers.add_parser("status", help="Get full status of current debug session (stack, vars, source)")
    stat_p.add_argument("--session-id", help="Optional debug session ID")

    subparsers.add_parser("list-sessions", help="List all active debug sessions")
    subparsers.add_parser("list-configs", help="List available run configurations")

    run_cfg_p = subparsers.add_parser("run-config", help="Execute run configuration directly")
    run_cfg_p.add_argument("name", help="Run configuration name")
    run_cfg_p.add_argument("--mode", default="debug", choices=["run", "debug"])

    # Breakpoints
    subparsers.add_parser("list-bp", help="List all breakpoints")

    setbp_p = subparsers.add_parser("set-bp", help="Set a breakpoint or tracepoint")
    setbp_p.add_argument("file", help="File path (absolute or relative)")
    setbp_p.add_argument("line", type=int, help="1-based line number")
    setbp_p.add_argument("--condition", help="Conditional expression")
    setbp_p.add_argument("--log", help="Log message expression (tracepoint without stopping)")
    setbp_p.add_argument("--suspend", default="all", choices=["all", "thread", "none"])
    setbp_p.add_argument("--hit-count", type=int, help="Pass count threshold")

    rmbp_p = subparsers.add_parser("remove-bp", help="Remove a breakpoint by ID")
    rmbp_p.add_argument("id", help="Breakpoint ID")

    subparsers.add_parser("clear-all-bp", help="Clear all breakpoints in one command")

    # Execution Flow
    res_p = subparsers.add_parser("resume", help="Resume execution")
    res_p.add_argument("--session-id", help="Optional debug session ID")

    pause_p = subparsers.add_parser("pause", help="Pause execution")
    pause_p.add_argument("--session-id", help="Optional debug session ID")

    step_p = subparsers.add_parser("step", help="Step over / into / out")
    step_p.add_argument("mode", choices=["over", "into", "out"], default="over", nargs="?")
    step_p.add_argument("--session-id", help="Optional debug session ID")

    rtl_p = subparsers.add_parser("run-to-line", help="Run until execution hits specified line")
    rtl_p.add_argument("file", help="File path")
    rtl_p.add_argument("line", type=int, help="1-based line number")
    rtl_p.add_argument("--session-id", help="Optional debug session ID")

    wait_p = subparsers.add_parser("wait", help="Wait for breakpoint pause (blocks until hit or timeout)")
    wait_p.add_argument("--timeout", type=int, default=60, help="Timeout in seconds")
    wait_p.add_argument("--session-id", help="Optional debug session ID")

    # Inspection
    eval_p = subparsers.add_parser("eval", help="Evaluate expression in paused stackframe")
    eval_p.add_argument("expression", help="Expression to evaluate")
    eval_p.add_argument("--frame", type=int, default=0, help="Stack frame index")
    eval_p.add_argument("--session-id", help="Optional debug session ID")

    var_p = subparsers.add_parser("vars", help="Get variables in current frame")
    var_p.add_argument("--frame", type=int, default=0, help="Stack frame index")
    var_p.add_argument("--group-by", default="scope", choices=["scope", "type"])
    var_p.add_argument("--session-id", help="Optional debug session ID")

    setvar_p = subparsers.add_parser("set-var", help="Set variable value in current frame")
    setvar_p.add_argument("name", help="Variable name")
    setvar_p.add_argument("value", help="New value expression")
    setvar_p.add_argument("--frame", type=int, default=0, help="Stack frame index")
    setvar_p.add_argument("--session-id", help="Optional debug session ID")

    src_p = subparsers.add_parser("source", help="Get source context around line")
    src_p.add_argument("file", help="File path")
    src_p.add_argument("line", type=int, help="1-based line number")
    src_p.add_argument("--before", type=int, default=5, help="Lines before")
    src_p.add_argument("--after", type=int, default=5, help="Lines after")

    frame_p = subparsers.add_parser("select-frame", help="Select active stack frame")
    frame_p.add_argument("index", type=int, help="Frame index")
    frame_p.add_argument("--session-id", help="Optional debug session ID")

    th_p = subparsers.add_parser("threads", help="List active threads")
    th_p.add_argument("--session-id", help="Optional debug session ID")

    st_p = subparsers.add_parser("stack", help="Get stack trace")
    st_p.add_argument("--max-frames", type=int, default=20, help="Max frames")
    st_p.add_argument("--thread-id", help="Thread ID")
    st_p.add_argument("--session-id", help="Optional debug session ID")

    # Raw Call
    call_p = subparsers.add_parser("call", help="Call any Debugger MCP tool directly")
    call_p.add_argument("tool", help="Tool name")
    call_p.add_argument("args", nargs="?", default="{}", help="JSON arguments")

    args = parser.parse_args()
    client = DebuggerMcpClient()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    result = None
    if args.command == "start":
        result = client.start_session(args.config)
    elif args.command == "stop":
        result = client.stop_session(args.session_id)
    elif args.command == "status":
        result = client.session_status(args.session_id)
    elif args.command == "list-sessions":
        result = client.list_sessions()
    elif args.command == "list-configs":
        result = client.list_run_configurations()
    elif args.command == "run-config":
        result = client.execute_run_configuration(args.name, mode=args.mode)
    elif args.command == "list-bp":
        result = client.list_breakpoints()
    elif args.command == "set-bp":
        result = client.set_breakpoint(args.file, args.line, condition=args.condition, log_message=args.log, suspend_policy=args.suspend, hit_count=args.hit_count)
    elif args.command == "remove-bp":
        result = client.remove_breakpoint(args.id)
    elif args.command == "clear-all-bp":
        result = client.clear_all_breakpoints()
    elif args.command == "eval":
        result = client.evaluate(args.expression, session_id=args.session_id, frame_index=args.frame)
    elif args.command == "resume":
        result = client.resume(args.session_id)
    elif args.command == "pause":
        result = client.pause(args.session_id)
    elif args.command == "step":
        if args.mode == "over":
            result = client.step_over(args.session_id)
        elif args.mode == "into":
            result = client.step_into(args.session_id)
        elif args.mode == "out":
            result = client.step_out(args.session_id)
    elif args.command == "run-to-line":
        result = client.run_to_line(args.file, args.line, session_id=args.session_id)
    elif args.command == "wait":
        result = client.wait_for_pause(timeout=args.timeout, session_id=args.session_id)
    elif args.command == "vars":
        result = client.get_variables(session_id=args.session_id, frame_index=args.frame, group_by=args.group_by)
    elif args.command == "set-var":
        result = client.set_variable(args.name, args.value, session_id=args.session_id, frame_index=args.frame)
    elif args.command == "source":
        result = client.get_source_context(args.file, args.line, lines_before=args.before, lines_after=args.after)
    elif args.command == "select-frame":
        result = client.select_stack_frame(args.index, session_id=args.session_id)
    elif args.command == "threads":
        result = client.list_threads(session_id=args.session_id)
    elif args.command == "stack":
        result = client.get_stack_trace(session_id=args.session_id, thread_id=args.thread_id, max_frames=args.max_frames)
    elif args.command == "call":
        parsed_args = json.loads(args.args)
        result = client.call(args.tool, parsed_args)

    print(json.dumps(result, indent=2))
    if isinstance(result, dict) and result.get("isError"):
        sys.exit(1)

if __name__ == "__main__":
    main()
