package org.fisk.swim.plugins.treeview;

public record TreeViewInteractionResult(
    TreeViewCommandResult commandResult,
    TreeViewRenderSnapshot snapshot
) {
}
