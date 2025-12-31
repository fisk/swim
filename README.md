# SWIM – The Java‑powered Vim‑style Editor

> **SWIM** stands for *Strange Variety Vi Improved* – the ultimate modal text editor built on the Java platform. It delivers the power and speed of Vim, the flexibility of Java, and the extensibility of a full language‑server ecosystem—all in a single, lightweight JAR.

| Feature | What it gives you |
|---------|-------------------|
| **Vim‑style modal editing** | Classic keystrokes, visual and block selections, powerful motions.
| **Pure Java implementation** | Runs on any JVM, no native dependencies, simple `mvn` build.
| **Native Java LSP integration** | Auto‑completion, go‑to‑definition, refactoring for Java (and LaTeX, etc.) right out of the box.
| **Extensible** | Add custom modes or plugins in Java – treat the editor as a platform, not a product.
| **Zero‑configuration** | One command to launch, alias support for quick launching.
| **Lightweight** | Less than 50 MB, no external libraries besides the standard JDK.

## Quick Start

```bash
# 1. Make sure you have JDK 25+ installed.
# 2. Build the project:
$ mvn clean package

# 3. Run SWIM with a file:
$ java -XX:+UseZGC -cp "target/swim-0.0.1-SNAPSHOT.jar:target/libs/*" org.fisk.swim.Swim <file>

# 4. For convenience, create an alias:
$ alias swim='java -XX:+UseZGC -cp "<swim_path>/target/swim-0.0.1-SNAPSHOT.jar:<swim_path>/target/libs/*" org.fisk.swim.Swim'
# Then simply:
$ swim <file>
```

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

## Extending SWIM

The entire codebase is open source.  To add a new language‑server or mode, simply:

1. Implement a new `LanguageMode`.
2. Register it via `LanguageModeProvider`.
3. Build and test.

Because the editor is written in Java, you can drop in existing libraries or even build a UI plug‑in.


