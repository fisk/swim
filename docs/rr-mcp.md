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
mcp.server.rr.timeout_seconds=90
mcp.server.rr.trusted=false
```

Leave the server untrusted. Nemo will then request host approval before every
MCP call, including starting a replay and running a GDB diagnostic command.
The server accepts only trace directories beneath `RR_MCP_TRACE_ROOT` and
source breakpoints beneath `RR_MCP_SOURCE_ROOT`.

The MCP tools are `start_replay`, `status`, `control`, `breakpoint`, `stack`,
`evaluate`, `gdb_command`, and `stop_replay`. `control` supports both normal
execution (`continue`, `step`, `next`, `finish`) and reverse execution
(`reverse-continue`, `reverse-step`, `reverse-next`, `reverse-finish`).
`gdb_command` blocks shell/process/session-changing GDB commands; use it for
diagnostic inspection only.
