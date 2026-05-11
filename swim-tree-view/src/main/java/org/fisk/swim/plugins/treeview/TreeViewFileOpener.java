package org.fisk.swim.plugins.treeview;

import java.nio.file.Path;

@FunctionalInterface
public interface TreeViewFileOpener {
    void open(Path path);
}
