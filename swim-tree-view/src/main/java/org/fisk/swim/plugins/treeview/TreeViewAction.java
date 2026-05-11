package org.fisk.swim.plugins.treeview;

import java.nio.file.Path;

public record TreeViewAction(TreeViewActionType type, Path path) {
    public static TreeViewAction none() {
        return new TreeViewAction(TreeViewActionType.NONE, null);
    }

    public static TreeViewAction toggleDirectory(Path path) {
        return new TreeViewAction(TreeViewActionType.TOGGLE_DIRECTORY, path);
    }

    public static TreeViewAction openFile(Path path) {
        return new TreeViewAction(TreeViewActionType.OPEN_FILE, path);
    }
}
