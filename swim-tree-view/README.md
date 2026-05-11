# swim-tree-view

`swim-tree-view` is the project tree plugin for SWIM.

It builds as its own module and is copied into `~/.swim/plugins` during `mvn package`, alongside `swim-core` and `swim-java-lsp`.

At runtime:

- SWIM loads the plugin on demand when the user presses `t` in normal mode.
- The plugin registers a generic panel controller through the launcher API.
- `swim-core` hosts that controller inside the editor layout as a left-side panel.

Current interaction model:

- `j/k` or arrows move selection
- `h/l` or left/right collapse and expand directories
- `Enter` or `Space` opens the selected file
- `r` refreshes the tree
- `q` or `Esc` closes the panel

The module keeps the tree state, project-root resolution, rendering, and command dispatch inside the plugin artifact, while the core stays responsible for layout, focus, and file opening.
