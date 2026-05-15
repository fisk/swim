package org.fisk.swim.mail;

public record MailAccountSummary(
        String id,
        String name,
        String protocol,
        int threadCount,
        int unreadCount,
        String lastSyncAt,
        String syncStatus) {
}
