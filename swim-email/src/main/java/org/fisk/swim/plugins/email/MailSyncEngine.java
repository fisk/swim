package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class MailSyncEngine {
    private final MailSyncAdapterFactory _adapterFactory;

    MailSyncEngine(MailSyncAdapterFactory adapterFactory) {
        _adapterFactory = adapterFactory;
    }

    RefreshPlan prepare(EmailPaths paths) throws IOException {
        EmailAccountsConfig accounts = EmailConfigStore.loadAccounts(paths);
        EmailTagRulesConfig rules = EmailConfigStore.loadTagRules(paths);
        var results = new ArrayList<AccountSyncResult>();
        for (EmailAccountConfig account : accounts.accounts()) {
            results.add(fetchAccount(account));
        }
        return new RefreshPlan(accounts, rules, results);
    }

    void apply(Connection connection, RefreshPlan plan) throws SQLException {
        MailDb.syncAccounts(connection, plan.accounts());
        MailDb.syncTagRules(connection, plan.rules());
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (AccountSyncResult result : plan.results()) {
                if (result.batch().success()) {
                    MailDb.replaceAccountMessages(connection, result.account().normalizedId(), result.batch().messages());
                    MailDb.recordAccountSyncState(connection, result.account().normalizedId(), Instant.now().toString(),
                            result.batch().statusMessage().isBlank()
                                    ? result.batch().messages().size() + " messages"
                                    : result.batch().statusMessage());
                } else {
                    MailDb.recordAccountSyncState(connection, result.account().normalizedId(), null,
                            result.batch().statusMessage());
                }
            }
            MailDb.reapplyTags(connection);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private AccountSyncResult fetchAccount(EmailAccountConfig account) {
        try {
            return new AccountSyncResult(account, _adapterFactory.create(account).fetch(account));
        } catch (Exception e) {
            return new AccountSyncResult(account, MailSyncBatch.failure("Sync failed: " + rootMessage(e)));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    record RefreshPlan(EmailAccountsConfig accounts, EmailTagRulesConfig rules, List<AccountSyncResult> results) {
    }

    private record AccountSyncResult(EmailAccountConfig account, MailSyncBatch batch) {
    }
}
