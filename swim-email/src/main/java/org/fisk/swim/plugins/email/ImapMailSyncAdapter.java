package org.fisk.swim.plugins.email;

final class ImapMailSyncAdapter implements MailSyncAdapter {
    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        return JakartaMailSupport.fetchImap(account);
    }

    @Override
    public String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return JakartaMailSupport.loadImapBody(account, folderName, internetMessageId);
    }
}
