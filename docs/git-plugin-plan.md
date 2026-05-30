# Git Plugin Plan

## Goal

Add a proper Git UI to SWIM with a workflow closer to Magit than a thin command wrapper.

The plugin should eventually support:

- repository status
- staged vs unstaged changes
- add / reset / stage-all / unstage-all
- file and hunk diffs
- hunk staging and hunk discard
- patch editing for hunks
- commit creation
- merge-conflict inspection and resolution
- three-way conflict workflows

## Architecture Findings

After reviewing the current codebase:

- SWIM plugins are loaded through `SwimPlugin` and can currently register `SwimPanel` instances.
- The existing plugin surface is good for lightweight side panels like the tree view.
- A Magit-style Git UI is larger and denser than the current panel model.
- SWIM now has fullscreen windows/workspaces for buffers, directory browsing, and mail.
- The current plugin API does not yet expose a first-class fullscreen plugin workspace.
- `org.eclipse.jgit` is already a runtime dependency in `swim-core`, so Git operations can be implemented without shelling out to `git`.
- tmux-driven end-to-end tests already exist and should be reused for real repository workflows.

## Recommended Direction

Do **not** implement the Git UI as a narrow side panel first and hope it scales.

That would be the wrong abstraction for:

- multi-section status screens
- inline/hunk diff browsing
- patch editing
- conflict resolution
- commit-message entry and refresh-heavy interactions

Instead:

1. Extend the plugin/runtime surface to support a fullscreen plugin workspace.
2. Implement the Git plugin on top of that fullscreen workspace.
3. Allow ordinary buffer splits inside the Git workspace only when they are clearly useful.
4. Reuse normal SWIM buffers for editing commit messages, patch text, and conflicted files.

## Proposed User Experience

### Entry points

Initial entry points should be:

- `:git`
- `:git status`

Normal-mode bindings can be added after the status flow is stable. They should not conflict with existing `g` motions.

### Main Git window

The Git window should be a fullscreen workspace with sectioned rendering:

- repository header
- branch / HEAD / upstream summary
- staged changes
- unstaged changes
- untracked files
- stashes (later)
- merge/conflict section when applicable

The initial interaction model should be list-driven and explicit:

- `j/k` move between visible rows
- `Enter` opens the selected item
- `Tab` expands/collapses sections
- `s` stages selection
- `u` unstages selection
- `x` discards selection
- `d` shows diff
- `c` starts commit flow
- `r` refreshes status

These bindings are intentionally Magit-inspired, but they must still fit SWIM conventions.

### Diff and hunk flows

Diff views should support:

- file diff
- hunk navigation
- stage current hunk
- discard current hunk
- edit current hunk as patch text in a normal SWIM buffer

Patch editing should always validate before applying. Invalid patches should never partially corrupt the worktree.

### Merge conflict flows

For unmerged paths, the plugin should expose:

- open conflict summary
- jump between conflict blocks
- choose ours / theirs / both
- open a three-way resolution view
- mark resolved after a clean result is written

The three-way resolver can start with a pragmatic fullscreen layout:

- base
- ours
- theirs
- result

This can reuse ordinary buffer rendering where possible.

## Proposed Module Layout

Add a new Maven module:

- `swim-git`

Recommended package structure:

- `org.fisk.swim.plugins.git.GitPlugin`
- `org.fisk.swim.plugins.git.GitPluginSupport`
- `org.fisk.swim.plugins.git.GitRepositoryLocator`
- `org.fisk.swim.plugins.git.GitStatusService`
- `org.fisk.swim.plugins.git.GitStatusSnapshot`
- `org.fisk.swim.plugins.git.GitStatusController`
- `org.fisk.swim.plugins.git.GitStatusRenderer`
- `org.fisk.swim.plugins.git.GitStatusWorkspace`
- `org.fisk.swim.plugins.git.GitDiffService`
- `org.fisk.swim.plugins.git.GitPatchEditorSupport`
- `org.fisk.swim.plugins.git.GitConflictResolver`

If the runtime needs a new fullscreen plugin abstraction, add that first in `swim-launcher` and `swim-core`, then build `swim-git` on top of it.

## Implementation Phases

### Phase 1: Fullscreen plugin workspace foundation

Deliverables:

- extend the plugin API so a plugin can open a fullscreen workspace, not only a side panel
- add core support for activating, closing, and restoring plugin workspaces
- add `:git` command plumbing
- add a minimal `swim-git` plugin skeleton

Tests:

- launcher/plugin-registry tests for loading the new plugin type
- core window tests for plugin workspace activation and MRU switching
- tmux IT proving `:git` opens and closes a fullscreen Git window

### Phase 2: Read-only status screen

Deliverables:

- detect the repository from the current path
- handle nested directories and non-repo files cleanly
- render branch, staged, unstaged, untracked, and conflicted sections
- allow navigation, open-file, refresh, and section folding

Tests:

- unit tests for repository discovery and status snapshot building
- renderer tests for section ordering and folded/expanded states
- controller/session tests for navigation and selection
- tmux IT with a temporary real repo showing modified, staged, and untracked files

### Phase 3: File-level actions

Deliverables:

- stage selected file
- unstage selected file
- discard selected file changes
- stage all / unstage all
- open diff from the status view

Tests:

- unit tests using temporary repositories for add/reset/checkout-like flows
- controller tests proving status refresh after each action
- tmux IT covering file stage/unstage/discard from the Git window

### Phase 4: Diff and hunk actions

Deliverables:

- per-file diff view
- hunk navigation
- stage hunk
- discard hunk
- open current hunk in editable patch mode

Tests:

- unit tests for diff-to-hunk mapping
- unit tests for patch generation and hunk application
- parser tests for edited patch text
- tmux IT covering stage/discard of a single hunk in a multi-hunk file

### Phase 5: Patch editing

Deliverables:

- edit hunk as patch text in an ordinary SWIM buffer
- validate edited patch before apply
- report patch failures precisely
- refresh Git status after successful apply

Tests:

- unit tests for valid and invalid edited patches
- buffer/workspace tests proving patch buffers integrate with normal editing
- tmux IT covering successful patch edit and rejected malformed patch

### Phase 6: Commit flow

Deliverables:

- start commit from the Git window
- open commit message buffer
- commit staged changes
- surface validation errors for empty messages or empty index

Tests:

- unit tests for commit service behavior
- tmux IT covering stage, commit, and refreshed clean status

### Phase 7: Merge conflict support

Deliverables:

- detect unmerged paths in status
- open conflicted file and conflict summary
- choose ours / theirs / both for a conflict block
- mark resolved when index/worktree state is valid

Tests:

- unit tests for conflict parsing and resolution transforms
- repository tests for index transitions during conflict resolution
- tmux IT for a synthetic merge conflict from start to resolved status

### Phase 8: Three-way conflict resolver

Deliverables:

- dedicated three-way resolver UI
- base / ours / theirs / result views
- write resolved result back to the worktree
- re-stage resolved file from the UI

Tests:

- unit tests for resolver model construction
- renderer/controller tests for pane focus and selection
- tmux IT covering a real three-way merge conflict workflow

## Testing Strategy

This feature needs testing at several layers.

### 1. Pure unit tests

Use these for deterministic logic:

- repo detection
- snapshot building
- diff parsing
- hunk segmentation
- patch validation
- conflict marker parsing
- resolution transforms

These should live mostly in `swim-git/src/test/java`.

### 2. Plugin-session/controller tests

Use these for:

- key handling
- section expansion/collapse
- selection persistence
- action dispatch
- current-path sync

These should mirror the `swim-tree-view` test style.

### 3. Core integration tests

Use existing `swim-core` tests for:

- command wiring
- window/workspace switching
- plugin workspace lifecycle
- focus handoff between Git UI and normal buffers

### 4. tmux end-to-end tests

Use the installed binary and a real temporary Git repository for:

- status rendering
- file actions
- hunk actions
- commit flow
- merge conflicts
- three-way resolution

The tmux layer is required for confidence here because the user-visible complexity is in navigation and state refresh, not just repository logic.

## Test Matrix

The minimum matrix should include:

- clean repo
- modified file
- staged file
- modified + staged same file
- untracked file
- deleted file
- renamed file
- binary file
- multi-hunk file
- patch-edit success
- patch-edit failure
- merge conflict with two conflicting hunks
- repository root vs nested subdirectory
- `.swim` project root different from Git root
- worktree refresh after external file change

## First Implementation Slice

The safest first slice is:

1. fullscreen plugin workspace support
2. `:git` command
3. read-only Git status screen
4. open selected file from the status screen
5. tmux coverage for opening and navigating the status screen

That slice is small enough to land cleanly, but large enough to validate the core architecture.

## What Should Not Happen

Avoid these shortcuts:

- shelling out to `git` for every action instead of using JGit-backed services
- building the whole feature as a side panel first
- mixing repository logic directly into `swim-core`
- shipping hunk editing without validation
- shipping merge resolution without deterministic tests

## Success Criteria

The feature is ready when:

- the Git UI is fast and stable in large repos
- the main flows are accessible entirely from inside SWIM
- staged/unstaged/conflict state always refreshes correctly
- tmux tests cover real user workflows, not only isolated helpers
- merge conflict handling is reliable enough for daily use
