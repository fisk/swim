package org.fisk.swim.plugins.treeview;

public record TreeViewCommandResult(boolean handled, TreeViewAction action) {
    public static TreeViewCommandResult unhandled() {
        return new TreeViewCommandResult(false, TreeViewAction.none());
    }

    public static TreeViewCommandResult handled(TreeViewAction action) {
        return new TreeViewCommandResult(true, action);
    }
}
