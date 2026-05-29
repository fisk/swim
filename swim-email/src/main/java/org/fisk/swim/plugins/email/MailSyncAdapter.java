package org.fisk.swim.plugins.email;

import java.util.List;

interface MailSyncAdapter {
    MailSyncBatch fetch(EmailAccountConfig account) throws Exception;

    default boolean hasMore() {
        return false;
    }

    default MailSyncBatch fetchNext(EmailAccountConfig account) throws Exception {
        return MailSyncBatch.success(List.of(), "");
    }

    default String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return "";
    }
}
