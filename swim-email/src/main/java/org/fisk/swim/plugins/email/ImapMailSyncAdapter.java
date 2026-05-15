package org.fisk.swim.plugins.email;

final class ImapMailSyncAdapter implements MailSyncAdapter {
    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        return JakartaMailSupport.fetchImap(account);
    }
}
