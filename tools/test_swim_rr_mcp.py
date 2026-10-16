import importlib.util
import io
import json
import os
from pathlib import Path
import unittest
import subprocess
import sys
import threading
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("rr_mcp", Path(__file__).with_name("swim-rr-mcp.py"))
rr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rr)


class ReplayWaitTest(unittest.TestCase):
    def test_commands_wait_without_deadline(self):
        replay = rr.Replay()
        replay.gdb = Mock()
        replay.gdb.poll.return_value = None
        with patch.object(replay, "_read_prompt", return_value="stopped") as read:
            self.assertEqual("stopped", replay.command("continue"))
            read.assert_called_once_with(None)

    def test_all_control_actions_have_no_timeout(self):
        with patch.object(rr.REPLAY, "submit") as command:
            for action in ("continue", "step", "next", "finish", "reverse-continue",
                           "reverse-step", "reverse-next", "reverse-finish"):
                rr.call("control", {"action": action})
                command.assert_called_with(action)

    def test_wait_survives_old_deadline_and_split_prompt(self):
        replay = rr.Replay()
        replay.gdb = Mock()
        replay.gdb.poll.return_value = None
        with patch.object(rr.time, "monotonic", side_effect=[0, 3600, 7200, 10800]), \
                patch.object(rr, "select_readable", side_effect=[([], [], []), ([1], [], []), ([1], [], [])]), \
                patch.object(rr.os, "read", side_effect=[b"Stopped\n(g", b"db)"]):
            self.assertEqual("Stopped", replay._read_prompt())

    def test_process_exit_still_fails_promptly(self):
        replay = rr.Replay()
        replay.gdb = Mock()
        replay.gdb.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "GDB exited"):
            replay._read_prompt()


class ReplayOutputTest(unittest.TestCase):
    def capture(self):
        capture = rr.ReplayOutput()
        self.addCleanup(capture.close)
        return capture

    def test_incremental_reads_are_repeatable_and_bounded(self):
        capture = self.capture()
        capture._append("a" * (rr.MAX_OUTPUT + 10))
        first = capture.read()
        self.assertLessEqual(len(json.dumps(first, indent=2)), rr.OUTPUT_RESPONSE_BUDGET)
        self.assertTrue(first["has_more"])
        self.assertEqual(first, capture.read())
        second = capture.read(first["next_cursor"], first["replay_id"])
        self.assertEqual("a" * (rr.MAX_OUTPUT + 10), first["output"] + second["output"])
        self.assertFalse(second["has_more"])
        self.assertEqual("", capture.read(second["next_cursor"])["output"])
        capture._append("crash message")
        self.assertEqual("crash message", capture.read(second["next_cursor"])["output"])

    def test_full_history_retained_and_stale_replay_rejected(self):
        capture = self.capture()
        capture._append("first message\n" + "x" * 1_100_000 + "last message")
        result = capture.read()
        self.assertFalse(result["truncated"])
        self.assertTrue(result["output"].startswith("first message\n"))
        self.assertEqual("last message", capture.read(len("first message\n") + 1_100_000)["output"])
        with self.assertRaisesRegex(ValueError, "different replay"):
            self.capture().read(0, result["replay_id"])
        for cursor in (-1, True, "0", capture.length + 1):
            with self.assertRaises(ValueError):
                capture.read(cursor)

    def test_escaped_pages_fit_nemo_limit_and_reconstruct_exact_output(self):
        capture = self.capture()
        original = ('"\\\n\t\x00π😀' * 3000) + "final crash message"
        capture._append(original)
        for budget in (512, rr.OUTPUT_RESPONSE_BUDGET):
            cursor, chunks = 0, []
            while True:
                page = capture.read(cursor, max_response_chars=budget)
                # This is the actual text serialized by main and truncated by Nemo.
                wire = json.dumps(page, indent=2)
                self.assertLessEqual(len(wire), budget)
                parsed = json.loads(wire[:12000])
                self.assertGreater(parsed["next_cursor"], cursor)
                chunks.append(parsed["output"])
                cursor = parsed["next_cursor"]
                if not parsed["has_more"]:
                    break
            self.assertEqual(original, "".join(chunks))

    def test_disk_failure_is_reported_without_stopping_capture(self):
        capture = self.capture()
        capture._append("saved")
        with patch.object(capture.spool, "write", side_effect=OSError("disk full")):
            capture._append("lost")
        capture._append("also lost")
        page = capture.read()
        self.assertTrue(page["truncated"])
        self.assertIn("disk full", page["error"])
        self.assertEqual("saved", page["output"])

    def test_backward_pages_reconstruct_output_and_fit_budget(self):
        capture = self.capture()
        original = ('early message\nπ😀\\"' * 2000) + "final verifier error"
        capture._append(original)
        for budget in (512, rr.OUTPUT_RESPONSE_BUDGET):
            cursor, chunks = None, []
            while True:
                page = capture.read(cursor, direction="backward", max_response_chars=budget)
                self.assertLessEqual(len(json.dumps(page, indent=2)), budget)
                self.assertEqual(len(original), page["total_chars"])
                self.assertEqual(original[page["start_cursor"]:page["end_cursor"]], page["output"])
                if cursor is None:
                    self.assertTrue(page["output"].endswith("final verifier error"))
                else:
                    self.assertEqual(cursor, page["end_cursor"])
                self.assertLess(page["next_cursor"], page["end_cursor"])
                chunks.append(page["output"])
                cursor = page["next_cursor"]
                if not page["has_more"]:
                    break
            self.assertEqual(original, "".join(reversed(chunks)))

    def test_backward_boundaries_and_append_during_pagination(self):
        capture = self.capture()
        self.assertEqual("", capture.read(direction="backward")["output"])
        capture._append("old output")
        tail = capture.read(direction="backward")
        capture._append("new output")
        anchored = capture.read(tail["end_cursor"], direction="backward")
        self.assertEqual("old output", anchored["output"])
        self.assertEqual(20, anchored["total_chars"])
        zero = capture.read(0, direction="backward")
        self.assertEqual("", zero["output"])
        self.assertFalse(zero["has_more"])
        with self.assertRaises(ValueError):
            capture.read(direction="invalid")

    def test_utf8_decoder_handles_split_and_invalid_bytes(self):
        capture = self.capture()
        read_fd, write_fd = os.pipe()
        with os.fdopen(write_fd, "wb") as writer:
            capture.capture(os.fdopen(read_fd, "rb"))
            writer.write("error: π".encode()[:-1])
            writer.flush()
            writer.write("π".encode()[-1:] + b"\xff\xe2")
        capture.thread.join(2)
        self.assertFalse(capture.thread.is_alive())
        self.assertEqual("error: π\ufffd\ufffd", capture.read()["output"])
        self.assertTrue(capture.read()["complete"])

    def test_start_captures_stdout_stderr_without_pipe_backpressure(self):
        process = subprocess.Popen([sys.executable, "-u", "-c",
            "import os; os.write(1, b'x' * 200000); os.write(1, b'\\nstdout marker\\n'); "
            "os.write(2, b'stderr crash marker\\n')"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        replay = rr.Replay()
        fake_gdb = Mock()
        fake_gdb.poll.return_value = None
        try:
            with patch.object(rr.subprocess, "Popen", side_effect=[process, fake_gdb]) as popen, \
                    patch.object(rr, "trace_path", return_value="/synthetic-trace"), \
                    patch.object(rr, "available_port", return_value=12345), \
                    patch.object(replay, "_read_prompt", return_value=""), \
                    patch.object(replay, "command", return_value="connected"):
                started = replay.start("synthetic-trace")
                self.assertEqual(subprocess.PIPE, popen.call_args_list[0].kwargs["stdout"])
            process.wait(5)
            replay.output_capture.thread.join(5)
            chunks = []
            cursor = 0
            while True:
                result = replay.output(cursor, started["replay_id"])
                chunks.append(result["output"])
                cursor = result["next_cursor"]
                if not result["has_more"]:
                    break
            self.assertTrue(result["complete"])
            self.assertIn("stdout marker\nstderr crash marker", "".join(chunks))
            replay.stop()
            self.assertEqual(result, replay.output(cursor - len(result["output"]), started["replay_id"]))
        finally:
            replay.stop()
            if replay.output_capture is not None:
                replay.output_capture.close()


class AsyncReplayTest(unittest.TestCase):
    def setUp(self):
        self.replay = rr.Replay()
        # A pipe-based fake GDB: continue only completes when SIGINT arrives.
        self.process = subprocess.Popen([sys.executable, "-u", "-c", """
import signal, sys, time
stopped = False
def interrupt(signum, frame):
    global stopped
    stopped = True
signal.signal(signal.SIGINT, interrupt)
print('(gdb)', flush=True)
for line in sys.stdin:
    command = line.strip()
    if command == 'continue':
        while not stopped:
            time.sleep(.01)
        stopped = False
        print('Interrupted\\n(gdb)', flush=True)
    elif command == 'exit':
        sys.exit(1)
    else:
        print(command + '\\n(gdb)', flush=True)
"""], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.replay.gdb = self.process
        self.replay._read_prompt(5)

    def tearDown(self):
        self.replay.stop()
        if self.replay.output_capture is not None:
            self.replay.output_capture.close()
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()

    def test_continue_returns_status_and_interrupt_remain_available(self):
        operation = self.replay.submit("continue")
        self.assertEqual("running", operation["state"])
        self.assertEqual("running", self.replay.status()["operation"]["state"])
        self.assertEqual("running", self.replay.wait(operation["operation_id"], .01)["state"])
        with self.assertRaisesRegex(RuntimeError, "busy"):
            self.replay.submit("bt")
        self.assertTrue(self.replay.interrupt()["interrupt_requested"])
        result = self.replay.wait(operation["operation_id"], 5)
        self.assertEqual("completed", result["state"])
        self.assertIn("Interrupted", result["output"])
        # Reading results does not consume them.
        self.assertEqual(result, self.replay.wait(operation["operation_id"], 0))
        next_op = self.replay.submit("info threads", "bt")
        self.assertGreater(next_op["operation_id"], operation["operation_id"])
        self.assertEqual("info threads\n\nbt", self.replay.wait(next_op["operation_id"], 5)["output"])
        with self.assertRaises(ValueError):
            self.replay.wait(operation["operation_id"], 0)

    def test_stop_cancels_running_operation(self):
        operation = self.replay.submit("continue")
        self.replay.stop()
        self.assertEqual("cancelled", self.replay.wait(operation["operation_id"], 0)["state"])
        self.assertFalse(self.replay.status()["active"])
        self.assertFalse(self.replay.interrupt()["interrupt_requested"])

    def test_exit_is_reported_as_failure(self):
        operation = self.replay.submit("exit")
        self.assertEqual("failed", self.replay.wait(operation["operation_id"], 5)["state"])

    def test_invalid_wait_is_rejected(self):
        for seconds in (-1, 11, float("nan"), "1", True):
            with self.assertRaises(ValueError):
                self.replay.wait(1, seconds)

    def test_mcp_loop_serves_requests_while_continue_runs(self):
        requests = [
            ("control", {"action": "continue"}),
            ("status", {}),
            ("output", {}),
            ("interrupt", {}),
            ("wait", {"operation_id": 1, "seconds": 5}),
        ]
        lines = "\n".join(json.dumps({"jsonrpc": "2.0", "id": index,
            "method": "tools/call", "params": {"name": name, "arguments": args}})
            for index, (name, args) in enumerate(requests))
        output = io.StringIO()
        self.replay.output_capture = rr.ReplayOutput()
        self.replay.output_capture._append("synthetic application error")
        with patch.object(rr, "REPLAY", self.replay), \
                patch.object(rr.sys, "stdin", io.StringIO(lines)), \
                patch.object(rr.sys, "stdout", output):
            rr.main()
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([0, 1, 2, 3, 4], [reply["id"] for reply in replies])
        results = [json.loads(reply["result"]["content"][0]["text"]) for reply in replies]
        self.assertEqual("running", results[0]["state"])
        self.assertEqual("running", results[1]["operation"]["state"])
        self.assertEqual("synthetic application error", results[2]["output"])
        self.assertTrue(results[3]["interrupt_requested"])
        self.assertEqual("completed", results[4]["state"])

    def test_old_worker_cannot_overwrite_new_operation(self):
        release = threading.Event()
        entered = threading.Event()
        def delayed(**kwargs):
            if kwargs["process"] is not self.process:
                return "new output"
            entered.set()
            release.wait(5)
            return "old output"
        with patch.object(self.replay, "_read_prompt", side_effect=delayed):
            old = self.replay.submit("bt")
            self.assertTrue(entered.wait(2))
            old_job = self.replay.job
            self.replay.stop()
            self.replay.gdb = Mock()
            self.replay.gdb.poll.return_value = None
            new = self.replay.submit("bt")
            self.assertEqual("new output", self.replay.wait(new["operation_id"], 2)["output"])
            release.set()
            self.assertEqual("cancelled", old_job["state"])
            self.assertEqual("", old_job["output"])
            self.assertEqual(new["operation_id"], self.replay.status()["operation"]["operation_id"])


if __name__ == "__main__":
    unittest.main()
