package org.fisk.swim.mail;

public record MailMessageSummary(
        long messageId,
        long threadId,
        long parentMessageId,
        String subject,
        String from,
        String to,
        String receivedAt,
        String snippet,
        boolean unread) {
    public MailMessageSummary {
        parentMessageId = Math.max(0L, parentMessageId);
        subject = subject == null ? "" : subject;
        from = from == null ? "" : from;
        to = to == null ? "" : to;
        receivedAt = receivedAt == null ? "" : receivedAt;
        snippet = snippet == null ? "" : snippet;
    }
}
