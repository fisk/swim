package org.fisk.swim.mail;

public record MailDraft(
        String accountId,
        String to,
        String subject,
        String body) {
}
