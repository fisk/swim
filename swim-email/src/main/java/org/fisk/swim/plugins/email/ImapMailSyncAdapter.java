package org.fisk.swim.plugins.email;

final class ImapMailSyncAdapter implements MailSyncAdapter {
    private long _nextBackfillUid;
    private long _uidValidity;

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account) throws Exception {
        MailSyncBatch batch = JakartaMailSupport.fetchImap(account);
        _nextBackfillUid = batch.nextBackfillUid();
        _uidValidity = batch.uidValidity();
        return batch;
    }

    @Override
    public MailSyncBatch fetch(EmailAccountConfig account, AccountSyncState syncState) throws Exception {
        if (syncState != null && syncState.backfillComplete()) {
            _nextBackfillUid = 0L;
            if (syncState.lastSeenUid() > 0L) {
                MailSyncBatch batch = JakartaMailSupport.fetchImapSinceUid(account, syncState.lastSeenUid(), syncState.uidValidity());
                _uidValidity = batch.uidValidity();
                if (batch.uidValidity() > 0L && batch.uidValidity() != syncState.uidValidity()) {
                    _nextBackfillUid = batch.nextBackfillUid();
                }
                return batch;
            }
            return fetch(account);
        }
        if (syncState != null) {
            _nextBackfillUid = syncState.nextBackfillUid();
            _uidValidity = syncState.uidValidity();
            if (syncState.lastSeenUid() > 0L) {
                MailSyncBatch incremental = JakartaMailSupport.fetchImapSinceUid(account, syncState.lastSeenUid(), syncState.uidValidity());
                _uidValidity = incremental.uidValidity();
                if (incremental.uidValidity() > 0L && incremental.uidValidity() != syncState.uidValidity()) {
                    _nextBackfillUid = incremental.nextBackfillUid();
                }
                return MailSyncBatch.success(
                        incremental.messages(),
                        incremental.statusMessage(),
                        incremental.totalMessages(),
                        incremental.startIndex(),
                        incremental.endIndex(),
                        incremental.highWatermarkUid(),
                        _nextBackfillUid).withUidValidity(incremental.uidValidity());
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
        MailSyncBatch batch = JakartaMailSupport.fetchImapOlderThanUid(account, _nextBackfillUid, _uidValidity);
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
