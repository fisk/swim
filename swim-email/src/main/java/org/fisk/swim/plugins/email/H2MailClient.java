package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailThreadPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class H2MailClient implements MailClient {
    private static final Logger LOG = LoggerFactory.getLogger(H2MailClient.class);
    private static final int SNAPSHOT_THREAD_LIMIT = 100;

    private final EmailPaths _paths;
    private final Connection _connection;
    private final MailSyncAdapterFactory _adapterFactory;
    private final MailSyncEngine _syncEngine;
    private final MailDeliveryService _deliveryService;
    private final Object _dbLock = new Object();
    private final AtomicBoolean _refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean _backfillInFlight = new AtomicBoolean();
    private final AtomicLong _refreshGeneration = new AtomicLong();
    private volatile boolean _closed;

    H2MailClient(EmailPaths paths) throws SQLException, IOException {
        this(paths, new DefaultMailSyncAdapterFactory(), new SmtpMailSupport(paths), true);
    }

    H2MailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory) throws SQLException, IOException {
        this(paths, adapterFactory, new SmtpMailSupport(paths), true);
    }

    H2MailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory, MailDeliveryService deliveryService)
            throws SQLException, IOException {
        this(paths, adapterFactory, deliveryService, true);
    }

    H2MailClient(EmailPaths paths, MailSyncAdapterFactory adapterFactory, MailDeliveryService deliveryService,
            boolean initialRefresh)
            throws SQLException, IOException {
        _paths = paths;
        EmailConfigStore.ensureDefaultFiles(paths);
        ensureH2DriverLoaded();
        _connection = DriverManager.getConnection(paths.databaseJdbcUrl());
        _adapterFactory = adapterFactory;
        _syncEngine = new MailSyncEngine(adapterFactory);
        _deliveryService = deliveryService;
        MailDb.initialize(_connection);
        bootstrapConfigurationState();
        if (initialRefresh) {
            refresh();
        }
    }

    @Override
    public MailSnapshot snapshot() {
        synchronized (_dbLock) {
            try {
                return MailDb.loadSnapshot(_connection, _paths, SNAPSHOT_THREAD_LIMIT);
            } catch (SQLException e) {
                LOG.error("Failed to load mail snapshot", e);
                return new MailSnapshot(java.util.List.of(), java.util.List.of(),
                        "Failed to load mail from " + _paths.databaseFilePath() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public MailThreadPage loadThreads(String query, int offset, int limit) {
        synchronized (_dbLock) {
            try {
                return MailDb.loadThreadPage(_connection, query, offset, limit);
            } catch (SQLException e) {
                LOG.error("Failed to load mail threads", e);
                return new MailThreadPage(java.util.List.of(), 0);
            }
        }
    }

    @Override
    public MailMessageDetail loadMessage(long threadId) {
        MailMessageDetail detail;
        synchronized (_dbLock) {
            try {
                detail = MailDb.loadMessage(_connection, threadId);
            } catch (SQLException e) {
                LOG.error("Failed to load mail message for thread {}", threadId, e);
                return new MailMessageDetail(0L, threadId, "(error)", "", "", "", e.getMessage(), java.util.List.of());
            }
        }
        if (_closed || detail.messageId() == 0L || (detail.bodyText() != null && !detail.bodyText().isBlank())) {
            return detail;
        }
        try {
            MailDb.MessageHydrationTarget target;
            synchronized (_dbLock) {
                target = MailDb.loadMessageHydrationTarget(_connection, threadId);
            }
            if (target == null || target.internetMessageId() == null || target.internetMessageId().isBlank()) {
                return detail;
            }
            EmailAccountConfig account = resolveAccount(target.accountId());
            if (account == null) {
                return detail;
            }
            String bodyText = _adapterFactory.create(account).loadBody(account, target.folderName(), target.internetMessageId());
            if (bodyText == null || bodyText.isBlank()) {
                return detail;
            }
            synchronized (_dbLock) {
                if (_closed) {
                    return detail;
                }
                MailDb.updateMessageBody(_connection, target.messageId(), target.threadId(), bodyText);
                return MailDb.loadMessage(_connection, threadId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to hydrate mail body for thread {}", threadId, e);
            return detail;
        }
    }

    @Override
    public void refresh() {
        if (_closed || _backfillInFlight.get() || !_refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        long generation = _refreshGeneration.incrementAndGet();
        try {
            MailSyncEngine.RefreshPlan plan = _syncEngine.prepare(_paths);
            if (_closed) {
                return;
            }
            synchronized (_dbLock) {
                if (_closed) {
                    return;
                }
                _syncEngine.apply(_connection, plan);
            }
            startBackfill(plan, generation);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to refresh mail state", e);
        } finally {
            _refreshInFlight.set(false);
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
        _closed = true;
        _refreshGeneration.incrementAndGet();
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

    private void bootstrapConfigurationState() throws IOException, SQLException {
        EmailAccountsConfig accounts = EmailConfigStore.loadAccounts(_paths);
        EmailTagRulesConfig rules = EmailConfigStore.loadTagRules(_paths);
        synchronized (_dbLock) {
            MailDb.syncAccounts(_connection, accounts);
            MailDb.syncTagRules(_connection, rules);
            MailDb.reapplyTags(_connection);
        }
    }

    private void startBackfill(MailSyncEngine.RefreshPlan plan, long generation) {
        boolean hasBackfill = plan.results().stream().anyMatch(result -> result.batch().success() && result.adapter().hasMore());
        if (!hasBackfill || !_backfillInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> runBackfill(plan, generation), "mail-backfill");
        thread.setDaemon(true);
        thread.start();
    }

    private void runBackfill(MailSyncEngine.RefreshPlan plan, long generation) {
        try {
            for (MailSyncEngine.AccountSyncResult result : plan.results()) {
                while (!_closed && generation == _refreshGeneration.get() && result.adapter().hasMore()) {
                    MailSyncBatch batch;
                    try {
                        batch = result.adapter().fetchNext(result.account());
                    } catch (Exception e) {
                        LOG.warn("Failed to backfill mail for {}", result.account().normalizedId(), e);
                        recordBackfillFailure(result.account().normalizedId(), rootMessage(e), generation);
                        break;
                    }
                    applyBackfillBatch(result.account(), result.adapter(), batch, generation);
                }
            }
        } finally {
            _backfillInFlight.set(false);
        }
    }

    private void applyBackfillBatch(
            EmailAccountConfig account,
            MailSyncAdapter adapter,
            MailSyncBatch batch,
            long generation) {
        synchronized (_dbLock) {
            if (_closed || generation != _refreshGeneration.get()) {
                return;
            }
            try {
                boolean originalAutoCommit = _connection.getAutoCommit();
                _connection.setAutoCommit(false);
                try {
                    if (batch.success()) {
                        MailDb.upsertAccountMessages(_connection, account.normalizedId(), batch.messages());
                        MailDb.rebuildThreads(_connection, account.normalizedId());
                        MailDb.reapplyTags(_connection);
                        int cachedMessages = MailDb.countMessages(_connection, account.normalizedId());
                        String status = adapter.hasMore()
                                ? JakartaMailSupport.backgroundSyncStatus(cachedMessages, batch.totalMessages())
                                : cachedMessages + " messages";
                        MailDb.recordAccountSyncState(_connection, account.normalizedId(), Instant.now().toString(), status,
                                !adapter.hasMore());
                    } else {
                        MailDb.recordAccountSyncState(_connection, account.normalizedId(), null, batch.statusMessage(), false);
                    }
                    _connection.commit();
                } catch (SQLException e) {
                    _connection.rollback();
                    throw e;
                } finally {
                    _connection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to apply backfill batch for " + account.normalizedId(), e);
            }
        }
    }

    private void recordBackfillFailure(String accountId, String message, long generation) {
        synchronized (_dbLock) {
            if (_closed || generation != _refreshGeneration.get()) {
                return;
            }
            try {
                MailDb.recordAccountSyncState(_connection, accountId, null, "Backfill failed: " + message, false);
            } catch (SQLException e) {
                LOG.warn("Failed to record backfill failure for {}", accountId, e);
            }
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

    private static void ensureH2DriverLoaded() throws SQLException {
        try {
            Class.forName("org.h2.Driver", true, H2MailClient.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 JDBC driver is unavailable", e);
        }
    }
}
