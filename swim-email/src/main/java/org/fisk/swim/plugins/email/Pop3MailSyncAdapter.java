package org.fisk.swim.plugins.email;

final class Pop3MailSyncAdapter implements MailSyncAdapter {
    private int _nextEndIndex;
    private java.util.function.Function<java.util.List<String>, java.util.Set<String>> _capturedKeys = keys -> java.util.Set.of();

    @Override
    public void useCapturedKeys(java.util.function.Function<java.util.List<String>, java.util.Set<String>> lookup) {
        _capturedKeys = lookup;
    }

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        MailSyncBatch batch = JakartaMailSupport.fetchPop3(account, _capturedKeys);
        _nextEndIndex = (int) Math.max(0L, batch.nextBackfillUid());
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
        MailSyncBatch batch = JakartaMailSupport.fetchPop3Range(account, _nextEndIndex, _capturedKeys);
        _nextEndIndex = (int) Math.max(0L, batch.nextBackfillUid());
        return batch;
    }

    @Override
    public long backfillCursor() {
        return _nextEndIndex;
    }

    @Override
    public String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return JakartaMailSupport.loadPop3Body(account, internetMessageId);
    }
}
