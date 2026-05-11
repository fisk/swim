package org.fisk.swim.plugins.treeview;

import java.nio.file.Path;

public record TreeViewRow(
    Path path,
    String label,
    int depth,
    boolean directory,
    boolean expanded,
    boolean selected
) {
}
