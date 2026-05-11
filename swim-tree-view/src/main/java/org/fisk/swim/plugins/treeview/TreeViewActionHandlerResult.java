package org.fisk.swim.plugins.treeview;

public record TreeViewActionHandlerResult(boolean handled, String message) {
    public static TreeViewActionHandlerResult ignored() {
        return new TreeViewActionHandlerResult(false, null);
    }

    public static TreeViewActionHandlerResult success() {
        return new TreeViewActionHandlerResult(true, null);
    }

    public static TreeViewActionHandlerResult success(String message) {
        return new TreeViewActionHandlerResult(true, message);
    }
}
