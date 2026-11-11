package org.fisk.swim.plugins.email;

import java.util.List;

interface MailSyncAdapter {
    default void useCapturedKeys(java.util.function.Function<List<String>, java.util.Set<String>> lookup) {
    }

    default List<String> capturedKeysToRecord() {
        return List.of();
    }

    MailSyncBatch fetch(EmailAccountConfig account) throws Exception;

    default MailSyncBatch fetch(EmailAccountConfig account, AccountSyncState syncState) throws Exception {
        return fetch(account);
    }

    default boolean hasMore() {
        return false;
    }

    default MailSyncBatch fetchNext(EmailAccountConfig account) throws Exception {
        return MailSyncBatch.success(List.of(), "");
    }

    default long backfillCursor() {
        return 0L;
    }

    default String loadBody(EmailAccountConfig account, String folderName, String internetMessageId) throws Exception {
        return "";
    }
}
