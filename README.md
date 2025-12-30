Strange Variety Vi Improved, or **swim**, is the ultimate text editor, written in Java.
Keybindings are similar to vi based editors. But this one is written in Java and runs in a JVM.

# Building #

Swim is using JDK 25, so make sure you have that installed.
In order to build swim, run the following command:

```
mvn clean package
```

# Keybindings

The SWIM editor provides a Vim‑style modal interface with a set of key‑bindings that cover navigation, editing, and Java LSP integration. Below is a consolidated reference for the current behaviour.

## Navigation & movement

| Key sequence | Action |
|--------------|--------|
| `<CTRL>-y` | Scroll buffer view up |
| `<CTRL>-e` | Scroll buffer view down |
| `$` | Go to end of line |
| `^` | Go to start of line |
| `h` | Move cursor left |
| `l` | Move cursor right |
| `j` | Move cursor down |
| `k` | Move cursor up |
| `<LEFT>` | Move cursor left |
| `<RIGHT>` | Move cursor right |
| `<DOWN>` | Move cursor down |
| `<UP>` | Move cursor up |
| `g g` | Go to start of buffer |
| `G` | Go to end of buffer |
| `f` (followed by a character) | Find next occurrence of character |
| `F` (followed by a character) | Find previous occurrence of character |

## Normal‑mode bindings

| Key sequence | Action |
|--------------|--------|
| `<SPACE> e i` | `JavaLSPClient.organizeImports()` |
| `<SPACE> e f` | `JavaLSPClient.makeFinal()` |
| `<SPACE> e a` | `JavaLSPClient.generateAccessors()` |
| `<SPACE> e s` | `JavaLSPClient.generateToString()` |
| `<SPACE> e l` | `JavaLSPClient.codeLens()` |
| `i` | Switch to **Insert** mode |
| `v` | Switch to **Visual** mode |
| `V` | Switch to **Visual Line** mode |
| `<CTRL>-v` | Switch to **Visual Block** mode |
| `u` | Undo last change |
| `<CTRL>-r` | Redo last undone change |
| `d i w` | Delete inner word |
| `d w` | Delete next word |
| `d d` | Delete current line |
| `x` | Delete character under cursor |
| `c i w` | Change inner word (delete + switch to insert) |
| `c w` | Change next word (delete + switch to insert) |
| `a` | Switch to insert mode and move cursor right (append) |
| `A` | Switch to insert mode and move cursor to end of line (append) |
| `o` | Insert new line below current line, switch to insert mode |
| `O` | Insert new line above current line, switch to insert mode |
| `p` | Paste after cursor (handles line vs. character copy) |
| `P` | Paste before cursor (handles line vs. character copy) |
| `y y` | Yank (copy) current line |
| `m` | Toggle display of the file list panel (Project Files) |
| `:` | Activate command line (prefix `:`) |
| `*` | Search for inner word under cursor and activate search |
| `#` | Search for inner word under cursor backwards and activate search |
| `/` | Activate forward search prompt |
| `?` | Activate backward search prompt |
| `n` | Go to next search match |
| `N` | Go to previous search match |

## Visual‑mode bindings

The visual modes share the same navigation bindings as normal mode.  They add the following actions:

| Key sequence | Action |
|--------------|--------|
| `d i w` | Delete the selected inner word |
| `d w` | Delete the selected word |
| `d d` | Delete the selected line(s) |
| `x` | Delete the selection |
| `y y` | Yank the selection |

**Note**: Switching out of visual mode back to normal mode is typically done by pressing `<ESC>`.

## Quick reference cheatsheet

```
Movement      |   Action
--------------|--------
...
```

---

In order to edit a file with swim, use the following command:

```
java -XX:+UseZGC -cp "target/swim-0.0.1-SNAPSHOT.jar:target/libs/*" org.fisk.swim.Swim <file>
```

For the best experience, create an alias, like this:

```
alias swim="java -XX:+UseZGC -cp "<swim_path>/target/swim-0.0.1-SNAPSHOT.jar:<swim_path>target/libs/*" org.fisk.swim.Swim "
```

where `<swim_path>` is the path where swim was cloned. With this alias, you can open files like a pro:

```
swim <file>
```