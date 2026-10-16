#!/usr/bin/env python3
"""A small, stdio MCP server for inspecting an rr replay with GDB.

The server deliberately owns one replay at a time.  It accepts only a trace
under RR_MCP_TRACE_ROOT (default: ~/.local/share/rr), starts `rr replay`, and
connects GDB to its local remote stub.  It never invokes a shell.
"""

import codecs
import json
import os
import re
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid


MAX_OUTPUT = 12000
OUTPUT_RESPONSE_BUDGET = 11000
PROMPT = b"(gdb)"
ROOT = os.path.realpath(os.environ.get("RR_MCP_TRACE_ROOT", os.path.expanduser("~/.local/share/rr")))
RR = os.environ.get("RR_MCP_RR", "rr")
GDB = os.environ.get("RR_MCP_GDB", "gdb")


class ReplayOutput:
    """Disk-backed output with non-consuming character cursors and bounded pages."""
    def __init__(self):
        self.replay_id = uuid.uuid4().hex
        self.lock = threading.Lock()
        # Fixed-width storage permits seeking by character without an in-memory index.
        # TemporaryFile is private and unlinked automatically; never expose a host path.
        self.spool = tempfile.TemporaryFile(mode="w+b")
        self.length = 0
        self.complete = False
        self.error = None
        self.thread = None

    def capture(self, stream):
        self.thread = threading.Thread(target=self._drain, args=(stream,),
                                       name="rr-mcp-output", daemon=True)
        self.thread.start()

    def _append(self, text):
        with self.lock:
            if self.spool.closed or self.error is not None:
                return
            try:
                self.spool.seek(self.length * 4)
                self.spool.write(text.encode("utf-32-le"))
                self.spool.flush()
                self.length += len(text)
            except OSError as error:
                # Keep draining the child pipe even if storage fails.
                self.error = "Output storage failed: " + str(error)[:300]

    def close(self):
        with self.lock:
            self.spool.close()

    def _drain(self, stream):
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        try:
            with stream:
                while True:
                    data = os.read(stream.fileno(), 16384)
                    if not data:
                        break
                    self._append(decoder.decode(data))
                self._append(decoder.decode(b"", final=True))
        except OSError as error:
            with self.lock:
                self.error = str(error)
        finally:
            with self.lock:
                self.complete = True

    def read(self, cursor=None, replay_id=None, max_response_chars=OUTPUT_RESPONSE_BUDGET,
             direction="forward"):
        if direction not in ("forward", "backward"):
            raise ValueError("direction must be forward or backward")
        if cursor is not None and (isinstance(cursor, bool) or not isinstance(cursor, int) or cursor < 0):
            raise ValueError("cursor must be a non-negative integer")
        if (isinstance(max_response_chars, bool) or not isinstance(max_response_chars, int)
                or not 512 <= max_response_chars <= OUTPUT_RESPONSE_BUDGET):
            raise ValueError("max_response_chars must be an integer between 512 and 11000")
        with self.lock:
            if replay_id is not None and replay_id != self.replay_id:
                raise ValueError("Output belongs to a different replay; restart at cursor 0")
            end = self.length
            if cursor is None:
                cursor = end if direction == "backward" else 0
            if cursor > end:
                raise ValueError("cursor is beyond the captured output")
            backward = direction == "backward"
            begin = max(0, cursor - max_response_chars) if backward else cursor
            limit = cursor if backward else min(end, cursor + max_response_chars)
            self.spool.seek(begin * 4)
            text = self.spool.read((limit - begin) * 4).decode("utf-32-le")
            def page(count):
                start = cursor - count if backward else cursor
                stop = cursor if backward else cursor + count
                return {"replay_id": self.replay_id, "total_chars": end,
                        "start_cursor": start, "end_cursor": stop,
                        "next_cursor": start if backward else stop, "direction": direction,
                        "has_more": start > 0 if backward else stop < end, "complete": self.complete,
                        "truncated": self.error is not None, "error": self.error,
                        "output": (text[len(text) - count:] if backward else text[:count])}
            # Account for JSON escaping and metadata, not just raw output length.
            low, high = 0, len(text)
            while low < high:
                middle = (low + high + 1) // 2
                if len(json.dumps(page(middle), indent=2)) <= max_response_chars:
                    low = middle
                else:
                    high = middle - 1
            result = page(low)
            if len(json.dumps(result, indent=2)) > max_response_chars:
                raise ValueError("max_response_chars is too small for output metadata")
            return result


class Replay:
    def __init__(self):
        self.rr = None
        self.gdb = None
        self.trace = None
        self.port = None
        self.lock = threading.RLock()
        self.job = None
        self.next_job_id = 1
        self.output_capture = None

    def start(self, trace):
        with self.lock:
            self.stop()
            path = trace_path(trace)
            port = available_port()
            if self.output_capture is not None:
                self.output_capture.close()
            self.output_capture = ReplayOutput()
            self.rr = subprocess.Popen([RR, "replay", "-s", str(port), path], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.output_capture.capture(self.rr.stdout)
            self.trace, self.port = path, port
            try:
                self.gdb = subprocess.Popen([GDB, "--quiet", "--nx"], stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                self._read_prompt(10)
                self.command("set pagination off", 10)
                self.command("set confirm off", 10)
                # rr may need a short moment to bind its replay stub.  Retry the
                # GDB request, not by probing the socket (the stub allows one GDB).
                last_error = ""
                for _ in range(40):
                    output = self.command("target extended-remote :%d" % port, 2)
                    if "Connection refused" not in output and "Connection timed out" not in output:
                        return {"trace": path, "port": port, "gdb": output,
                                "replay_id": self.output_capture.replay_id}
                    last_error = output
                    time.sleep(.1)
                raise RuntimeError("rr replay stub did not accept GDB: " + last_error)
            except Exception:
                self.stop()
                raise

    def command(self, command, timeout=None):
        if self.gdb is None or self.gdb.poll() is not None:
            raise RuntimeError("No active rr replay. Call start_replay first.")
        self.gdb.stdin.write((command + "\n").encode("utf-8"))
        self.gdb.stdin.flush()
        return self._read_prompt(timeout)

    def _read_prompt(self, timeout=None, process=None):
        process = self.gdb if process is None else process
        deadline = None if timeout is None else time.monotonic() + timeout
        result = bytearray()
        while deadline is None or time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("GDB exited while waiting for a response")
            readable, _, _ = select_readable(process.stdout, .1)
            if not readable:
                continue
            data = os.read(process.stdout.fileno(), 4096)
            if not data:
                raise RuntimeError("GDB closed its output")
            result.extend(data)
            if PROMPT in result:
                return result.rsplit(PROMPT, 1)[0].decode("utf-8", "replace").strip()
            # Keep the tail (including a possibly split prompt), not unbounded output.
            if len(result) > MAX_OUTPUT:
                del result[:-MAX_OUTPUT]
        raise RuntimeError("Timed out waiting for GDB")

    def submit(self, *commands):
        with self.lock:
            if self.job is not None and self.job["state"] == "running":
                raise RuntimeError("Replay is busy; use status, wait, interrupt, or stop_replay")
            if self.gdb is None or self.gdb.poll() is not None:
                raise RuntimeError("No active rr replay. Call start_replay first.")
            job = {"operation_id": self.next_job_id, "state": "running",
                   "output": "", "done": threading.Event()}
            self.next_job_id += 1
            process = self.gdb
            # Queue the command before exposing the running operation.
            process.stdin.write((commands[0] + "\n").encode("utf-8"))
            process.stdin.flush()
            self.job = job
            threading.Thread(target=self._finish_job, args=(job, process, commands),
                             name="rr-mcp-command", daemon=True).start()
            return self._job_result(job)

    def _finish_job(self, job, process, commands):
        try:
            output = self._read_prompt(process=process)
            for command in commands[1:]:
                with self.lock:
                    if job["state"] != "running":
                        return
                    process.stdin.write((command + "\n").encode("utf-8"))
                    process.stdin.flush()
                output += "\n\n" + self._read_prompt(process=process)
            with self.lock:
                if job["state"] == "running":
                    job.update(state="completed", output=output[-MAX_OUTPUT:])
        except Exception as error:
            with self.lock:
                if job["state"] == "running":
                    job.update(state="failed", error=str(error))
        finally:
            job["done"].set()

    @staticmethod
    def _job_result(job):
        return {key: value for key, value in job.items() if key != "done"}

    def wait(self, operation_id, seconds=1):
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not 0 <= seconds <= 10:
            raise ValueError("seconds must be between 0 and 10; this only bounds the poll, not execution")
        with self.lock:
            job = self.job
            if job is None or job["operation_id"] != operation_id:
                raise ValueError("Unknown operation_id; only the latest operation is retained")
        job["done"].wait(seconds)
        with self.lock:
            return self._job_result(job)

    def interrupt(self):
        with self.lock:
            if self.job is None or self.job["state"] != "running":
                return {"interrupt_requested": False}
            if self.gdb is not None and self.gdb.poll() is None:
                self.gdb.send_signal(signal.SIGINT)
            return {"interrupt_requested": True, **self._job_result(self.job)}

    def stop(self):
        with self.lock:
            if self.job is not None and self.job["state"] == "running":
                self.job["state"] = "cancelled"
                self.job["done"].set()
            self._stop_processes()

    def _stop_processes(self):
        for process in (self.gdb, self.rr):
            if process is None:
                continue
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        if self.output_capture is not None and self.output_capture.thread is not None:
            # The collector has its own lock, so it can drain final bytes during shutdown.
            self.output_capture.thread.join(2)
        self.rr = self.gdb = self.trace = self.port = None

    def output(self, cursor=None, replay_id=None, max_response_chars=OUTPUT_RESPONSE_BUDGET,
               direction="forward"):
        with self.lock:
            capture = self.output_capture
        if capture is None:
            raise RuntimeError("No replay output available. Call start_replay first.")
        return capture.read(cursor, replay_id, max_response_chars, direction)

    def status(self):
        with self.lock:
            return {"active": self.gdb is not None and self.gdb.poll() is None, "trace": self.trace,
                "port": self.port, "rr_pid": None if self.rr is None else self.rr.pid,
                "gdb_pid": None if self.gdb is None else self.gdb.pid,
                "operation": None if self.job is None else self._job_result(self.job)}


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
    tool("output", "Read disk-backed application stdout/stderr and rr diagnostics. For crash messages use direction=backward without cursor to read the tail. total_chars reports captured length. To page in either direction, pass next_cursor, replay_id and the same direction until has_more is false. Text stays in chronological order within each page. Forward (default) starts at 0; backward reads before its exclusive cursor. No history is evicted. max_response_chars bounds serialized pages (default 11000). truncated indicates capture failure, not pagination.",
         {"cursor": {"type": "integer", "minimum": 0}, "replay_id": {"type": "string"},
          "direction": {"type": "string", "enum": ["forward", "backward"]},
          "max_response_chars": {"type": "integer", "minimum": 512, "maximum": OUTPUT_RESPONSE_BUDGET}}),
    tool("wait", "Poll the latest operation for up to 10 seconds. Running operations are never timed out. Read output when completed.",
         {"operation_id": {"type": "integer"}, "seconds": {"type": "number", "minimum": 0, "maximum": 10}}, ("operation_id",)),
    tool("interrupt", "Request interruption of the running GDB operation. Use wait/status to observe its stop.", {}),
    tool("control", "Start replay movement asynchronously; returns operation_id. Use wait/status for completion and output. Reverse actions move backward.",
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

for descriptor in TOOLS:
    if descriptor["name"] in {"breakpoint", "stack", "evaluate", "gdb_command"}:
        descriptor["description"] += " Asynchronous: returns operation_id; use wait/status for output. Requires an idle replay."


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
    if name == "output": return REPLAY.output(args.get("cursor"), args.get("replay_id"),
                                             args.get("max_response_chars", OUTPUT_RESPONSE_BUDGET),
                                             args.get("direction", "forward"))
    if name == "wait": return REPLAY.wait(args.get("operation_id"), args.get("seconds", 1))
    if name == "interrupt": return REPLAY.interrupt()
    if name == "stop_replay": REPLAY.stop(); return {"stopped": True}
    if name == "control":
        commands = {"continue": "continue", "step": "step", "next": "next", "finish": "finish",
                    "reverse-continue": "reverse-continue", "reverse-step": "reverse-step",
                    "reverse-next": "reverse-next", "reverse-finish": "reverse-finish"}
        action = args.get("action")
        if action not in commands: raise ValueError("Unknown replay action: " + str(action))
        return REPLAY.submit(commands[action])
    if name == "breakpoint":
        action = args.get("action")
        if action == "list": return REPLAY.submit("info breakpoints")
        if action == "clear": return REPLAY.submit("delete %d" % int(args.get("number")))
        if action == "set": return REPLAY.submit("break %s:%d" % (source_path(args.get("file")), int(args.get("line"))))
        raise ValueError("Unknown breakpoint action: " + str(action))
    if name == "stack": return REPLAY.submit("info threads", "bt")
    if name == "evaluate": return REPLAY.submit("print " + args.get("expression", ""))
    if name == "gdb_command": return REPLAY.submit(safe_command(args.get("command", "")))
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
                response(request_id, {"content": [{"type": "text", "text": text}], "isError": False})
            else: response(request_id, error="Unknown MCP method: " + str(method))
        except Exception as error:
            response(request.get("id") if 'request' in locals() else None, error=error)


if __name__ == "__main__":
    try: main()
    finally:
        REPLAY.stop()
        if REPLAY.output_capture is not None:
            REPLAY.output_capture.close()
