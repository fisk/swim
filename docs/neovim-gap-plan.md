# Neovim Gap Plan

## Goal

Close the most important editor-behavior gaps between SWIM and a practical Neovim workflow without throwing away SWIM's workspace-oriented UI.

## Phase 1: Core Vim Primitives

- named registers for yank/delete/paste
- macro recording and playback
- marks and jump traversal
- dot-repeat for edit replay
- tests:
  - unit tests for register/macro/jump state
  - tmux integration tests for real editor behavior

## Phase 2: Ex And Buffer Workflow

- buffer inventory and buffer switching commands
- register/mark/jump inspection commands
- substitution commands for current-line and whole-buffer workflows
- tests:
  - command-level unit tests
  - tmux coverage for command-mode editing flows

## Phase 3: User Config And Session Restore

- `~/.swim/config.json` for:
  - normal-mode remaps
  - startup commands
  - editor options
  - restore-last-session toggle
- `~/.swim/session.json` for restoring the last active buffer set
- tests:
  - config/session store unit tests
  - tmux integration for remaps and session restore

## Phase 4: Remaining Parity Work

- richer text objects and operator-pending combinations
- quickfix/location-list workflows on top of project search
- broader visual-block operations and freer multicursor creation
- real folding support
- deeper session/layout persistence

## Notes

- SWIM already has first-class workspaces for mail, Slack, Git, shell, debugger, and directory browsing; the Neovim-gap work should strengthen the editor core rather than replicate plugin ecosystems blindly.
- The features in phases 1-3 are intended to be stable enough for daily editing before phase 4 lands.
