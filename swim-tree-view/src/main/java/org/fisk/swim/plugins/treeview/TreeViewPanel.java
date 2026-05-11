package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class TreeViewPanel {
    private final TreeViewController _controller;
    private final TreeViewRenderer _renderer;
    private final String _title;
    private int _scrollOffset;

    public TreeViewPanel(TreeViewController controller) {
        this(controller, new TreeViewRenderer(), "Tree");
    }

    public TreeViewPanel(TreeViewController controller, TreeViewRenderer renderer, String title) {
        _controller = Objects.requireNonNull(controller);
        _renderer = Objects.requireNonNull(renderer);
        _title = Objects.requireNonNull(title);
    }

    public TreeViewController getController() {
        return _controller;
    }

    public void refresh() throws IOException {
        _controller.refresh();
    }

    public void moveUp() {
        _controller.moveSelection(-1);
    }

    public void moveDown() {
        _controller.moveSelection(1);
    }

    public boolean expandSelection() {
        return _controller.expandSelectedDirectory();
    }

    public boolean collapseSelectionOrParent() {
        return _controller.collapseSelectedDirectoryOrParent();
    }

    public TreeViewAction activateSelection() {
        return _controller.activateSelection();
    }

    public boolean selectPath(java.nio.file.Path path) {
        return _controller.selectPath(path);
    }

    public TreeViewCommandResult handleCommand(TreeViewCommand command) throws IOException {
        return switch (Objects.requireNonNull(command)) {
            case MOVE_UP -> {
                moveUp();
                yield TreeViewCommandResult.handled(TreeViewAction.none());
            }
            case MOVE_DOWN -> {
                moveDown();
                yield TreeViewCommandResult.handled(TreeViewAction.none());
            }
            case EXPAND -> {
                boolean changed = expandSelection();
                yield changed ? TreeViewCommandResult.handled(TreeViewAction.none())
                    : TreeViewCommandResult.unhandled();
            }
            case COLLAPSE -> {
                boolean changed = collapseSelectionOrParent();
                yield changed ? TreeViewCommandResult.handled(TreeViewAction.none())
                    : TreeViewCommandResult.unhandled();
            }
            case ACTIVATE -> TreeViewCommandResult.handled(activateSelection());
            case REFRESH -> {
                refresh();
                yield TreeViewCommandResult.handled(TreeViewAction.none());
            }
        };
    }

    public TreeViewCommandResult handleInput(String input, TreeViewInputBindings bindings) throws IOException {
        Objects.requireNonNull(bindings);
        var command = bindings.lookup(input);
        if (command.isEmpty()) {
            return TreeViewCommandResult.unhandled();
        }
        return handleCommand(command.get());
    }

    public List<String> render(int width, int height) {
        List<TreeViewRow> rows = _controller.getRows();
        syncScroll(rows, height);
        return _renderer.render(rows, _title, width, height, _scrollOffset);
    }

    public TreeViewRenderSnapshot snapshot(int width, int height) {
        List<String> lines = render(width, height);
        return new TreeViewRenderSnapshot(_controller.getRootPath(), _controller.getSelectedPath(), _scrollOffset, lines);
    }

    private void syncScroll(List<TreeViewRow> rows, int height) {
        int visibleRowCount = Math.max(0, height - 1);
        if (visibleRowCount == 0 || rows.isEmpty()) {
            _scrollOffset = 0;
            return;
        }

        int selectedIndex = 0;
        for (int i = 0; i < rows.size(); ++i) {
            if (rows.get(i).selected()) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex < _scrollOffset) {
            _scrollOffset = selectedIndex;
        } else if (selectedIndex >= _scrollOffset + visibleRowCount) {
            _scrollOffset = selectedIndex - visibleRowCount + 1;
        }

        int maxScroll = Math.max(0, rows.size() - visibleRowCount);
        _scrollOffset = Math.max(0, Math.min(maxScroll, _scrollOffset));
    }
}
