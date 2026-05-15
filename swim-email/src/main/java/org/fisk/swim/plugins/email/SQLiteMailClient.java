package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SQLiteMailClient implements MailClient {
    private static final Logger LOG = LoggerFactory.getLogger(SQLiteMailClient.class);

    private final EmailPaths _paths;
    private final Connection _connection;
    private final MailSyncEngine _syncEngine;
    private final MailDeliveryService _deliveryService;
    private final Object _dbLock = new Object();

    SQLiteMailClient(EmailPaths paths) throws SQLException, IOException {
        this(paths, new DefaultMailSyncAdapterFactory(), new SmtpMailSupport(paths), true);
    }

    SQLiteMailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory) throws SQLException, IOException {
        this(paths, adapterFactory, new SmtpMailSupport(paths), true);
    }

    SQLiteMailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory, MailDeliveryService deliveryService)
            throws SQLException, IOException {
        this(paths, adapterFactory, deliveryService, true);
    }

    SQLiteMailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory, MailDeliveryService deliveryService,
            boolean initialRefresh)
            throws SQLException, IOException {
        _paths = paths;
        EmailConfigStore.ensureDefaultFiles(paths);
        ensureSqliteDriverLoaded();
        _connection = DriverManager.getConnection("jdbc:sqlite:" + paths.databasePath());
        _syncEngine = new MailSyncEngine(adapterFactory);
        _deliveryService = deliveryService;
        MailDb.initialize(_connection);
        if (initialRefresh) {
            refresh();
        }
    }

    @Override
    public MailSnapshot snapshot() {
        synchronized (_dbLock) {
            try {
                return MailDb.loadSnapshot(_connection, _paths);
            } catch (SQLException e) {
                LOG.error("Failed to load mail snapshot", e);
                return new MailSnapshot(java.util.List.of(), java.util.List.of(),
                        "Failed to load mail from " + _paths.databasePath() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public MailMessageDetail loadMessage(long threadId) {
        synchronized (_dbLock) {
            try {
                return MailDb.loadMessage(_connection, threadId);
            } catch (SQLException e) {
                LOG.error("Failed to load mail message for thread {}", threadId, e);
                return new MailMessageDetail(0L, threadId, "(error)", "", "", "", e.getMessage(), java.util.List.of());
            }
        }
    }

    @Override
    public void refresh() {
        try {
            MailSyncEngine.RefreshPlan plan = _syncEngine.prepare(_paths);
            synchronized (_dbLock) {
                _syncEngine.apply(_connection, plan);
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to refresh mail state", e);
        }
    }

    @Override
    public MailSendResult sendDraft(MailDraft draft) {
        try {
            EmailAccountConfig account = resolveAccount(draft.accountId());
            if (account == null) {
                return MailSendResult.failure("Mail account not found: " + draft.accountId());
            }
            MailSendResult result = _deliveryService.send(account, draft);
            if (result.success()) {
                synchronized (_dbLock) {
                    MailDb.appendSentDraft(_connection, account.normalizedId(), account.displayName(), account.username(),
                            draft, Instant.now().toString());
                    MailDb.reapplyTags(_connection);
                }
            }
            return result;
        } catch (Exception e) {
            LOG.error("Failed to send mail draft", e);
            return MailSendResult.failure("Failed to send mail: " + rootMessage(e));
        }
    }

    @Override
    public Path getDataPath() {
        return _paths.emailHome();
    }

    @Override
    public void close() {
        synchronized (_dbLock) {
            try {
                _connection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close mail database", e);
            }
        }
    }

    private EmailAccountConfig resolveAccount(String accountId) throws IOException {
        EmailAccountsConfig config = EmailConfigStore.loadAccounts(_paths);
        if (accountId != null && !accountId.isBlank()) {
            for (EmailAccountConfig account : config.accounts()) {
                if (account.normalizedId().equals(accountId)) {
                    return account;
                }
            }
        }
        return config.accounts().isEmpty() ? null : config.accounts().getFirst();
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

    private static void ensureSqliteDriverLoaded() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC", true, SQLiteMailClient.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver is unavailable", e);
        }
    }
}
