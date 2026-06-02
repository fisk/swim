package org.fisk.swim.mail;

import java.util.List;

public record MailMessageDetail(
        long messageId,
        long threadId,
        String subject,
        String from,
        String to,
        String cc,
        String sentAt,
        String bodyText,
        List<String> tags,
        String internetMessageId) {
    public MailMessageDetail {
        tags = tags == null ? List.of() : List.copyOf(tags);
        internetMessageId = internetMessageId == null ? "" : internetMessageId;
    }

    public MailMessageDetail(
            long messageId,
            long threadId,
            String subject,
            String from,
            String to,
            String cc,
            String sentAt,
            String bodyText,
            List<String> tags) {
        this(messageId, threadId, subject, from, to, cc, sentAt, bodyText, tags, "");
    }

    public MailMessageDetail(
            long messageId,
            long threadId,
            String subject,
            String from,
            String to,
            String sentAt,
            String bodyText,
            List<String> tags) {
        this(messageId, threadId, subject, from, to, "", sentAt, bodyText, tags, "");
    }

    public MailMessageDetail(
            long messageId,
            long threadId,
            String subject,
            String from,
            String to,
            String sentAt,
            String bodyText,
            List<String> tags,
            String internetMessageId) {
        this(messageId, threadId, subject, from, to, "", sentAt, bodyText, tags, internetMessageId);
    }
}
