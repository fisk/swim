package org.fisk.swim.mail;

import java.nio.file.Path;

public interface MailClient extends AutoCloseable {
    MailSnapshot snapshot();

    MailMessageDetail loadMessage(long threadId);

    void refresh();

    default MailSendResult sendDraft(MailDraft draft) {
        return MailSendResult.failure("Sending is not available");
    }

    Path getDataPath();

    @Override
    default void close() {
    }
}
