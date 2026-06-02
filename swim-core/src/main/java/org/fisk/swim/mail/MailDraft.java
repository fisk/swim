package org.fisk.swim.mail;

public record MailDraft(
        String accountId,
        String to,
        String cc,
        String bcc,
        String subject,
        String body,
        String inReplyToMessageId) {
    public MailDraft(
            String accountId,
            String to,
            String cc,
            String bcc,
            String subject,
            String body) {
        this(accountId, to, cc, bcc, subject, body, "");
    }
}
