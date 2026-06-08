# Development Notes

## Nemo Editor-Control Sandboxing

Nemo can inspect and drive the editor through host-approved editor-control tools. Treat this as a security boundary.

- Put sandbox decisions at the action execution point, not in a broad raw-key blacklist. A key sequence such as `:w` or `e` should reach the same handler a user would use, and that handler must decide whether the requested action is allowed in the current editor-control sandbox.
- Editor-control actions are opt-in. A driven action that does not call an allow helper or an explicit block helper must fail closed.
- Editor control is a single-owner lease. It starts only after host approval, must be released explicitly when done, and must be released defensively if the owning Nemo request ends or is aborted.
- New commands, normal-mode bindings, global bindings, panel actions, and workspace actions must explicitly use the helper policy methods when they can edit buffers, navigate, open external systems, reveal non-project state, write files, run processes, send messages, switch confidential workspaces, or otherwise cross the project/editor boundary.
- Filesystem writes from editor control are allowed only when the target path is inside the approved workspace root and the active permissions allow workspace writes. Saving a project file can be valid; saving a scratch buffer or an outside-workspace file must be blocked.
- Do not expose confidential surfaces to Nemo. Mail content is never available to Nemo, including screen snapshots, driven UI navigation, prompts, tool outputs, debug text, logs, and tests. Treat future private integrations with the same default-deny rule.
- Host-only approval overlays must remain invisible to Nemo and must not be controllable through `drive_editor`.
- Prefer focused integration tests for each new action-level decision: one allowed project-local case, one blocked boundary-crossing case, and one assertion that confidential text is absent from Nemo-visible output.
