package org.fisk.swim.plugins.treeview;

@FunctionalInterface
public interface TreeViewActionHandler {
    TreeViewActionHandlerResult handle(TreeViewAction action);
}
