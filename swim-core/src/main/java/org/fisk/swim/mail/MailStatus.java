package org.fisk.swim.mail;

public record MailStatus(
        int unreadCount,
        MailNotification notification) {
    public static MailStatus empty() {
        return new MailStatus(0, null);
    }

    public MailStatus {
        unreadCount = Math.max(0, unreadCount);
    }
}
