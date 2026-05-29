package org.fisk.swim.plugins.email;

final class Pop3MailSyncAdapter implements MailSyncAdapter {
    private int _nextEndIndex;

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        MailSyncBatch batch = JakartaMailSupport.fetchPop3(account);
        _nextEndIndex = Math.max(0, batch.startIndex() - 1);
        return batch;
    }

    @Override
    public boolean hasMore() {
        return _nextEndIndex > 0;
    }

    @Override
    public MailSyncBatch fetchNext(EmailAccountConfig account) throws Exception {
        if (!hasMore()) {
            return MailSyncBatch.success(java.util.List.of(), "");
        }
        MailSyncBatch batch = JakartaMailSupport.fetchPop3Range(account, _nextEndIndex);
        _nextEndIndex = Math.max(0, batch.startIndex() - 1);
        return batch;
    }

    @Override
    public String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return JakartaMailSupport.loadPop3Body(account, internetMessageId);
    }
}
