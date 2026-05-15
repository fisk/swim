package org.fisk.swim.plugins.email;

interface MailSyncAdapterFactory {
    MailSyncAdapter create(EmailAccountConfig account);
}
