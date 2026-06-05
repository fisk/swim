# Diagnostics UX

SWIM now exposes LSP diagnostics as a first-class editing workflow for Java and C/C++ buffers.

## Visuals

- Lines with errors are marked with a red background.
- Lines with warnings are marked with a yellow background.
- The frame-local mode line shows buffer-local error and warning counts.
- The global mode line shows project-wide error and warning counts on the right.

## Navigation

- `g ]` jumps to the next project diagnostic.
- `g [` jumps to the previous project diagnostic.
- `g }` jumps to the next project error.
- `g {` jumps to the previous project error.

Navigation wraps across files inside the current project and records jump history.

## Popups

- `g x` opens diagnostics for the current line in a popup.
- Hovering a faulty line with the mouse opens the same popup without stealing focus.
- The diagnostic popup shows the current message plus source/code details in the footer.

## Fixes

- `g a` opens code actions for the current line.
- Inside the diagnostic popup, `a` or `Enter` opens the code-action list.
- In the code-action popup, `j`/`k` move the selection, `Enter` applies the fix, and `Esc` closes the popup.

## Coverage

- Java diagnostics and code actions are backed by the embedded Oracle/NetBeans LSP client.
- C/C++ diagnostics and code actions are backed by `clangd`.
