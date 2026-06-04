package org.fisk.swim.slack;

import java.util.List;

public record SlackMessagePage(
        List<SlackMessageSummary> messages,
        int totalCount,
        boolean hasMore) {
}
