package org.fisk.swim.plugins.email;

interface MailSyncAdapter {
    MailSyncBatch fetch(EmailAccountConfig account) throws Exception;

    default String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return "";
    }
}
