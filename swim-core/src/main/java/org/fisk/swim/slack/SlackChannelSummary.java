package org.fisk.swim.slack;

public record SlackChannelSummary(
        String workspaceId,
        String channelId,
        String name,
        String displayName,
        String kind,
        String topic,
        String lastActivityAt,
        boolean archived) {
}
