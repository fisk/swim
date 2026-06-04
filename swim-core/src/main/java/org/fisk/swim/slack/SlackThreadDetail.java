package org.fisk.swim.slack;

import java.util.List;

public record SlackThreadDetail(
        String workspaceId,
        String conversationId,
        String conversationName,
        String threadTs,
        List<SlackMessageEntry> messages) {
}
