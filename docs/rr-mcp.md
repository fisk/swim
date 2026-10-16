# rr MCP server

`tools/swim-rr-mcp.py` is a stdio MCP server that lets Nemo inspect a single
[`rr`](https://rr-project.org/) replay through a private GDB connection. It
does not execute a shell and it keeps the replay process separate from the
editor process.

Add a server entry to `nemo/nemo.conf` (adjust the roots for your machine):

```properties
mcp.server.rr.command=/absolute/path/to/swim/tools/swim-rr-mcp.py
mcp.server.rr.cwd=/absolute/path/to/project
mcp.server.rr.env.RR_MCP_TRACE_ROOT=/home/you/.local/share/rr
mcp.server.rr.env.RR_MCP_SOURCE_ROOT=/absolute/path/to/project
mcp.server.rr.timeout_seconds=0
mcp.server.rr.trusted=false
```

Leave the server untrusted. Nemo will then request host approval before every
MCP call, including starting a replay and running a GDB diagnostic command.
The server accepts only trace directories beneath `RR_MCP_TRACE_ROOT` and
source breakpoints beneath `RR_MCP_SOURCE_ROOT`.

`timeout_seconds=0` disables Nemo's MCP response deadline. Replay operations
run in the background without an execution deadline. GDB startup/connection
retries and process shutdown retain bounded waits. Restart Nemo after changing
its configuration or updating the server so it discovers the new tools.

The MCP tools are `start_replay`, `status`, `control`, `breakpoint`, `stack`,
`evaluate`, `gdb_command`, `wait`, `output`, `interrupt`, and `stop_replay`. `control` supports both normal
execution (`continue`, `step`, `next`, `finish`) and reverse execution
(`reverse-continue`, `reverse-step`, `reverse-next`, `reverse-finish`).
`gdb_command` blocks shell/process/session-changing GDB commands; use it for
diagnostic inspection only.

## Asynchronous operations

`control`, `breakpoint`, `stack`, `evaluate`, and `gdb_command` return immediately
with an `operation_id` and `state: "running"`. They do not return the eventual
GDB output in that initial response.

1. Call `control` with `{"action":"continue"}` (or another operation).
2. Do other work, or call `wait` with `{"operation_id":1,"seconds":1}` using
   the returned ID. `seconds` defaults to 1 and ranges from 0 to 10. It bounds
   only this poll: a `running` result leaves execution untouched.
3. Repeat `wait` as needed. A `completed` result contains `output`; a `failed`
   result contains `error`. Output is bounded to its last 12,000 characters.

`status` returns immediately and includes the latest operation and its result.
Results remain readable until another operation starts; only the latest is
retained, and old IDs are rejected. There are no unsolicited completion messages.

While an operation runs, `interrupt` sends SIGINT to GDB and returns immediately.
Use `wait` or `status` to observe GDB stopping before issuing another command.
`stop_replay` terminates the processes and marks pending work `cancelled`.
Starting a new replay also stops the old one. Other GDB operations are rejected
while busy, so output cannot be consumed by the wrong request.

Starting a replay still waits for the initial GDB connection. The background
worker never writes to MCP stdout; the request loop alone emits MCP responses.

## Application stdout/stderr

Use `output` to read the application's replayed stdout/stderr, including printed
crash messages. These streams are merged and can also contain rr diagnostics.
They are separate from the GDB command output returned by `wait`/`status`.
The collector drains the pipe continuously, even while no tools are being called.

Start with `output {}`. The response contains `output`, `replay_id`, and
`next_cursor`. Pass the returned values as
`{"replay_id":"<returned ID>","cursor":123}` for subsequent incremental reads.
Each response fits within 11,000 serialized JSON characters, including metadata
and escaped text, below Nemo's default 12,000-character tool-result limit.
If a client has a smaller limit, pass `max_response_chars` (512–11,000) no larger
than that limit. If `has_more` is true, read the next page immediately.
Reads do not consume data, so retrying a cursor is safe. Always include `replay_id` after the first
read to detect a replacement replay rather than silently skipping its output.

For errors near the end, call `output {"direction":"backward"}` without a
cursor. This returns the latest page. Every response includes `total_chars`
(the captured length), `start_cursor`, and `end_cursor` (exclusive), all in
decoded characters. To walk backwards, pass the returned `next_cursor` and
`replay_id` with `direction:"backward"` until `has_more` is false. Text within
each page remains in its original order. In backward mode, the input cursor
is an exclusive upper bound; in forward mode it is an inclusive lower bound.
Omitting a backward cursor takes a fresh snapshot of the current end, while an
explicit cursor remains anchored even when more output arrives. Cursor 0 in
backward mode returns an empty page, not the tail.

All captured output is retained in a private temporary file for the current
replay; older history is not evicted. Memory use and individual responses stay
bounded. The file uses four bytes per decoded character for direct cursor seeks.
`truncated: true` now means a capture/storage failure (see `error`), not ordinary
pagination. If disk storage fails, capture continues draining the process pipe
to avoid hanging the replay, but subsequent output cannot be retained.
Cursors count decoded characters, not bytes.
Split UTF-8 sequences are preserved; invalid bytes are replaced.

Output can be read while an operation is running, at a breakpoint/crash, or after
`stop_replay`. Starting another replay or exiting the MCP server deletes the
temporary output file; it is not a permanent log. `complete` means the
output pipe has closed and been drained, not that a GDB operation has stopped.
An empty page with `complete: false` can be polled again later; pipe collection
can briefly lag behind a GDB stop. `error` reports any capture failure.

Output previously discarded by an older server cannot be recovered from its
buffer: restart the updated MCP server and replay the relevant portion again.
Revisiting execution may emit the same application messages again; cursors track
capture order, not recording time. Output redirected by the application to other
files, or never flushed before its crash, is not part of this stream.
