# VT200 Shell Plan

## Goal

Replace the current line-oriented shell panel with a real terminal emulator that can drive interactive programs and render VT200/ANSI terminal behavior inside SWIM.

## Current Limitation

Today `ShellPanelView` behaves like a chat transcript:

- shell output is split into lines
- carriage-return updates are flattened
- cursor addressing is ignored
- ANSI/VT escape sequences are not interpreted
- full-screen TUIs cannot render correctly

That is the wrong abstraction for a terminal.

## Target Architecture

### 1. Terminal emulator core

Add a reusable terminal-emulation layer in `swim-core`, independent from the shell panel:

- `TerminalCell`
- `TerminalStyle`
- `TerminalLine`
- `TerminalScreenBuffer`
- `TerminalParser`
- `TerminalEmulator`

This layer should own:

- cursor position
- screen contents
- scrolling region
- insert/overwrite semantics
- character attributes
- escape-sequence parsing

### 2. PTY-backed shell session

The shell panel should stop treating the shell as a message stream.

It should instead:

- run the shell inside a pseudo-terminal
- feed PTY output bytes to the terminal emulator
- translate key events back to terminal input bytes
- keep the cursor and screen model in sync

### 3. Terminal view/UI

The shell view should render:

- the current emulator screen buffer
- cursor position
- foreground/background colors
- basic attributes such as bold/inverse

The shell panel title/help should become terminal-oriented rather than chat-oriented.

## Phases

### Phase 1: Emulator foundation

Deliverables:

- screen buffer model
- printable characters
- newline, carriage return, backspace, tab
- cursor movement
- line wrap
- clear screen / clear line
- basic CSI cursor positioning
- basic SGR colors

Tests:

- unit tests for parsing and buffer mutation
- rendering tests for wrapped/cursor-addressed output

### Phase 2: Replace line-chat shell panel with emulator view

Deliverables:

- shell output pumps bytes into the emulator
- shell input writes raw bytes, not “submit line” only
- shell view renders from the screen buffer
- cursor shown at emulator cursor position

Tests:

- shell panel unit tests
- tmux test for colored output and carriage-return updates

### Phase 3: PTY correctness and interactive apps

Deliverables:

- launch shell under a PTY
- preserve TTY semantics for interactive programs
- resize propagation
- support for alternate-screen style apps where possible

Tests:

- integration tests for `bash`, `zsh`, or `sh`
- tmux tests for an interactive full-screen tool

### Phase 4: Broader VT200 compatibility

Deliverables:

- scrolling regions
- insert/delete line and char commands
- save/restore cursor
- origin mode where needed
- better SGR coverage

Tests:

- parser coverage for supported escape families
- regression tests with recorded control-sequence transcripts

## First Slice To Implement Now

The first practical slice should cover:

1. terminal emulator model
2. basic VT200/ANSI sequence support
3. shell panel rendering from emulator state
4. unit tests for ANSI output
5. tmux shell-panel tests for visible behavior

That gets SWIM from “chat shell” to “actual terminal renderer” without waiting for every last VT feature.
