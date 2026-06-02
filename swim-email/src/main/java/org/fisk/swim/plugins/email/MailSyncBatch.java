package org.fisk.swim.plugins.email;

import java.util.List;

record MailSyncBatch(
        List<ImportedMailMessage> messages,
        boolean success,
        String statusMessage,
        int totalMessages,
        int startIndex,
        int endIndex,
        long highWatermarkUid,
        long nextBackfillUid) {
    MailSyncBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        statusMessage = statusMessage == null ? "" : statusMessage;
        totalMessages = Math.max(0, totalMessages);
        startIndex = Math.max(0, startIndex);
        endIndex = Math.max(0, endIndex);
        highWatermarkUid = Math.max(0L, highWatermarkUid);
        nextBackfillUid = Math.max(0L, nextBackfillUid);
    }

    static MailSyncBatch success(List<ImportedMailMessage> messages, String statusMessage) {
        int size = messages == null ? 0 : messages.size();
        return new MailSyncBatch(messages, true, statusMessage, size, size == 0 ? 0 : 1, size, 0L, 0L);
    }

    static MailSyncBatch success(
            List<ImportedMailMessage> messages,
            String statusMessage,
            int totalMessages,
            int startIndex,
            int endIndex) {
        return new MailSyncBatch(messages, true, statusMessage, totalMessages, startIndex, endIndex, 0L, 0L);
    }

    static MailSyncBatch success(
            List<ImportedMailMessage> messages,
            String statusMessage,
            int totalMessages,
            int startIndex,
            int endIndex,
            long highWatermarkUid) {
        return new MailSyncBatch(messages, true, statusMessage, totalMessages, startIndex, endIndex, highWatermarkUid, 0L);
    }

    static MailSyncBatch success(
            List<ImportedMailMessage> messages,
            String statusMessage,
            int totalMessages,
            int startIndex,
            int endIndex,
            long highWatermarkUid,
            long nextBackfillUid) {
        return new MailSyncBatch(messages, true, statusMessage, totalMessages, startIndex, endIndex, highWatermarkUid,
                nextBackfillUid);
    }

    static MailSyncBatch failure(String statusMessage) {
        return new MailSyncBatch(List.of(), false, statusMessage, 0, 0, 0, 0L, 0L);
    }
}
