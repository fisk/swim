package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TreeViewController {
    private final Path _rootPath;
    private final Set<Path> _expandedPaths = new HashSet<>();
    private TreeViewNode _root;
    private TreeViewFileOpener _fileOpener = path -> { };
    private int _selectionIndex;

    public TreeViewController(Path rootPath) throws IOException {
        _rootPath = Objects.requireNonNull(rootPath).toAbsolutePath().normalize();
        _expandedPaths.add(_rootPath);
        refresh();
    }

    public Path getRootPath() {
        return _rootPath;
    }

    public void setFileOpener(TreeViewFileOpener fileOpener) {
        _fileOpener = Objects.requireNonNull(fileOpener);
    }

    public void refresh() throws IOException {
        Path selectedPath = _root == null ? null : getSelectedPath();
        _root = TreeViewNode.load(_rootPath);
        _expandedPaths.add(_rootPath);
        if (selectedPath != null) {
            selectPath(selectedPath);
        } else {
            _selectionIndex = 0;
        }
        clampSelection();
    }

    public List<TreeViewRow> getRows() {
        List<TreeViewRow> rows = new ArrayList<>();
        appendRows(_root, 0, rows);
        return rows;
    }

    public TreeViewRow getSelectedRow() {
        List<TreeViewRow> rows = getRows();
        if (rows.isEmpty()) {
            return null;
        }
        clampSelection(rows.size());
        return rows.get(_selectionIndex);
    }

    public Path getSelectedPath() {
        TreeViewRow row = getSelectedRow();
        return row == null ? null : row.path();
    }

    public void moveSelection(int delta) {
        List<TreeViewRow> rows = getRows();
        if (rows.isEmpty()) {
            _selectionIndex = 0;
            return;
        }
        _selectionIndex = Math.max(0, Math.min(rows.size() - 1, _selectionIndex + delta));
    }

    public boolean selectPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        expandAncestors(normalized);
        List<TreeViewRow> rows = getRows();
        for (int i = 0; i < rows.size(); ++i) {
            if (rows.get(i).path().equals(normalized)) {
                _selectionIndex = i;
                return true;
            }
        }
        return false;
    }

    private void expandAncestors(Path path) {
        if (!path.startsWith(_rootPath)) {
            return;
        }
        for (Path current = path.getParent(); current != null && current.startsWith(_rootPath); current = current.getParent()) {
            _expandedPaths.add(current);
            if (current.equals(_rootPath)) {
                break;
            }
        }
    }

    public boolean expandSelectedDirectory() {
        TreeViewRow row = getSelectedRow();
        if (row == null || !row.directory() || row.expanded()) {
            return false;
        }
        _expandedPaths.add(row.path());
        return true;
    }

    public boolean collapseSelectedDirectoryOrParent() {
        TreeViewRow row = getSelectedRow();
        if (row == null) {
            return false;
        }
        if (row.directory() && row.expanded() && !row.path().equals(_rootPath)) {
            _expandedPaths.remove(row.path());
            return true;
        }
        Path parent = row.path().getParent();
        if (parent == null) {
            return false;
        }
        if (!parent.equals(_rootPath) && _expandedPaths.remove(parent)) {
            selectPath(parent);
            return true;
        }
        return false;
    }

    public TreeViewAction activateSelection() {
        TreeViewRow row = getSelectedRow();
        if (row == null) {
            return TreeViewAction.none();
        }
        if (row.directory()) {
            toggleExpanded(row.path());
            return TreeViewAction.toggleDirectory(row.path());
        }
        _fileOpener.open(row.path());
        return TreeViewAction.openFile(row.path());
    }

    private void toggleExpanded(Path path) {
        if (_expandedPaths.contains(path) && !path.equals(_rootPath)) {
            _expandedPaths.remove(path);
        } else {
            _expandedPaths.add(path);
        }
    }

    private void appendRows(TreeViewNode node, int depth, List<TreeViewRow> rows) {
        boolean selected = rows.size() == _selectionIndex;
        boolean expanded = node.isDirectory() && _expandedPaths.contains(node.getPath());
        rows.add(new TreeViewRow(node.getPath(), node.getName(), depth, node.isDirectory(), expanded, selected));
        if (!expanded) {
            return;
        }
        for (TreeViewNode child : node.getChildren()) {
            appendRows(child, depth + 1, rows);
        }
    }

    private void clampSelection() {
        clampSelection(getRows().size());
    }

    private void clampSelection(int size) {
        if (size <= 0) {
            _selectionIndex = 0;
            return;
        }
        _selectionIndex = Math.max(0, Math.min(size - 1, _selectionIndex));
    }
}
