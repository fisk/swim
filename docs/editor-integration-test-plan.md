# Editor Integration Test Plan

This plan maps the action inventory to tmux-driven end-to-end coverage. The goal is to keep adding tests until every item in `docs/editor-action-inventory.md` is either:

- covered by a tmux integration test
- intentionally delegated to a lower-level unit/integration test with justification
- marked unsupported/not implemented

## Harness Direction

Shared helpers:

- `org.fisk.swim.testutil.TmuxSession`
- `org.fisk.swim.testutil.InstalledSwimDriver`

Rules:

- Prefer launching the installed binary through tmux
- Prefer asserting user-visible outcomes:
  - file contents
  - pane content
  - process exit behavior
  - plugin panel rendering
  - log side effects only when there is no stable UI signal
- Use temporary workspaces and temporary HOME directories for isolation
- Skip plugin-specific tmux tests only when the required plugin artifact is unavailable

## Coverage Matrix

### Phase 1: Core Editing And Motions

Status:

- Implemented now

Scenarios:

- Insert, undo, redo, save
- `:e` create-new-file flow
- `h/j/k/l`, `^`, `$`, `gg`, `G`
- `f<char>`, `F<char>`
- `yy`, `p`, `P`
- `cw`, `a`, `A`, `o`, `O`
- Visual character delete
- Visual line delete
- Visual block insert with multiple cursors
- Fancy-jump disambiguation with hint labels

### Phase 2: Search, Lists, And Command Palette

Status:

- Implemented now

Scenarios:

- `/` search with `n`
- `:help` plus command completion via `Tab`
- Help/list filtering
- `:grep`
- `<leader> f` project file list
- `<leader> /` project grep panel
- Ignore hidden and generated directories in project search

Planned additions:

- `?` reverse search with `N`
- `*` and `#` current-word searches
- command-match cycling with arrow keys and reverse tab
- unknown-command and invalid-focus error messages

### Phase 3: Pane Management

Status:

- Partially implemented now

Covered:

- split and focus using commands
- open different files in split panes

Planned additions:

- normal-mode `Ctrl-w` split/focus/close/only bindings
- command aliases `:sp` and `:vs`
- `:close`, `:only`, `:focus next|prev`
- edge cases for last-pane close refusal

### Phase 4: Panels And Plugins

Status:

- Implemented now for shell, mail-open, tree-open

Covered:

- shell panel open, command execution, close
- mail panel open with default local config
- tree view open and file activation

Planned additions:

- mail browse/search/compose/send flows using temporary mail fixtures
- mail OAuth status/open-link/copy-link flows in a full process IT
- tree expand/collapse/refresh edge cases
- plugin-panel close behavior through `Esc` and `q`

### Phase 5: Visual-Mode Edge Cases

Status:

- Partially implemented now

Covered:

- visual char delete
- visual line delete
- visual block multi-cursor insert

Planned additions:

- visual yank and paste round-trips
- visual change into insert mode
- visual anchor swap with `o`
- visual-block deletion edge cases on ragged line lengths

### Phase 6: LSP And Language-Specific End-To-End

Status:

- Planned

Targets:

- Java `g d`
- Java organize imports
- Java generate accessors / final / `toString()` / code lens
- completion popup navigation and acceptance
- snippet next/previous stops
- C/C++ clangd auto-start on `compile_commands.json`

Notes:

- Some existing non-tmux integration tests already cover pieces of Java LSP
- tmux coverage should add true user-level flows once fixtures are stabilized

### Phase 7: Nemo Chat And Long-Running Panels

Status:

- Planned

Targets:

- open Nemo pane from normal mode
- submit prompt
- command-mode operations: `:sessions`, `:session`, `:conversations`, `:workers`, `:new`, `:switch`, `:rename`, `:delete`, `:abort`, `:help`, `:q`
- pending-state rendering and abort behavior

### Phase 8: Configuration And Startup Matrix

Status:

- Planned

Targets:

- source-root detection via `.git` and `.swim`
- project-root detection via `pom.xml`
- logging property permutations
- terminal env size permutations
- shell selection through `SHELL`
- Java provider selection through `swim.java.lsp.provider`
- mail poll/notification properties
- Oracle extension install overrides

## Immediate Next Additions After This Change

The next tmux-driven work items should be:

1. Add reverse-search and current-word-search tests.
2. Add `Ctrl-w` pane-binding coverage rather than only `:` commands.
3. Add visual yank/change/swap-anchor coverage.
4. Add full mail-panel interaction coverage with a deterministic temporary mail fixture.
5. Add Nemo command-mode coverage with a deterministic fake backend.
6. Add Java completion/snippet tmux flows in a synthetic Maven workspace.

## Acceptance Standard

For each inventory item, the repository should eventually contain:

- a tmux IT proving the user-visible behavior, or
- a documented reason it cannot be deterministic at the tmux layer and the lower-level test that covers it instead

The inventory document should be updated whenever new bindings, commands, panels, or config surfaces are added.
