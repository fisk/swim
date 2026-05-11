package org.fisk.swim.plugins.treeview;

import java.nio.file.Path;
import java.util.List;

public record TreeViewRenderSnapshot(
    Path rootPath,
    Path selectedPath,
    int scrollOffset,
    List<String> lines
) {
}
