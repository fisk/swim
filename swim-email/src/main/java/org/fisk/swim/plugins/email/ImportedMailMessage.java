package org.fisk.swim.plugins.email;

record ImportedMailMessage(
        String accountId,
        String folderName,
        String internetMessageId,
        String threadKey,
        String subject,
        String fromName,
        String fromEmail,
        String toSummary,
        String sentAt,
        String receivedAt,
        String snippet,
        String bodyText,
        boolean unread) {
    String effectiveTimestamp() {
        if (receivedAt != null && !receivedAt.isBlank()) {
            return receivedAt;
        }
        if (sentAt != null && !sentAt.isBlank()) {
            return sentAt;
        }
        return "";
    }

    String participants() {
        String from = fromName != null && !fromName.isBlank() ? fromName : fromEmail;
        if (from == null || from.isBlank()) {
            from = "(unknown)";
        }
        if (toSummary == null || toSummary.isBlank()) {
            return from;
        }
        return from + " -> " + toSummary;
    }
}
