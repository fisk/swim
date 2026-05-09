# SWIM

SWIM stands for *Strange Variety Vi Improved*. It is a Java modal editor with Vim-style keybindings, Java/LaTeX language features, and a launcher/core split that lets the editor reload its core while running.
## Quick Start

```bash
# 1. Make sure you have JDK 25+ installed.
# 2. Clone SWIM into a local hidden directory:
$ git clone <your-fork-or-this-repo-url> ~/.swim
$ cd ~/.swim

# 3. Build the project in place:
$ mvn package

# 4. Run SWIM with a file via the installed launcher module:
$ java -XX:+UseZGC --module-path "$HOME/.swim/bin/launcher/swim-launcher-0.0.1-SNAPSHOT.jar" -m org.fisk.swim.launcher/org.fisk.swim.launcher.Main <file>

# 5. For convenience, create an alias:
$ alias swim='java -XX:+UseZGC --module-path "$HOME/.swim/bin/launcher/swim-launcher-0.0.1-SNAPSHOT.jar" -m org.fisk.swim.launcher/org.fisk.swim.launcher.Main'
# Then simply:
$ swim <file>
```

`mvn package` now copies the runtime artifacts into:

- `~/.swim/bin/launcher`
- `~/.swim/lib`

Those installed artifacts are the supported runtime entrypoint. The launcher and core are now deployed as real JPMS modules. The source tree remains in `~/.swim`, so `:rebuild` can rebuild in place and then reload the freshly copied `lib` artifacts without depending directly on `swim-core/target` at runtime.

Once SWIM is running:

- use `:help` to open the built-in tutorial
- use `:nemo <question>` to ask Nemo about the current file

## Modal Editing – The Essentials

SWIM uses the same keybindings that make Vim legendary.  Below are the core motions and commands you’ll use every day.

### Normal Mode

| Key | Action |
|-----|--------|
| `h/j/k/l` | Move cursor left/down/up/right |
| `0/$` | Move to start/end of line |
| `gg/G` | Go to first/last line |
| `f/F` | Find next/previous character |
| `w/b/e` | Move by words |
| `d{motion}` | Delete |
| `y{motion}` | Yank |
| `p/P` | Paste |
| `u` | Undo |
| `Ctrl‑r` | Redo |
| `i` | Enter **Insert** mode |
| `v/V` | Enter **Visual** / **Visual Line** mode |
| `Ctrl‑v` | Visual Block mode |
| `:` | Command‑line prompt |
| `/` or `?` | Search |

### Insert Mode

All the usual text entry – just type!  Escape (`Esc`) returns you to Normal mode.

### Visual Mode

Same motion keys as Normal, plus:

| Key | Action |
|-----|--------|
| `d` | Delete selection |
| `y` | Yank selection |
| `c` | Change selection (delete + Insert) |

## Java‑LSP Power‑Ups

SWIM’s `:JavaLSP` namespace exposes powerful refactorings.  In Normal mode, prefix commands with `<Space>e`:

| Shortcut | Description |
|----------|-------------|
| `<Space>e i` | Organize imports |
| `<Space>e f` | Make field `final` |
| `<Space>e a` | Generate accessors |
| `<Space>e s` | Generate `toString()` |
| `<Space>e l` | Show code lens |

Feel free to create your own custom commands by wiring up a Java handler.

## Nemo

SWIM includes `:nemo`, a built-in AI assistant for asking questions about the current file. Nemo now uses a persistent chat pane and can call tools to inspect the workspace.

Nemo reads all of its settings from `~/.swim/nemo.conf` using a simple Java-properties format:

```properties
api_key=your_api_key_here
model=gpt-5.4
base_url=https://api.openai.com/v1
# Optional override if you want to point directly at a full responses endpoint:
# responses_url=https://example.invalid/custom/responses

# Optional provider/project headers:
organization=
project=
header.client=swim

# Optional workspace override. If unset, Nemo uses the current project root.
# workspace_root=/absolute/path/to/workspace

# Tool configuration
tool.web_search=false
tool.list_files=true
tool.read_file=true
tool.search_files=true
tool.run_command=true
tool.max_results=200
tool.max_output_chars=12000
tool.command_timeout_seconds=20
```

If you use a provider-compatible endpoint instead of the public OpenAI API, set `base_url` or `responses_url` and any extra HTTP headers with `header.<name>=<value>`.

Then inside SWIM you can ask Nemo questions like:

```text
:nemo summarize this file
:nemo explain this method
:nemo suggest a refactor for this class
```

Or just press `Esc` in Normal mode to open the Nemo chat pane immediately.

Nemo sends the current file path and full buffer contents to the model along with the chat transcript. When enabled in `nemo.conf`, Nemo can use:

- `list_files`
- `read_file`
- `search_files`
- `run_command`
- OpenAI web search via `tool.web_search=true`

Inside the chat pane:

- type normally and press `Enter` to send a follow-up message
- use `:abort` to stop the current request
- use `:help` to list available chat commands
- use `:q` to close the chat pane

## Extending SWIM

The entire codebase is open source.  To add a new language‑server or mode, simply:

1. Implement a new `LanguageMode`.
2. Register it via `LanguageModeProvider`.
3. Build and test.

Because the editor is written in Java, you can drop in existing libraries or even build a UI plug‑in.

## Live Reload

SWIM now runs through a small launcher that loads the editor core dynamically. From inside the editor:

- `:reload` reloads the latest built core
- `:rebuild` rebuilds the project and reloads
- `:upgrade` is an alias for `:rebuild`
