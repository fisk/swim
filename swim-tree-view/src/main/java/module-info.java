module org.fisk.swim.tree.view {
    requires org.fisk.swim.launcher;

    exports org.fisk.swim.plugins.treeview;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.treeview.TreeViewPlugin;
}
