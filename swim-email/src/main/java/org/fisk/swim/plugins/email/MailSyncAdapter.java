package org.fisk.swim.plugins.email;

interface MailSyncAdapter {
    MailSyncBatch fetch(EmailAccountConfig account) throws Exception;
}
