package org.fisk.swim.mail;

public record MailDraft(
        String accountId,
        String to,
        String cc,
        String bcc,
        String subject,
        String body) {
}
