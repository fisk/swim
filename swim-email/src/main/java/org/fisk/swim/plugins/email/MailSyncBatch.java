package org.fisk.swim.plugins.email;

import java.util.List;

record MailSyncBatch(
        List<ImportedMailMessage> messages,
        boolean success,
        String statusMessage,
        int totalMessages,
        int startIndex,
        int endIndex) {
    MailSyncBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        statusMessage = statusMessage == null ? "" : statusMessage;
        totalMessages = Math.max(0, totalMessages);
        startIndex = Math.max(0, startIndex);
        endIndex = Math.max(0, endIndex);
    }

    static MailSyncBatch success(List<ImportedMailMessage> messages, String statusMessage) {
        int size = messages == null ? 0 : messages.size();
        return new MailSyncBatch(messages, true, statusMessage, size, size == 0 ? 0 : 1, size);
    }

    static MailSyncBatch success(
            List<ImportedMailMessage> messages,
            String statusMessage,
            int totalMessages,
            int startIndex,
            int endIndex) {
        return new MailSyncBatch(messages, true, statusMessage, totalMessages, startIndex, endIndex);
    }

    static MailSyncBatch failure(String statusMessage) {
        return new MailSyncBatch(List.of(), false, statusMessage, 0, 0, 0);
    }
}
