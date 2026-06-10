package org.fisk.swim.plugins.email;

final class ImapMailSyncAdapter implements MailSyncAdapter {
    private long _nextBackfillUid;

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        MailSyncBatch batch = JakartaMailSupport.fetchImap(account);
        _nextBackfillUid = batch.nextBackfillUid();
        return batch;
    }

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account, AccountSyncState syncState) throws Exception {
        if (syncState != null && syncState.backfillComplete()) {
            _nextBackfillUid = 0L;
            if (syncState.lastSeenUid() > 0L) {
                return JakartaMailSupport.fetchImapSinceUid(account, syncState.lastSeenUid());
            }
            return fetch(account);
        }
        if (syncState != null) {
            _nextBackfillUid = syncState.nextBackfillUid();
            if (syncState.lastSeenUid() > 0L) {
                MailSyncBatch incremental = JakartaMailSupport.fetchImapSinceUid(account, syncState.lastSeenUid());
                return MailSyncBatch.success(
                        incremental.messages(),
                        incremental.statusMessage(),
                        incremental.totalMessages(),
                        incremental.startIndex(),
                        incremental.endIndex(),
                        incremental.highWatermarkUid(),
                        _nextBackfillUid);
            }
            if (_nextBackfillUid > 0L) {
                return MailSyncBatch.success(java.util.List.of(), "Resuming backfill", 0, 0, 0, 0L, _nextBackfillUid);
            }
        }
        return fetch(account);
    }

    @Override
    public boolean hasMore() {
        return _nextBackfillUid > 0L;
    }

    @Override
    public MailSyncBatch fetchNext(EmailAccountConfig account) throws Exception {
        if (!hasMore()) {
            return MailSyncBatch.success(java.util.List.of(), "");
        }
        MailSyncBatch batch = JakartaMailSupport.fetchImapOlderThanUid(account, _nextBackfillUid);
        _nextBackfillUid = batch.nextBackfillUid();
        return batch;
    }

    @Override
    public long backfillCursor() {
        return _nextBackfillUid;
    }

    @Override
    public String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return JakartaMailSupport.loadImapBody(account, folderName, internetMessageId);
    }
}
