# SWIM

SWIM stands for *Strange Variety Vi Improved*. It is a Java modal editor with Vim-style keybindings, Java/LaTeX language features, and a launcher/core split that lets the editor reload its core while running.
## Quick Start

```bash
# 1. Make sure you have JDK 25+ installed.
# 2. Build the project from the repo root:
$ mvn package

# 3. Run SWIM with a file via the launcher:
$ java -XX:+UseZGC -cp "swim-launcher/target/swim-launcher-0.0.1-SNAPSHOT.jar:swim-launcher/target/runtime-libs/*" org.fisk.swim.launcher.Main <file>

# 4. For convenience, create an alias:
$ alias swim='java -XX:+UseZGC -cp "<swim_path>/swim-launcher/target/swim-launcher-0.0.1-SNAPSHOT.jar:<swim_path>/swim-launcher/target/runtime-libs/*" org.fisk.swim.launcher.Main'
# Then simply:
$ swim <file>
```

The launcher jar in `swim-launcher/target` is the supported entrypoint. The old root-level `target/swim-*.jar` compatibility path is no longer used.

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

SWIM includes `:nemo`, a built-in AI assistant for asking questions about the current file.

Before using it, export your OpenAI credentials in the shell you use to launch SWIM:

```bash
export OPENAI_API_KEY="your_api_key_here"
# Optional:
export OPENAI_MODEL="gpt-4.1"
export OPENAI_BASE_URL="https://api.openai.com/v1"
```

If you use a provider-compatible endpoint instead of the public OpenAI API, you can point Nemo at it with `OPENAI_BASE_URL` or `OPENAI_RESPONSES_URL` and add provider-specific headers with `OPENAI_HEADER_*`.

Example for an Oracle Code Assist style setup:

```bash
export OPENAI_API_KEY="your_employer_token"
export OPENAI_MODEL="gpt-5.4"
export OPENAI_BASE_URL="https://code-internal.aiservice.us-chicago-1.oci.oraclecloud.com/20250206/app/litellm"
export OPENAI_HEADER_CLIENT="codex-cli"
export OPENAI_HEADER_CLIENT_VERSION="0"
```

Then inside SWIM you can ask Nemo questions like:

```text
:nemo summarize this file
:nemo explain this method
:nemo suggest a refactor for this class
```

` :nemo ` sends the current file path and full buffer contents to the model, then shows the answer in a SWIM list panel. The current implementation is read-only: it gives explanations and suggestions, but it does not edit files automatically.

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
