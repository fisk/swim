#!/usr/bin/env python3
"""A small, stdio MCP server for inspecting an rr replay with GDB.

The server deliberately owns one replay at a time.  It accepts only a trace
under RR_MCP_TRACE_ROOT (default: ~/.local/share/rr), starts `rr replay`, and
connects GDB to its local remote stub.  It never invokes a shell.
"""

import json
import os
import re
import socket
import subprocess
import sys
import threading
import time


MAX_OUTPUT = 12000
PROMPT = b"(gdb)"
ROOT = os.path.realpath(os.environ.get("RR_MCP_TRACE_ROOT", os.path.expanduser("~/.local/share/rr")))
RR = os.environ.get("RR_MCP_RR", "rr")
GDB = os.environ.get("RR_MCP_GDB", "gdb")


class Replay:
    def __init__(self):
        self.rr = None
        self.gdb = None
        self.trace = None
        self.port = None
        self.lock = threading.Lock()

    def start(self, trace):
        with self.lock:
            self.stop()
            path = trace_path(trace)
            port = available_port()
            self.rr = subprocess.Popen([RR, "replay", "-s", str(port), path], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            self.trace, self.port = path, port
            try:
                self.gdb = subprocess.Popen([GDB, "--quiet", "--nx"], stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                self._read_prompt(10)
                # rr may need a short moment to bind its replay stub.  Retry the
                # GDB request, not by probing the socket (the stub allows one GDB).
                last_error = ""
                for _ in range(40):
                    output = self.command("target extended-remote :%d" % port, 2)
                    if "Connection refused" not in output and "Connection timed out" not in output:
                        return {"trace": path, "port": port, "gdb": output}
                    last_error = output
                    time.sleep(.1)
                raise RuntimeError("rr replay stub did not accept GDB: " + last_error)
            except Exception:
                self.stop()
                raise

    def command(self, command, timeout=30):
        if self.gdb is None or self.gdb.poll() is not None:
            raise RuntimeError("No active rr replay. Call start_replay first.")
        self.gdb.stdin.write((command + "\n").encode("utf-8"))
        self.gdb.stdin.flush()
        return self._read_prompt(timeout)

    def _read_prompt(self, timeout):
        deadline = time.monotonic() + timeout
        result = bytearray()
        while time.monotonic() < deadline:
            if self.gdb.poll() is not None:
                raise RuntimeError("GDB exited while waiting for a response")
            readable, _, _ = select_readable(self.gdb.stdout, .1)
            if not readable:
                continue
            data = os.read(self.gdb.stdout.fileno(), 4096)
            if not data:
                raise RuntimeError("GDB closed its output")
            result.extend(data)
            if PROMPT in result:
                return result.rsplit(PROMPT, 1)[0].decode("utf-8", "replace").strip()
        raise RuntimeError("Timed out waiting for GDB")

    def stop(self):
        for process in (self.gdb, self.rr):
            if process is None:
                continue
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(2)
                except subprocess.TimeoutExpired:
                    process.kill()
        self.rr = self.gdb = self.trace = self.port = None

    def status(self):
        return {"active": self.gdb is not None and self.gdb.poll() is None, "trace": self.trace,
                "port": self.port, "rr_pid": None if self.rr is None else self.rr.pid,
                "gdb_pid": None if self.gdb is None else self.gdb.pid}


def select_readable(stream, timeout):
    import select
    return select.select([stream], [], [], timeout)


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def trace_path(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("trace must be a non-empty rr trace name or path")
    candidate = value if os.path.isabs(value) else os.path.join(ROOT, value)
    candidate = os.path.realpath(candidate)
    if os.path.commonpath([ROOT, candidate]) != ROOT:
        raise ValueError("trace must be inside RR_MCP_TRACE_ROOT: " + ROOT)
    if not os.path.isdir(candidate):
        raise ValueError("rr trace directory does not exist: " + candidate)
    return candidate


def tool(name, description, properties, required=()):
    return {"name": name, "description": description,
            "inputSchema": {"type": "object", "properties": properties, "required": list(required),
                            "additionalProperties": False}}


TOOLS = [
    tool("start_replay", "Start one rr trace replay. Trace must be below the configured trace root.",
         {"trace": {"type": "string", "description": "Trace name (for example java-7) or path below RR_MCP_TRACE_ROOT."}},
         ("trace",)),
    tool("status", "Report whether an rr replay and its GDB connection are active.", {}),
    tool("control", "Move the rr replay. Reverse actions move backward through the recording.",
         {"action": {"type": "string", "enum": ["continue", "step", "next", "finish", "reverse-continue",
                     "reverse-step", "reverse-next", "reverse-finish"]}}, ("action",)),
    tool("breakpoint", "Set, clear, or list breakpoints in the replay. Source paths must be below RR_MCP_SOURCE_ROOT when set.",
         {"action": {"type": "string", "enum": ["set", "clear", "list"]},
          "file": {"type": "string"}, "line": {"type": "integer", "minimum": 1},
          "number": {"type": "integer", "minimum": 1}}, ("action",)),
    tool("stack", "Return the current thread list and backtrace.", {}),
    tool("evaluate", "Evaluate a GDB expression in the current replay state.",
         {"expression": {"type": "string", "maxLength": 2000}}, ("expression",)),
    tool("gdb_command", "Run a read-only GDB console command for diagnosis. Shell, Python, target, file, run, attach and quit commands are blocked.",
         {"command": {"type": "string", "maxLength": 2000}}, ("command",)),
    tool("stop_replay", "Stop GDB and the rr replay process.", {}),
]


def source_path(value):
    root = os.path.realpath(os.environ.get("RR_MCP_SOURCE_ROOT", os.getcwd()))
    path = os.path.realpath(value if os.path.isabs(value) else os.path.join(root, value))
    if os.path.commonpath([root, path]) != root:
        raise ValueError("source path must be inside RR_MCP_SOURCE_ROOT: " + root)
    return path


def safe_command(command):
    command = command.strip()
    if not command or len(command) > 2000:
        raise ValueError("GDB command must be 1 to 2000 characters")
    first = command.split(None, 1)[0].lower()
    if first in {"shell", "python", "source", "target", "file", "run", "attach", "quit", "kill"}:
        raise ValueError("GDB command is blocked by the rr MCP safety policy: " + first)
    return command


REPLAY = Replay()


def call(name, args):
    if name == "start_replay": return REPLAY.start(args.get("trace"))
    if name == "status": return REPLAY.status()
    if name == "stop_replay": REPLAY.stop(); return {"stopped": True}
    if name == "control":
        commands = {"continue": "continue", "step": "step", "next": "next", "finish": "finish",
                    "reverse-continue": "reverse-continue", "reverse-step": "reverse-step",
                    "reverse-next": "reverse-next", "reverse-finish": "reverse-finish"}
        action = args.get("action")
        if action not in commands: raise ValueError("Unknown replay action: " + str(action))
        return REPLAY.command(commands[action], 60)
    if name == "breakpoint":
        action = args.get("action")
        if action == "list": return REPLAY.command("info breakpoints")
        if action == "clear": return REPLAY.command("delete %d" % int(args.get("number")))
        if action == "set": return REPLAY.command("break %s:%d" % (source_path(args.get("file")), int(args.get("line"))))
        raise ValueError("Unknown breakpoint action: " + str(action))
    if name == "stack": return REPLAY.command("info threads") + "\n\n" + REPLAY.command("bt")
    if name == "evaluate": return REPLAY.command("print " + args.get("expression", ""))
    if name == "gdb_command": return REPLAY.command(safe_command(args.get("command", "")))
    raise ValueError("Unknown tool: " + str(name))


def response(request_id, result=None, error=None):
    body = {"jsonrpc": "2.0", "id": request_id}
    if error is not None: body["error"] = {"code": -32000, "message": str(error)}
    else: body["result"] = result
    print(json.dumps(body), flush=True)


def main():
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if "id" not in request: continue
            method, request_id = request.get("method"), request["id"]
            if method == "initialize":
                response(request_id, {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                                      "serverInfo": {"name": "swim-rr", "version": "1.0"}})
            elif method == "tools/list": response(request_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = request.get("params", {})
                result = call(params.get("name"), params.get("arguments", {}))
                text = json.dumps(result, indent=2) if isinstance(result, dict) else str(result)
                response(request_id, {"content": [{"type": "text", "text": text[:MAX_OUTPUT]}], "isError": False})
            else: response(request_id, error="Unknown MCP method: " + str(method))
        except Exception as error:
            response(request.get("id") if 'request' in locals() else None, error=error)


if __name__ == "__main__":
    try: main()
    finally: REPLAY.stop()
