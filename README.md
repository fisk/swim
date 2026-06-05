# SWIM

SWIM stands for *Strange Variety Vi Improved*. It is a Java modal editor with Vim-style movement, embedded Java LSP support, live core reloading, and a plugin-based runtime layout.

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

# Or open a file directly.
"$HOME/.swim/image/bin/swim" README.md

# Or start in directory-browse mode.
"$HOME/.swim/image/bin/swim" .
```

Startup behavior:

- `swim` opens an untitled scratch buffer.
- `swim <file>` opens that file and creates it if needed.
- `swim <directory>` opens the built-in directory browser for that directory.

`mvn package` installs runtime artifacts into:

- `~/.swim/image`
- `~/.swim/plugins`
- `~/.swim/deps/oracle.oracle-java`

The launcher binary in `image/bin/swim` is built from a custom `jlink` image. Runtime code is loaded from `plugins/`, so `:rebuild` can rebuild in place and then reload freshly built plugin jars without running directly from `target/`.

## Runtime Layout

`plugins/` contains the runtime modules that SWIM loads:

- `swim-core-...jar`: the editor core plugin
- `swim-java-lsp-...jar`: Java language support
- `swim-clangd-lsp-...jar`: C/C++ language support via `clangd`
- `swim-tree-view-...jar`: the project tree plugin
- `swim-slack-...jar`: the Slack client plugin
- `runtime-libs/`: shared runtime dependencies for the plugin layer

The Oracle Java VS Code extension payload is installed automatically during the build under `deps/oracle.oracle-java`, so Java support does not depend on a separate VS Code installation.

## Everyday Editing

Normal mode uses familiar Vim-style keys:

| Key | Action |
| --- | --- |
| `h/j/k/l` | Move left/down/up/right |
| `0/$` | Start/end of line |
| `gg/G` | Top/bottom of buffer |
| `w/b/e` | Word motions |
| `d{motion}` / `c{motion}` / `y{motion}` | Delete / change / yank |
| `p/P` | Paste after / before |
| `.` | Repeat the last edit |
| `q{register}` / `q` / `@{register}` / `@@` | Record macro / stop / play / replay last |
| `u` / `Ctrl-r` | Undo / redo |
| `i` | Enter insert mode |
| `v`, `V`, `Ctrl-v` | Visual, visual-line, visual-block |
| `:` | Open command line |
| `!` | Start Nemo / open Nemo chat |
| `>` | Open shell panel |
| `/` or `?` | Search |
| `m` | Project file list |
| `e` | Mail client |
| `g m{char}` | Set mark |
| `` `{char}` / `'{char}`` | Jump to mark |
| `g w{char}` / `g c{char}` | Fancy jump to visible word starts / visible matching characters |
| `g ]` / `g [` | Next / previous project diagnostic |
| `g }` / `g {` | Next / previous project error |
| `g x` / `g a` | Show diagnostics for the current line / show code actions |
| `g n` / `g N` / `g C` | Add next/previous multicursor / clear extras |
| `Ctrl-o` / `Tab` | Jump backward / forward |
| `t` | Tree view plugin |

## Discoverability

SWIM now exposes more of the editor while you work:

- The top context bar is metadata-driven: visible key paths are grouped and documented instead of being rendered from one hard-coded sentence.
- In NORMAL mode, the top bar expands into a small dropdown after the first prefix key and shows only matching continuations for that key path.
- Visible keys are grouped into clusters such as navigation, editing, panes, shell, workspace, and code actions.
- When a row or set of groups does not fit, the bar pages back and forth through the available options instead of truncating everything permanently.
- The header also shows recent fullscreen windows in MRU order.
- When the command line is active, the top menu switches to command-specific hints.
- Typing `:` opens a command popup with matching commands for the current prefix.
- The command popup uses the full window width and can grow taller when longer descriptions need more room.
- `Up` and `Down` move through command matches, and `Tab` completes the selected command.
- Typing `!` opens the Nemo chat pane.
- Typing `>` opens or reuses a shell panel using your configured `$SHELL` (for example `zsh`).
- `w <number> Enter` switches to one of the recent fullscreen windows shown in the header.

This makes it possible to explore commands and key chains without memorizing everything up front.

## User Config

SWIM reads `~/.swim/config.json` for editor startup behavior.

Current config surface:

```json
{
  "normalModeRemaps": [
    {
      "lhs": "Q",
      "rhs": "x"
    }
  ],
  "startupCommands": [
    "help"
  ],
  "options": {
    "indent.java.size": "2"
  }
}
```

- `normalModeRemaps` rewrites exact NORMAL-mode key sequences using SWIM key notation such as `Q`, `g g`, `<CTRL>-o`, and `<ESC>`.
- `startupCommands` runs `:` commands during startup.
- `options` sets editor options. Keys starting with `indent.` map onto indentation settings such as `indent.java.size`, `indent.c.string`, and `indent.default.size`.
- Normal launches open the requested path, or an empty scratch buffer when no path is supplied.
- `:reload` and `:rebuild` preserve the current buffer set and split topology through `~/.swim/session.json` while the runtime restarts.

## Windows, Panes, And Panels

SWIM now has two layout levels:

- Fullscreen windows (workspaces) for buffers, directory browsing, and mail.
- Fullscreen windows (workspaces) for buffers, directory browsing, mail, and Slack.
- Fullscreen shell workspaces opened with `:shell` or `Ctrl-g c`.
- Split panes inside the active buffer window.
- Side/bottom panels for contextual tools such as project search, shell, Nemo chat, and plugin views.

Window behavior:

- Opening a directory creates a fullscreen directory-browse window.
- Opening mail with `e` creates a fullscreen mail window.
- Opening Slack with `:slack` creates a fullscreen Slack window.
- `:shell` creates a fullscreen shell workspace.
- Typing `>` opens a bottom shell panel for quick commands without leaving the current window.
- Opening a file from another window creates or activates a buffer window for that file.
- The top header shows recent windows in most-recently-used order.
- `w <number> Enter` switches to the numbered recent window.

Buffer-pane controls from the command line:

- `:split` or `:sp` opens a pane below the active pane
- `:vsplit` or `:vs` opens a pane to the right
- `:focus left|right|up|down|next|prev` moves focus
- `:close` closes the active pane
- `:only` closes every other pane
- `:buffers` or `:ls` lists open buffers
- `:buffer <index|path>`, `:bnext`, and `:bprev` switch buffers
- `:copen`, `:cnext`, and `:cprev` navigate the quickfix list built from project search
- `:lgrep <text>`, `:lopen`, `:lnext`, and `:lprev` navigate a location list scoped to the current buffer
- `:multicursor <text>` places cursors on every literal match in the current buffer
- `:registers`, `:marks`, and `:jumps` show editor state
- `:s/pattern/replacement/[g]` substitutes in the current line
- `:%s/pattern/replacement/[g]` substitutes in the whole buffer

Buffer-pane controls from normal mode:

- `Ctrl-w s` splits below
- `Ctrl-w v` splits to the right
- `Ctrl-w h/j/k/l` moves focus
- `Ctrl-w w` and `Ctrl-w W` cycle panes
- `Ctrl-w q` closes the active pane
- `Ctrl-w o` keeps only the active pane

Editing additions:

- Named registers: type `"` then a register letter before `yy`, `diw`, `x`, `p`, and similar operators.
- Text objects for `d/c/y`: `i(`, `a(`, `i[`, `a[`, `i{`, `a{`, `i"`, `a"`, `i'`, `a'`, `ip`, and `ap`.
- Manual folds:
  - `V ... z f` creates a fold from the selected lines
  - `z a` toggles the fold at the cursor
  - `z c` closes the current fold
  - `z o` opens the current fold
- `z M` closes all folds
- `z R` opens all folds

Diagnostics:

- Lines with errors are marked with a red background and warnings with a yellow background.
- The frame-local mode line shows buffer error/warning counts.
- The global mode line shows project error/warning counts on the right.
- `g x` opens a diagnostic popup for the current line.
- Hovering a faulty line with the mouse opens the same popup without stealing focus.
- `g a` opens a code-action menu for the current line and `Enter` applies the selected fix.

## Directory Browser

Passing a directory to SWIM opens a dired-style directory browser instead of a file tree.

- `j/k` or arrow keys move through entries.
- `Enter`, `l`, or right arrow opens the selected file or descends into the selected directory.
- `h`, left arrow, or backspace goes to the parent directory.
- `r` refreshes the current directory.
- `q` or `Esc` closes the directory window and returns to the previous fullscreen window.

## Tree View

The project tree ships as the separate `swim-tree-view` plugin artifact and is loaded on demand.

- Press `t` in normal mode to open or close the tree view.
- The tree opens on the left side of the current layout.
- Use `j/k` or arrow keys to move.
- Use `h/l` or left/right arrows to collapse and expand directories.
- Use `Enter` or `Space` to open the selected file.
- Use `r` to refresh the tree.
- Use `q` or `Esc` to close the tree pane.

The tree follows the current project root and keeps its selection synced to the active file.

## Java Support

Java files use the embedded Oracle/NetBeans LSP provider through the separate `swim-java-lsp` plugin.

Current Java features include:

- semantic token coloring
- line diagnostics with background highlights, popup details, project navigation, and code actions
- embedded LSP startup from the bundled Oracle payload
- insert-mode completion popup with Java completion items
- snippet placeholder support for LSP snippet completions
- code actions wired to normal-mode shortcuts
- reference/definition menus when multiple locations are returned

Normal-mode Java shortcuts use the `<Space> e` prefix:

| Shortcut | Action |
| --- | --- |
| `<Space> e i` | Organize imports |
| `<Space> e f` | Make field `final` |
| `<Space> e a` | Generate accessors |
| `<Space> e s` | Generate `toString()` |
| `<Space> e l` | Show code lens information |

## C And C++ Support

C and C++ files (`.c`, `.h`, `.cpp`, `.hpp`) use the separate `swim-clangd-lsp` plugin.

- Install `clangd` so it is available on `PATH`.
- Keep a `compile_commands.json` in the project root or in a `build/` directory under that root.
- You can also mark a project boundary with a `.swim` file and point it at a compilation database with `compile_commands=<relative-or-absolute-path>`.
- SWIM loads the plugin on demand when one of those file types is opened.
- Insert mode uses the same completion popup style as Java mode.
- Diagnostics share the same line highlights, mode-line counts, popup details, project navigation, and code-action menu.
- Default indentation is 2 spaces for C and C++.

Example `.swim` file:

```ini
compile_commands=build/compile_commands.json
```

Default indentation is 4 spaces for Java.

## Mail Client

The mail client ships as the separate `swim-email` plugin and stores its runtime state under `~/.swim/email`.

- Press `e` in normal mode to open or close the fullscreen mail window.
- Mail state is stored in `~/.swim/email/mail.mv.db`.
- Account configuration lives in `~/.swim/email/accounts.json`.
- Tag rules live in `~/.swim/email/tag-rules.json`.

Current account config shape:

```json
{
  "accounts": [
    {
      "id": "work",
      "name": "Work",
      "protocol": "IMAP",
      "host": "mail.example.com",
      "port": 993,
      "username": "me@example.com",
      "passwordEnv": "SWIM_MAIL_PASSWORD",
      "folder": "INBOX"
    }
  ]
}
```

Current tag rule shape:

```json
{
  "rules": [
    {
      "tag": "vip",
      "field": "sender",
      "contains": "boss@example.com"
    }
  ]
}
```

Supported fields for tag rules are:

- `sender`
- `recipient`
- `subject`
- `body`

Current sync behavior:

- IMAP imports recent mail into the local H2 store and groups messages into threads.
- POP3 imports recent inbox mail into the local H2 store.
- IMAP accounts can also use OAuth2 device-code login and cache tokens in `~/.swim/email/oauth-tokens.json`.
- SMTP send is available for configured accounts, including OAuth2 token reuse for Microsoft 365.
- Tags are reapplied whenever rules change or mail is refreshed.
- Exchange accounts can use EWS with `authType` set to `BASIC` or `PASSWORD`.
- The current EWS adapter supports distinguished folders such as `INBOX`, `SentItems`, `Drafts`, `DeletedItems`, and `JunkEmail`.
- NTLM and custom EWS folder traversal are not finished yet.
- The thread list is paged and extended lazily as you scroll.

## Slack Client

The Slack client ships as the separate `swim-slack` plugin and stores its configuration under `~/.swim/slack`.

- Open or close the fullscreen Slack workspace with `:slack`.
- Slack workspace configuration lives in `~/.swim/slack/workspaces.json`.
- The left pane shows configured workspaces and the channels or DMs for the active workspace.
- The top-right pane shows recent messages for the selected channel.
- The lower buffer shows the selected thread in a normal read-only buffer.
- Press `Enter` in the message list to focus that read-only buffer for normal navigation and search.
- Press `c` to compose a new message in the selected conversation.
- Press `r` to reply in the selected thread.
- While composing, the lower buffer becomes editable and `Ctrl-s` sends the message.
- Press `e` to refresh Slack metadata and channel state.

Current Slack workspace config shape:

```json
{
  "workspaces": [
    {
      "id": "work",
      "label": "Work Slack",
      "tokenEnv": "SWIM_SLACK_TOKEN"
    }
  ]
}
```

Supported token configuration fields are:

- `token`: literal token stored directly in the config file
- `tokenEnv`: environment variable or Java system property containing the token
- `tokenCommand`: shell command whose stdout is the token

Current Slack behavior:

- Workspace and channel metadata are refreshed when you open Slack or press `e`.
- Channel history is loaded on demand for the selected conversation.
- Thread bodies are loaded on demand for the selected message.
- Sending invalidates the current channel cache so the refreshed message list includes the new post.

Example on-prem Exchange account:

```json
{
  "accounts": [
    {
      "id": "exchange",
      "name": "Exchange",
      "protocol": "EXCHANGE",
      "ewsUrl": "https://mail.example.com/EWS/Exchange.asmx",
      "username": "DOMAIN\\\\user",
      "passwordEnv": "SWIM_EXCHANGE_PASSWORD",
      "folder": "INBOX",
      "authType": "BASIC"
    }
  ]
}
```

Example Microsoft 365 / Exchange Online IMAP account with OAuth2:

```json
{
  "accounts": [
    {
      "id": "work-oauth",
      "name": "Work Mail",
      "protocol": "IMAP",
      "host": "outlook.office365.com",
      "port": 993,
      "smtpHost": "smtp.office365.com",
      "smtpPort": 587,
      "username": "you@example.com",
      "folder": "INBOX",
      "authType": "OAUTH2",
      "tenant": "organizations",
      "clientId": "YOUR-ENTRA-PUBLIC-CLIENT-ID"
    }
  ]
}
```

For OAuth2 IMAP:

- Open the mail window with `e`.
- If no cached token is present, SWIM shows a Microsoft device-code login message in the window.
- Complete the browser login, then press `r` in the mail window to refresh.

Compose and send:

- Press `c` in the mail window to compose a reply/new message from the selected thread context.
- `Tab` and `Shift-Tab` move between `To`, `Subject`, and `Body`.
- `Ctrl-s` sends the message using the selected account’s SMTP settings.
- `Esc` cancels compose mode.

## Writing A Plugin

Plugins are regular Java components loaded through the runtime plugin layer in `plugins/`.

The main plugin API lives under `swim-launcher/src/main/java/org/fisk/swim/api/`.

### Core Interfaces

- `SwimPlugin`
  - implement this for every plugin
  - `load(SwimPluginContext context)` is the main entry point
  - `getId()` defaults to the class name
  - `getLoadOrder()` defaults to `100`
  - `loadOnStartup()` defaults to `true`
  - `close()` is called when the plugin is shut down

- `SwimPluginContext`
  - `getHost()` gives access to host-level actions
  - `getInitialPath()` returns the path SWIM started with
  - `getCurrentPath()` returns the current active path

- `SwimHost`
  - `requestReload(Path path)` reloads the runtime
  - `requestRebuildAndReload(Path path)` rebuilds, then reloads
  - `requestLoadPlugin(String pluginId, Path path)` requests loading another plugin
  - `requestExit()` exits SWIM
  - `getBuildRoot()` returns the active build root
  - panel support:
    - `registerPanel(String pluginId, SwimPanel panel)`
    - `unregisterPanel(String pluginId)`
    - `getPanel(String pluginId)`
    - `isReloading()`

- `SwimPanel`
  - use this when your plugin exposes a side or bottom panel
  - `getId()` returns the panel id
  - `getTitle()` returns the displayed title
  - `render(int width, int height)` returns panel lines
  - `handleInput(String input, int width, int height)` handles panel input
  - `syncToCurrentPath(Path path)` is optional and can track editor focus

- `SwimPanelResult`
  - panel input handlers return this record
  - fields: `handled`, `openFile`, `message`
  - helper constructors: `ignored()`, `success()`, `success(Path openFile)`, `successMessage(String message)`

### Minimal Plugin Shape

```java
public final class MyPlugin implements SwimPlugin {
    @Override
    public void load(SwimPluginContext context) {
        // initialize here
    }

    @Override
    public void close() {
        // cleanup here
    }
}
```

### Panel Plugin Shape

If your plugin provides UI, register a `SwimPanel` from `load(...)` using the `SwimHost` from the plugin context.

### Examples In This Repo

- `swim-tree-view` shows a panel-oriented plugin
- `swim-java-lsp` shows a language-support plugin
- `swim-clangd-lsp` shows another minimal LSP-style plugin

## Nemo

SWIM includes a built-in AI assistant for the current workspace.

Open Nemo from normal mode with `!`.

Nemo reads configuration from `~/.swim/nemo/nemo.conf`:

```json
{
  "provider": "openai",
  "model": "gpt-5.4",
  "apiKeyEnv": "SWIM_NEMO_API_KEY",
  "baseUrl": "https://api.openai.com/v1",
  "contextWindowTokens": 200000,
  "timeoutSeconds": 60,
  "maxRetries": 2,
  "skills": {
    "enabled": true,
    "maxFiles": 8,
    "maxChars": 12000
  },
  "tools": {
    "webSearch": true,
    "listFiles": true,
    "readFile": true,
    "searchFiles": true,
    "runCommand": true,
    "commandPolicy": "restricted",
    "permissionMode": "workspace_write",
    "osSandbox": "auto",
    "approvalPolicy": "on_escalation",
    "writeFile": true,
    "applyPatch": true,
    "gitStatus": true,
    "gitDiff": true,
    "gitAdd": true,
    "gitCommit": true,
    "maxResults": 200,
    "maxOutputChars": 12000,
    "commandTimeoutSeconds": 20
  }
}
```

The config loader also accepts the older properties format and still migrates `~/.swim/nemo.conf` into the Nemo directory on first use. Property names use the same structure, for example `tool.permission_mode=workspace_write`, `tool.os_sandbox=auto`, and `tool.approval_policy=on_escalation`.

Nemo now runs through langchain4j, so OpenAI-compatible vendors can be selected with `provider`, `baseUrl`, custom headers, query parameters, and custom request parameters.

### Context And Guidance

When `contextWindowTokens` is set, Nemo budgets the prompt before sending it. If the full prompt would exceed the configured window, it preserves the current request and recent turns, compacts older conversation into bounded notes, truncates oversized skill instructions, and excerpts large files around the cursor.

Nemo includes applicable workspace guidance from `AGENTS.override.md` or `AGENTS.md`, walking from the workspace root to the current file directory. `AGENTS.override.md` wins over `AGENTS.md` in the same directory. Existing `SKILLS.md` files in those directories are also included for compatibility.

Tool transcript entries such as `tool> list_files: path=.` are shown in chat history and persisted with the session, but are excluded from future model prompts so tool chatter does not pollute the conversation context.

### Tool Permissions

`permissionMode` controls the tool surface and runtime enforcement:

| Mode | Behavior |
| --- | --- |
| `read_only` | Advertises and allows inspection tools only. Blocks `run_command`, `write_file`, `apply_patch`, `git_add`, and `git_commit`. |
| `workspace_write` | Allows workspace-scoped file edits and shell-backed git/apply helpers. `runCommand` still obeys `commandPolicy` and OS sandbox settings. |
| `full_access` | Bypasses Nemo's restricted command policy and OS sandbox for `runCommand`. Use only for trusted sessions. File tools still resolve paths through the workspace root. |

`runCommand` uses `commandPolicy: "restricted"` by default. Restricted mode blocks shell control operators such as `;`, pipes, redirects, command substitution, and high-risk executables such as `rm`, `sudo`, `curl`, `ssh`, and `rsync`. Set `commandPolicy` to `"trusted"` only when you intentionally want raw shell behavior.

`:permissions` shows the active session's permission mode, command policy, OS sandbox backend, and approval policy. `:permissions read-only`, `:permissions workspace-write`, and `:permissions full-access` change the active session until the session is rebound from config.

### OS Sandboxing

Shell-backed tools run through an OS filesystem sandbox outside full-access mode when one is available:

| Platform | Backend |
| --- | --- |
| macOS | `/usr/bin/sandbox-exec` |
| Linux | `bwrap` / Bubblewrap, when installed and usable |
| Windows or unsupported Unix | no backend; `osSandbox=required` fails closed |

The sandbox allows reads, denies host filesystem writes by default, allows `/dev/null`, and in `workspace_write` mode allows writes under the workspace root. In `read_only`, mutating tools are already blocked; git inspection commands run with `--no-optional-locks`.

`osSandbox` controls fallback behavior:

| Setting | Behavior |
| --- | --- |
| `auto` | Use the OS sandbox when available. If no backend is usable, or if the sandbox blocks a filesystem write, ask for approval before running the shell command unsandboxed. |
| `required` | Fail closed when no supported OS sandbox backend is available or usable. |
| `disabled` | Run shell-backed tools without the OS sandbox. |

### Approvals

`approvalPolicy` controls when Nemo pauses a worker and asks before running a tool action:

| Setting | Behavior |
| --- | --- |
| `on_escalation` | Default. Ask when a restricted command would otherwise be blocked, or when `osSandbox=auto` would have to run unsandboxed. |
| `on_request` | Ask before every mutating or shell action: `run_command`, `write_file`, `apply_patch`, `git_add`, and `git_commit`. |
| `never` | Do not prompt. Block escalations that require approval. |

Approvals appear as `approval>` messages in the Nemo chat pane. While the worker is paused, Nemo opens the approval menu automatically when the input is empty; use arrows and `Enter` to choose approve once, approve always, or deny. Type `:` if you closed or replaced the menu. The typed commands still work as a fallback: `:approve <id>` runs once, `:approve <id> always` saves an exact workspace-scoped rule, and `:deny <id>` denies the request. Saved approval rules live in `~/.swim/nemo/approvals.json`; they are exact matches for the workspace, tool, and action signature. Use `:approvals` to list pending and saved approvals, and `:unapprove <rule-id|all>` to remove saved rules for the current workspace.

Inside the Nemo chat pane:

- the live input prompt is `!`, but sent chat messages appear in history as `me>`
- type normally and press `Enter` to send; use `Shift-Enter`, `Ctrl-Enter`, `Alt-Enter`, or `Ctrl-J` to insert a newline
- pasted multiline text stays in the draft, so exception traces can be edited before sending
- type `:` at the start of the Nemo input to open a Nemo-specific command completion popup for chat commands and pending approval options
- `webSearch` is enabled by default; set it to `false` to hide Nemo's internet search tool
- `:sessions` lists sessions for the current workspace
- `:workers` lists active workers across sessions
- `:permissions` shows the current tool permission mode; `:permissions read-only`, `:permissions workspace-write`, and `:permissions full-access` change it for the active session
- pending approval prompts add approve-once, approve-always, and deny options to the `:` menu; `:approvals` and `:unapprove` manage pending and saved rules
- `:new [title]` creates a session
- `:switch <session-id>` changes sessions
- `:rename <title>` renames the current session
- `:reset [session-id]` clears a session without deleting it
- `:delete [session-id]` deletes a session
- `:abort [session-id|all]` stops running work
- `:help` lists chat commands
- `:q` closes the pane

Session state is persisted under `~/.swim/nemo/sessions.json`, so chat history survives editor restarts.

## Shell

SWIM includes a PTY-backed terminal view for both quick panel shells and fullscreen shell workspaces.

- `>` opens or reuses the bottom shell panel.
- `:shell` or `:sh` opens a fullscreen shell workspace.
- `Ctrl-g c` creates a new fullscreen shell workspace from normal mode or from an active shell.
- The terminal launches your login shell from `$SHELL`, falling back to `zsh` when unset.
- Fullscreen terminal apps such as `vim` run inside the shell terminal.

Shell interaction:

- When the prompt is active, the mode line shows `INPUT`.
- Typed keys go directly to the terminal application.
- `Esc` closes the bottom shell panel.
- `Ctrl-g` opens the shell command prefix:
  - `Ctrl-g Esc` browses terminal output as a read-only buffer
  - `Ctrl-g c` creates a new shell workspace
  - `Ctrl-g e` returns to the editor window
  - `Ctrl-g w <number> Enter` switches to a recent fullscreen window
  - `Ctrl-g q` closes the current shell
- In shell browse mode, normal-mode navigation works on a read-only snapshot of terminal output.
- Press `i` from shell browse mode to return to the live prompt.


## Reloading

The launcher keeps the editor running through a reloadable core/plugin boundary:

- `:reload` reloads the latest built plugins
- `:rebuild` rebuilds the project and reloads it
- `:upgrade` is an alias for `:rebuild`

This is the intended workflow while developing SWIM itself.
