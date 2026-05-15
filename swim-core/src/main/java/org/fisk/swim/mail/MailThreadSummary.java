package org.fisk.swim.mail;

import java.util.List;

public record MailThreadSummary(
        long threadId,
        String accountId,
        String subject,
        String participants,
        String snippet,
        String receivedAt,
        boolean unread,
        int messageCount,
        List<String> tags) {
    public MailThreadSummary {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
