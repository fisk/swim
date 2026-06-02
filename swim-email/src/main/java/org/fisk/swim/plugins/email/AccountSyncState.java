package org.fisk.swim.plugins.email;

record AccountSyncState(
        String accountId,
        String lastSyncAt,
        boolean backfillComplete,
        long lastSeenUid,
        long nextBackfillUid) {
    AccountSyncState {
        lastSeenUid = Math.max(0L, lastSeenUid);
        nextBackfillUid = Math.max(0L, nextBackfillUid);
    }

    static AccountSyncState empty(String accountId) {
        return new AccountSyncState(accountId, null, false, 0L, 0L);
    }
}
