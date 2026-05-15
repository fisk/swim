package org.fisk.swim.mail;

import java.util.List;

public record MailMessageDetail(
        long messageId,
        long threadId,
        String subject,
        String from,
        String to,
        String sentAt,
        String bodyText,
        List<String> tags) {
    public MailMessageDetail {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
