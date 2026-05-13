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
"$HOME/.swim/image/bin/swim" README.md
```

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
| `u` / `Ctrl-r` | Undo / redo |
| `i` | Enter insert mode |
| `v`, `V`, `Ctrl-v` | Visual, visual-line, visual-block |
| `:` | Open command line |
| `!` | Start Nemo / open Nemo chat |
| `/` or `?` | Search |
| `m` | Project file list |
| `t` | Tree view plugin |
| `Esc` | Start Nemo / open Nemo chat |

## Discoverability

SWIM now exposes more of the editor while you work:

- The two-line top menu shows normal-mode key chains and updates live as you type prefixes.
- When the command line is active, the top menu switches to command-specific hints.
- Typing `:` opens a command popup with matching commands for the current prefix.
- The command popup uses the full window width and can grow taller when longer descriptions need more room.
- `Up` and `Down` move through command matches, and `Tab` completes the selected command.
- Typing `!` or `Esc` opens the Nemo chat pane.

This makes it possible to explore commands and key chains without memorizing everything up front.

## Panes And Panels

SWIM supports multiple panes and persistent side/bottom panels.

Command-line pane controls:

- `:split` or `:sp` opens a pane below the active pane
- `:vsplit` or `:vs` opens a pane to the right
- `:focus left|right|up|down|next|prev` moves focus
- `:close` closes the active pane
- `:only` closes every other pane

Normal-mode pane controls:

- `Ctrl-w s` splits below
- `Ctrl-w v` splits to the right
- `Ctrl-w h/j/k/l` moves focus
- `Ctrl-w w` and `Ctrl-w W` cycle panes
- `Ctrl-w q` closes the active pane
- `Ctrl-w o` keeps only the active pane

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
- embedded LSP startup from the bundled Oracle payload
- insert-mode completion popup with Java completion items
- snippet placeholder support for LSP snippet completions
- code actions wired to normal-mode shortcuts

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
- SWIM loads the plugin on demand when one of those file types is opened.

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

Open Nemo from normal mode with `!` or `Esc`.

Nemo reads configuration from `~/.swim/nemo.conf`:

```properties
api_key=your_api_key_here
model=gpt-5.4
base_url=https://api.openai.com/v1

tool.web_search=false
tool.list_files=true
tool.read_file=true
tool.search_files=true
tool.run_command=true
tool.write_file=true
tool.apply_patch=true
tool.git_status=true
tool.git_diff=true
tool.git_add=true
tool.git_commit=true
tool.max_results=200
tool.max_output_chars=12000
tool.command_timeout_seconds=20
```

Inside the Nemo chat pane:

- the live input prompt is `!`, but sent chat messages appear in history as `me>`
- type normally and press `Enter` to send
- type `:` at the start of the Nemo input to open a Nemo-specific command completion popup for chat commands like `:sessions`, `:workers`, and `:switch`
- `:sessions` lists sessions for the current workspace
- `:workers` lists active workers across sessions
- `:new [title]` creates a session
- `:switch <session-id>` changes sessions
- `:rename <title>` renames the current session
- `:delete [session-id]` deletes a session
- `:abort [session-id|all]` stops running work
- `:help` lists chat commands
- `:q` closes the pane

Session state is persisted under `~/.swim/nemo/sessions.json`, so chat history survives editor restarts.

## Reloading

The launcher keeps the editor running through a reloadable core/plugin boundary:

- `:reload` reloads the latest built plugins
- `:rebuild` rebuilds the project and reloads it
- `:upgrade` is an alias for `:rebuild`

This is the intended workflow while developing SWIM itself.
