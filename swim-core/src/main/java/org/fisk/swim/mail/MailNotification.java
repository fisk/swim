package org.fisk.swim.mail;

public record MailNotification(
        String heading,
        String sender,
        String detail) {
    public MailNotification {
        heading = normalize(heading);
        sender = normalize(sender);
        detail = normalize(detail);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
