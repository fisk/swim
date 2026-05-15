package org.fisk.swim.plugins.email;

import java.util.List;

record MailSyncBatch(List<ImportedMailMessage> messages, boolean success, String statusMessage) {
    MailSyncBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        statusMessage = statusMessage == null ? "" : statusMessage;
    }

    static MailSyncBatch success(List<ImportedMailMessage> messages, String statusMessage) {
        return new MailSyncBatch(messages, true, statusMessage);
    }

    static MailSyncBatch failure(String statusMessage) {
        return new MailSyncBatch(List.of(), false, statusMessage);
    }
}
