package org.fisk.swim.plugins.email;

record AccountSyncState(
        String accountId,
        String lastSyncAt,
        boolean backfillComplete,
        long lastSeenUid,
        long nextBackfillUid,
        long uidValidity) {
    AccountSyncState {
        lastSeenUid = Math.max(0L, lastSeenUid);
        nextBackfillUid = Math.max(0L, nextBackfillUid);
    }

    AccountSyncState(String accountId, String lastSyncAt, boolean backfillComplete, long lastSeenUid, long nextBackfillUid) {
        this(accountId, lastSyncAt, backfillComplete, lastSeenUid, nextBackfillUid, 0L);
    }

    static AccountSyncState empty(String accountId) {
        return new AccountSyncState(accountId, null, false, 0L, 0L);
    }
}
