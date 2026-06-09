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

`mvn package` installs runtime artifacts into:

- `~/.swim/image`
- `~/.swim/plugins`
- `~/.swim/deps/oracle.oracle-java`

The launcher binary in `image/bin/swim` is built from a custom `jlink` image. Runtime code is loaded from `plugins/`, so SWIM can rebuild and reload itself without running directly from `target/`.

## Getting Help

Run `:help` inside SWIM to open the dedicated help workspace. It has a chapter tree on the left and the current chapter on the right, with beginner material, command reference, editor workflows, integrations, configuration notes, and plugin-provided chapters.

The top context bar and `:` command popup discover available key bindings from the active view and configured remaps. Use them for quick local hints, and use `:help` for the fuller tutorial.

Nemo can read the same documentation through the `swim_help` tool or the Nemo chat command `:swim-help [topic]`.

## Feature Areas

The core editor includes modal editing, normal/insert/visual modes, visual block mode, Vim-style operators and text objects, registers, macros, marks, jumps, folds, search, substitutions, splits, buffers, quickfix/location lists, directory browsing, shell workspaces, mouse selection, and project search.

Language tooling is plugin-backed. Java uses the bundled Oracle/NetBeans language-server payload. C and C++ use `clangd` and recognize common source/header extensions including `.c`, `.cc`, `.cpp`, `.cxx`, `.h`, `.hh`, `.hpp`, and `.hxx`. Diagnostics, line highlighting, completion, hovers, code actions, and project diagnostic navigation share the same editor UI.

Workspace apps include Help, Todo, Shell, Git, Mail, Slack, and debugger panels. Todo stores items in an H2 database. Mail and Slack keep their account/workspace data under the SWIM home directory. The Git plugin provides local status/history tools plus GitHub pull-request browsing, saved PR views, fetch, review modes, changed-file navigation, hunk coloring, and plugin help chapters.

Nemo is the built-in assistant. It supports OpenAI-compatible chat/responses providers, web search, MCP servers, delegated workers, plugin tools, bounded context management, host-visible approvals, OS command sandboxing where available, and host-approved editor-control sessions. Nemo-visible editor control is intentionally restricted: private integrations such as Mail are not exposed to Nemo.

## Runtime Layout

This repository currently builds these Maven modules:

- `swim-launcher`: the stable launcher, host API, plugin loading, rebuild/reload boundary, and `jlink` image installer.
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
