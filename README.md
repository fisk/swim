# SWIM

SWIM stands for *Seriously Vamped Vi IMproved*. It is a Java terminal editor with Vim-style modal editing, split panes, workspace-level apps, reloadable plugins, language tooling, and the built-in Nemo assistant.

This README is a project map. Day-to-day editor usage, beginner tutorials, and plugin workflows live in the in-editor help system so they stay close to the implementation and keybinding discovery.

## Quick Start

```bash
# 1. Install JDK 25+.
# 2. Clone SWIM into ~/.swim.
git clone <your-fork-or-this-repo-url> ~/.swim
cd ~/.swim

# 3. Build the editor and runtime image.
mvn package

# 4. Launch SWIM.
"$HOME/.swim/image/bin/swim"

# Open one file, several files, or a directory.
"$HOME/.swim/image/bin/swim" README.md
"$HOME/.swim/image/bin/swim" README.md pom.xml
"$HOME/.swim/image/bin/swim" .
```

Startup behavior:

- `swim` opens the welcome screen.
- `swim <file> [file...]` opens the requested file set, creating missing files when possible.
- `swim <directory>` opens the built-in directory browser for that directory.
- Normal launches do not restore an old session. `:reload` and `:rebuild` preserve the current editor state while the runtime restarts.
- Normal launches attach to a background SWIM session server. Use `swim --attach <name>` to attach the terminal client directly to a named session, `:sessions` to list live editor instances, and `:session <name>` to move the current terminal client to another named instance. New session app processes start from the client current working directory when it still exists. If a managed session is wedged, `swim --kill-session <name>` asks the server to terminate that session process tree.

`mvn package` installs runtime artifacts into:

- `~/.swim/image`
- `~/.swim/plugins`
- `~/.swim/deps/oracle.oracle-java`

The launcher in `image/bin/swim` is a single-source Java entry point generated into the custom `jlink` image. Its shebang points at the image's embedded `bin/java`, not whatever `java` happens to be on `PATH`. Runtime code is loaded from `plugins/`, so SWIM can rebuild and reload itself without running directly from `target/`.

## Getting Help

Run `:help` inside SWIM to open the dedicated help workspace. It has a chapter tree on the left and the current chapter on the right, with beginner material, command reference, editor workflows, integrations, configuration notes, and plugin-provided chapters.

The top context bar and `:` command popup discover available key bindings from the active view and configured remaps. Use them for quick local hints, and use `:help` for the fuller tutorial.

Nemo can read the same documentation through the `swim_help` tool or the Nemo chat command `:swim-help [topic]`.

## Feature Areas

The core editor includes modal editing, normal/insert/visual modes, visual block mode, Vim-style operators and text objects, registers, macros, marks, jumps, folds, search, substitutions, splits, project-scoped buffer groups, stable bottom tabs with rename/reorder support, quickfix/location lists, directory browsing, shell workspaces, mouse selection, project search, and a tmux-style `Ctrl-b` prefix for tab and frame management.

Language tooling is plugin-backed. Java uses the bundled Oracle/NetBeans language-server payload. C and C++ use `clangd` and recognize common source/header extensions including `.c`, `.cc`, `.cpp`, `.cxx`, `.h`, `.hh`, `.hpp`, and `.hxx`. Diagnostics, line highlighting, completion, hovers, code actions, and project diagnostic navigation share the same editor UI.

Workspace apps include Help, Todo, Shell, Git, Mail, Slack, and debugger panels. Todo stores items in an H2 database. Mail and Slack keep their account/workspace data under the SWIM home directory. The Git plugin provides local status/history tools plus GitHub pull-request browsing, saved PR views, fetch, review modes, changed-file navigation, hunk coloring, and plugin help chapters.

Nemo is the built-in assistant and harness. It uses the langchain4j chat backend for OpenAI-compatible models and other supported providers, and adds web search, MCP servers, delegated workers, plugin tools, bounded context management, host-visible approvals, and OS command sandboxing where available. Nemo can either work through file/project tools or, with explicit host approval, directly control the editor through sandbox-aware key streams and host-filtered screen snapshots. Nemo-visible editor control is intentionally restricted: private integrations such as Mail and host-only notifications are not exposed to Nemo.

## Architecture

SWIM is split into a small launch boundary, a session server, and versioned editor runtimes. The launcher should stay deliberately thin: it resolves the build/image location, starts or connects to the session server, builds an app launch command for the version being invoked, sends explicit attach/kill requests, and relays raw terminal I/O. The session server owns live sessions, terminal attachment, detachment, switching, and process-tree termination. App processes are launched directly with stdio pipes; SWIM does not depend on an external `script`/PTY wrapper between the server and editor runtime.

Each live editor session is a separate app process. When a client attaches, it sends the server the client current working directory, the user launch arguments, and the exact app command that should start that session. That is the version boundary: after `:rebuild`, new sessions can start with the rebuilt runtime while existing sessions keep running the older command they were created with. The server must not derive app launch commands from its own code version.

Detaching a client, such as with `:detach` or the tmux-style `Ctrl-b d` binding, leaves the server-side session running. `:q` follows Vim window semantics: it closes the current frame first, then the current tab, and only exits the app process when the last tab is gone. The tmux-style `Ctrl-b &` binding closes the current tab directly. App process exit removes the session from the server.

The session server is intentionally a separate `swim-session` module. It exposes the live-session API used by the editor and Nemo, implements the Unix-domain socket protocol, and detaches itself from the terminal. Its JVM is sized for long-lived coordination with `-XX:+UseZGC`, `-Xmx4G`, and `-XX:SoftMaxHeapSize=1G`; individual editor app processes keep their own runtime policy.

## Runtime Layout

This repository currently builds these Maven modules:

- `swim-launcher`: the stable host API, thin client launch path, plugin loading, rebuild/reload boundary, and `jlink` image installer.
- `swim-session`: the background session server, Unix-domain socket protocol, and public live-session API.
- `swim-core`: the editor core, UI, modes, commands, help system, shell, Todo, Nemo, LSP/debug integration points, and shared runtime services.
- `swim-java-lsp`: Java language-support plugin.
- `swim-clangd-lsp`: C/C++ language-support plugin.
- `swim-java-debug`: Java debugger provider plugin.
- `swim-cpp-debug`: C/C++ debugger provider plugin.
- `swim-tree-view`: project tree plugin.
- `swim-email`: mail workspace plugin.
- `swim-git`: Git and GitHub review workspace plugin.
- `swim-slack`: Slack workspace plugin.

Installed plugin jars and their shared runtime dependencies live under `plugins/`. Plugins can preload lightweight metadata such as help chapters and key bindings before their full UI/runtime code is loaded.

The main plugin API lives under `swim-launcher/src/main/java/org/fisk/swim/api/`. Existing plugin modules are the best references for new plugins because they show the current preload, keybinding, panel, command, help, and Nemo-tool integration patterns.

## Configuration And State

SWIM reads user configuration from `~/.swim/config.json`. That file is for editor options, normal-mode remaps, and startup commands.

Runtime state is kept under the SWIM home directory:

- `~/.swim/session.json`: reload/rebuild session snapshot.
- `~/.swim/nemo/`: Nemo configuration, sessions, approvals, and related assistant state.
- `~/.swim/email/`: mail account configuration and local mail database.
- `~/.swim/slack/`: Slack workspace configuration and cache.
- `~/.swim/todo/`: Todo H2 database.

Project-local `.swim` files or directories are used for repository/project settings such as C/C++ compilation database location and Git pull-request saved views. The relevant help chapters document the exact keys.

## Development

Useful commands while working on SWIM:

```bash
mvn test
mvn package
"$HOME/.swim/image/bin/swim" .
```

Inside a running editor:

- `:reload` reloads already-built runtime plugins.
- `:rebuild` rebuilds the project and then reloads.
- `:upgrade` is an alias for `:rebuild`.

When changing Nemo editor-control behavior, read `AGENTS.md` first. It documents the sandboxing boundary and the expectation that commands, key actions, workspace actions, and plugin actions explicitly decide whether they are allowed during Nemo-driven editor control.

## License

SWIM is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE). Third-party dependencies remain under their own licenses.
