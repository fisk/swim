# Editor Action Inventory

This inventory was derived from the source tree under:

- `swim-core/src/main/java`
- `swim-email/src/main/java`
- `swim-java-lsp/src/main/java`
- `swim-clangd-lsp/src/main/java`
- `swim-tree-view/src/main/java`
- `swim-launcher/src/main/java`

It is intentionally source-oriented: each item is a user-visible action, passive behavior, or configuration surface that should be covered by integration testing or explicitly marked as out of scope.

## Core Buffer And Normal Mode Actions

Defined primarily in:

- `org/fisk/swim/mode/Mode.java`
- `org/fisk/swim/mode/NormalMode.java`
- `org/fisk/swim/text/Buffer.java`
- `org/fisk/swim/ui/Cursor.java`

Actions:

- Scroll viewport up: `Ctrl-y`
- Scroll viewport down: `Ctrl-e`
- Move to end of line: `$`
- Move to start of line: `^`
- Move left: `h`, left arrow
- Move right: `l`, right arrow
- Move down: `j`, down arrow
- Move up: `k`, up arrow
- Move to start of buffer: `gg`
- Move to end of buffer: `G`
- Find next character on line: `f<char>`
- Find previous character on line: `F<char>`
- Fancy jump to visible word starts with hints: `w<char>` and follow-up hint keys
- Enter insert mode: `i`
- Enter visual character mode: `v`
- Enter visual line mode: `V`
- Enter visual block mode: `Ctrl-v`
- Split pane below: `Ctrl-w s`
- Split pane to the right: `Ctrl-w v`
- Focus pane left/down/up/right: `Ctrl-w h/j/k/l`
- Focus next pane: `Ctrl-w w`
- Focus previous pane: `Ctrl-w W`
- Close active pane: `Ctrl-w q`
- Close all other panes: `Ctrl-w o`
- Undo committed change: `u`
- Redo committed change: `Ctrl-r`
- Delete inner word: `d i w`
- Delete current word from cursor: `d w`
- Delete current line: `d d`
- Delete character under cursor: `x`
- Change inner word and enter insert: `c i w`
- Change current word and enter insert: `c w`
- Append after cursor: `a`
- Append at end of line: `A`
- Open line below and enter insert: `o`
- Open line above and enter insert: `O`
- Paste after cursor or after line: `p`
- Paste before cursor or before line: `P`
- Yank current line: `y y`
- Toggle project file list: `m`
- Toggle project text search panel: `M`
- Toggle mail panel: `e`
- Toggle tree view plugin panel: `t`
- Open command line: `:`
- Open Nemo chat pane: `!`
- Open shell panel: `>`
- Search current word forward: `*`
- Search current word backward: `#`
- Start forward search: `/`
- Start backward search: `?`
- Repeat search in same direction: `n`
- Repeat search in opposite direction: `N`
- Clear message / trigger Nemo with empty prompt on `Esc` in normal mode

Passive editing behaviors in `Buffer`:

- Smart newline indentation when inserting `\n`
- Extra indentation inside `{ ... }`
- Special handling when newline is inserted before `}`
- Line reindent after indentation-only line edits
- Clipboard semantics for characterwise vs linewise paste

## Insert Mode And Completion/Snippet Actions

Defined primarily in:

- `org/fisk/swim/mode/InputMode.java`
- `org/fisk/swim/lsp/java/JavaLSPClient.java`
- `org/fisk/swim/ui/CompletionPopupView.java`

Actions:

- Exit insert mode: `Esc`
- Insert printable character
- Backspace
- Insert newline: `Enter`
- Move left/right/up/down with arrows
- Completion next item: down arrow, `Ctrl-n`
- Completion previous item: up arrow, `Ctrl-p`
- Completion page next: page down, `Ctrl-v`
- Completion page previous: page up
- Accept completion: `Enter`, `Tab`
- Accept completion with commit character
- Cancel completion: `Esc`, `Ctrl-g`
- Trigger completion when not visible: `Ctrl-n`
- Snippet next stop: `Tab`
- Snippet previous stop: reverse tab
- Snippet in-place character editing

Multiple cursors:

- Supported indirectly through visual block mode plus `I`
- Insert-mode left/right movement applies to all active cursors
- General standalone multi-cursor creation is not exposed as a normal-mode action

## Visual Mode Actions

Defined in:

- `org/fisk/swim/mode/VisualMode.java`
- `org/fisk/swim/mode/VisualLineMode.java`
- `org/fisk/swim/mode/VisualBlockMode.java`

Visual character mode:

- Exit: `Esc`
- Swap anchor/caret: `o`
- Delete selection: `d`
- Change selection and enter insert: `c`
- Yank selection: `y`
- Movement inherited from normal navigation and fancy jump

Visual line mode:

- Exit: `Esc`
- Swap anchor/caret: `o`
- Delete selected lines: `d`
- Change selected lines: `c`
- Yank selected lines as linewise text: `y`

Visual block mode:

- Exit: `Esc`
- Swap anchor/caret: `o`
- Delete rectangular selection: `d`
- Insert at start of each selected row via multiple cursors: `I`
- Rectangular change/yank actions are not currently implemented

## Command Line Actions

Defined in:

- `org/fisk/swim/ui/CommandView.java`

Editing and command-menu behavior:

- Cancel command/search: `Esc`
- Submit current command/search: `Enter`
- Backspace
- Type command/search text
- Command menu previous match: up arrow, reverse tab
- Command menu next match: down arrow
- Command menu completion: `Tab`

Commands and aliases:

- `:q`
- `:e <path>`
- `:split`, `:sp`
- `:vsplit`, `:vs`
- `:close`
- `:only`
- `:focus left|right|up|down|next|prev|previous`
- `:grep <text>`, `:search <text>`
- `:help`, `:h`
- `:mail`
- `:nemo <question>`
- `:reload`
- `:rebuild`
- `:upgrade`
- `:w`

## Generic Panels

### List Panel

Defined in `org/fisk/swim/ui/ListView.java`

Actions:

- Move selection down: down arrow
- Move selection up: up arrow
- Close panel: `Esc`
- Activate selected item: `Enter`
- Filter text entry
- Filter backspace

### Text Panel

Defined in `org/fisk/swim/ui/TextPanelView.java`

Actions:

- Close: `Esc`, `q`
- Scroll down: down arrow, `j`
- Scroll up: up arrow, `k`

### Project Search Panel

Defined in `org/fisk/swim/ui/ProjectSearchPanelView.java`

Actions:

- Type query
- Backspace query
- Move result selection up/down
- Open selected search result: `Enter`
- Close: `Esc`

Special behavior:

- Searches source-root files
- Ignores hidden directories
- Ignores `target`, `build`, `out`, `node_modules`
- Ignores `.class` and `.jar` files

## Mail Panel Actions

Defined in:

- `org/fisk/swim/ui/MailPanelView.java`
- `org/fisk/swim/mail/MailUiSupport.java`
- `swim-email` plugin classes

Browse mode:

- Move thread selection: `j`, `k`, arrows
- Jump to top: `g`
- Jump to bottom: `G`
- Start search: `/`, `?`
- Refresh: `r`
- Scroll message body down: `d`
- Scroll message body up: `u`
- Close panel: `q`, `Esc`
- Start compose/reply: `c`
- Open actionable URL: `o`
- Copy actionable URL: `y`

Search sub-mode:

- Type query
- Backspace
- Move query cursor left/right
- Apply search: `Enter`
- Cancel search: `Esc`

Compose sub-mode:

- Submit send: `Ctrl-s`
- Cancel compose: `Esc`
- Next field: `Tab`
- Previous field: reverse tab
- Insert newline in body: `Enter`
- Backspace
- Move cursor left/right/up/down
- Type text

Passive mail behaviors:

- Auto-refresh on open when accounts never synced
- Refresh in background
- OAuth/browser sign-in status handling
- OAuth URL open/copy fallback
- Unread notification rendering in mode line

## Chat / Nemo Panel Actions

Defined in:

- `org/fisk/swim/ui/ChatPanelView.java`
- `org/fisk/swim/nemo/NemoClient.java`

Chat panel editing:

- Close panel: `Esc`
- Scroll transcript down/up: down arrow, up arrow
- Move input cursor left/right: left arrow, right arrow
- Move input cursor to start/end: `Home`, `End`, `Ctrl-a`, `Ctrl-e`
- Backspace
- Insert character
- Insert newline without submit: `Shift-Enter`
- Submit message or `:` command: `Enter`

Nemo commands:

- `:abort [conversation-id|all]`
- `:sessions`
- `:workers`
- `:new [title]`
- `:switch <conversation-id>`
- `:rename <title>`
- `:reset [conversation-id]`
- `:delete [conversation-id]`
- `:permissions [read-only|workspace-write|full-access]`
- pending approval dropdown options for approve once, approve always, and deny
- `:approvals`
- `:unapprove <rule-id|all>`
- `:help`
- `:q`, `:quit`

Passive Nemo behaviors:

- Pending/thinking timer
- Session persistence
- Tool transcript entries
- Tool permission mode enforcement
- OS sandbox backend selection
- Approval prompts and saved approval rules
- Command menu completions while typing `:`
- Worker lifecycle / abort

## Shell Panel Actions

Defined in `org/fisk/swim/ui/ShellPanelView.java`

Actions:

- Open from normal mode: `>`
- Submit shell command text through chat-like input
- Close via `:q` / `:quit`
- Close by hiding panel

Passive shell behaviors:

- Detect shell from `SHELL`, default to `zsh`
- Stream output line-by-line into the panel
- Destroy subprocess when panel closes

## Tree View Plugin Actions

Defined in:

- `org/fisk/swim/plugins/treeview/TreeViewInputBindings.java`
- `org/fisk/swim/plugins/treeview/TreeViewPanel.java`
- `org/fisk/swim/ui/PluginPanelView.java`

Normalized plugin inputs:

- Move up: up arrow, `k`
- Move down: down arrow, `j`
- Collapse: left arrow, `h`
- Expand: right arrow, `l`
- Activate: `Enter`, `Space`
- Refresh: `r`
- Close plugin panel: `Esc`, `q`

Passive tree-view behaviors:

- Sync to current file path
- Keep selection visible while scrolling
- Open selected file into editor buffer

## Java-LSP-Specific Actions

Defined in `org/fisk/swim/lsp/java/JavaLspPluginSupport.java`

Normal-mode bindings on Java files:

- Go to definition: `g d`
- Organize imports: `Space e i`
- Make field final: `Space e f`
- Generate accessors: `Space e a`
- Generate `toString()`: `Space e s`
- Show code lens details: `Space e l`
- Alternate organize imports binding: `o` on Java buffers

Passive Java behaviors:

- Java completion popup
- Snippet expansion / placeholder navigation
- Semantic highlighting
- Provider startup and workspace creation

## C/C++/Clangd Passive Behaviors

Defined in `org/fisk/swim/lsp/cpp/*`

Behaviors:

- Auto-load clangd plugin when project contains `compile_commands.json`
- Start clangd for project root or `build/compile_commands.json`
- Semantic highlighting after open/edit

No explicit user keybindings beyond generic editing/navigation are exposed here.

## Generic Popup Actions

### Java Definition Popup

Defined in `org/fisk/swim/ui/JavaDefinitionPopupView.java`

Actions:

- Move selection: `j`, `k`, up arrow, down arrow
- Accept selected destination: `Enter`
- Cancel popup: `Esc`

### Command Menu Popup

Defined in `org/fisk/swim/ui/CommandMenuView.java`

Passive behavior:

- Mirrors command palette and chat `:` completions
- Highlights active command selection

## Configuration Surfaces

### Project And File Layout

- `.git` marks source root for file list and project search
- `.swim` also marks source root for file list and project search
- `pom.xml` marks project root for Java/LSP-relative display
- `compile_commands.json` or `build/compile_commands.json` enables clangd project detection

### Logging And Runtime Properties

- `swim.log.dir`
- `swim.log.path`
- `swim.log.level`
- `swim.mail.poll.interval.ms`
- `swim.mail.notification.duration.ms`
- `swim.oracle.java.extension.path`
- `swim.install.oracle.java.skip`
- `swim.install.oracle.java.force`

### Terminal Environment

- `SWIM_TTY_PATH`
- `SWIM_TTY_ROWS`
- `SWIM_TTY_COLS`
- `LINES`
- `COLUMNS`

### Shell / Desktop Environment

- `SHELL`
- `PATH`
- `SystemRoot` on Windows for launcher helpers

### Mail Configuration Files

Located under `~/.swim/email`:

- `accounts.json`
- `tag-rules.json`
- `oauth-tokens.json`
- SQLite database file for synchronized mail state

Mail account fields:

- `id`
- `name`
- `protocol`
- `host`
- `port`
- `smtpHost`
- `smtpPort`
- `smtpUsername`
- `username`
- `passwordEnv`
- `folder`
- `ewsUrl`
- `domain`
- `authType`
- `tenant`
- `clientId`
- `scopes`

Mail tag-rule fields:

- `tag`
- `field`
- `contains`

Secrets can resolve from:

- environment variables
- system properties

### Nemo Configuration Files

Located under `~/.swim`:

- `nemo.conf`
- `nemo/sessions.json`

### Java Extension Resolution

Oracle Java extension can resolve from:

- `swim.oracle.java.extension.path`
- `~/.swim/deps/oracle.oracle-java`
- VS Code extensions directory under `~/.vscode/extensions`

## Explicitly Not Exposed As Standalone User Actions

These exist internally but are not separate top-level editor actions yet:

- Free-form multi-cursor creation outside visual-block insertion
- Visual-block change/yank commands
- Direct user commands for clangd operations
- Direct command-line configuration of indentation settings
