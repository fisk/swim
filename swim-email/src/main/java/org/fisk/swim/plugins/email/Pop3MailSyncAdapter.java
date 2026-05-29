package org.fisk.swim.plugins.email;

final class Pop3MailSyncAdapter implements MailSyncAdapter {
    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        return JakartaMailSupport.fetchPop3(account);
    }

    @Override
    public String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return JakartaMailSupport.loadPop3Body(account, internetMessageId);
    }
}
