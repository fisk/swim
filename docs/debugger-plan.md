# Debugger Plan

## Goal

Add debugger support with this structure:

- `swim-core` owns the debugger UI and abstract debugger/session contracts.
- debugger backends are contributed by plugins.
- Java debugging is provided by a Java debugger plugin.
- C/C++ debugging is provided by a separate debugger plugin.

## Core Design

The debugger architecture should be split into:

### 1. Core abstractions in `swim-core`

- `DebuggerProviderRegistry`
- `DebuggerProvider`
- `DebuggerSession`
- `DebugSnapshot`
- `DebugThreadInfo`
- `DebugFrameInfo`
- `DebugVariable`
- `DebugBreakpoint`
- `DebugSourceLocation`

### 2. Core debugger manager in `swim-core`

Responsibilities:

- hold the current debugger session
- route debugger commands to the active session
- maintain provider registrations
- update the UI when debugger state changes
- jump the editor to the current stopped source location

### 3. Core debugger UI in `swim-core`

The UI should be debugger-backend agnostic.

Initial layout should be a debugger panel or workspace showing:

- session/provider header
- current execution state
- breakpoints
- threads
- stack frames
- variables for the selected frame

The UI should support:

- continue
- step over
- step into
- step out
- stop
- breakpoint toggling
- selecting threads and frames

### 4. Backend plugins

Each backend plugin should:

- register a `DebuggerProvider` on load
- unregister it on close
- translate the backend’s protocol/runtime into core debugger snapshots

## Command Surface

Initial commands should be:

- `:debug`
- `:debug providers`
- `:debug open`
- `:debug stop`
- `:debug continue`
- `:debug next`
- `:debug step`
- `:debug out`
- `:debug break`
- `:debug java ...`
- `:debug cpp ...`

Normal-mode breakpoint toggle can be added as a dedicated binding after the command path is stable.

## Phase Breakdown

### Phase 1: Core debugger foundation

Deliverables:

- debugger contracts in `swim-core`
- debugger manager and provider registry
- generic debugger UI
- command plumbing in `CommandView`
- tests with a fake provider/session

### Phase 2: Java debugger plugin

Deliverables:

- new `swim-java-debug` module
- JDI-based launching backend
- line breakpoints
- stop/continue/step support
- stack frame and local variable snapshots
- integration tests with compiled temporary Java programs

### Phase 3: C/C++ debugger plugin

Deliverables:

- new `swim-cpp-debug` module
- GDB/MI backend
- breakpoint/continue/step/stack/variable support
- parser tests
- integration tests gated on `gdb` availability

### Phase 4: UX hardening

Deliverables:

- better action help and status messages
- in-progress operation recovery
- debugger panel/workspace polish
- tmux end-to-end tests for launch, break, step, inspect, and stop

## Testing Strategy

### Core tests

- provider registry tests
- debugger manager tests
- command plumbing tests
- UI rendering/selection tests

### Java backend tests

- compile temporary Java program
- launch with debugger
- set breakpoint
- continue to breakpoint
- step over / into / out
- inspect stack frames and variables

### C/C++ backend tests

- MI parser tests with recorded output
- backend tests with mocked MI transport
- real integration tests when `gdb` is available

### tmux tests

- open debugger UI from the installed launcher
- launch Java program and stop on breakpoint
- step and inspect variables
- stop session cleanly
- launch C++ program and stop on breakpoint when `gdb` is available

## First Implementation Slice

The first implementation slice should cover:

1. core debugger abstractions and UI
2. command plumbing
3. Java debugger backend with real launch/break/step support
4. plugin registration scaffolding for C/C++

That gives a real end-to-end debugger path without blocking on the C++ parser work.
