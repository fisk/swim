package org.fisk.swim.slack;

public record SlackMessageSummary(
        String workspaceId,
        String conversationId,
        String ts,
        String threadTs,
        String userDisplayName,
        String sentAt,
        String text,
        int replyCount) {
}
