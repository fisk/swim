package org.fisk.swim.slack;

public record SlackDraft(
        String workspaceId,
        String conversationId,
        String threadTs,
        String text) {
}
