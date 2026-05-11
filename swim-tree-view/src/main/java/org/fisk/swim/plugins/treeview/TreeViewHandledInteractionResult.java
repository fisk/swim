package org.fisk.swim.plugins.treeview;

public record TreeViewHandledInteractionResult(
    TreeViewInteractionResult interactionResult,
    TreeViewActionHandlerResult actionHandlerResult
) {
}
