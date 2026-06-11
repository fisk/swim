package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailMessageSummary;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailThreadFilter;
import org.fisk.swim.mail.MailThreadPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class H2MailClient implements MailClient {
    private static final Logger LOG = LoggerFactory.getLogger(H2MailClient.class);
    private static final int SNAPSHOT_THREAD_LIMIT = 100;
    private static final Gson GSON = new GsonBuilder().create();

    private final EmailPaths _paths;
    private final Connection _readConnection;
    private final Connection _writeConnection;
    private final MailSyncAdapterFactory _adapterFactory;
    private final MailSyncEngine _syncEngine;
    private final MailDeliveryService _deliveryService;
    private final Object _readLock = new Object();
    private final Object _writeLock = new Object();
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
        _adapterFactory = adapterFactory;
        _syncEngine = new MailSyncEngine(adapterFactory);
        _deliveryService = deliveryService;
        Connection writeConnection = DriverManager.getConnection(paths.databaseJdbcUrl());
        Connection readConnection = null;
        _writeConnection = writeConnection;
        try {
            MailDb.initialize(_writeConnection);
            bootstrapConfigurationState();
            readConnection = DriverManager.getConnection(paths.databaseJdbcUrl());
            _readConnection = readConnection;
            if (initialRefresh) {
                refresh();
            }
        } catch (IOException | SQLException | RuntimeException | Error e) {
            if (readConnection != null) {
                closeConnectionQuietly(readConnection, e);
            }
            closeConnectionQuietly(writeConnection, e);
            throw e;
        }
    }

    @Override
    public MailSnapshot snapshot() {
        synchronized (_readLock) {
            try {
                return augmentSnapshotWithPendingAuth(MailDb.loadSnapshot(_readConnection, _paths, SNAPSHOT_THREAD_LIMIT));
            } catch (SQLException e) {
                LOG.error("Failed to load mail snapshot", e);
                return new MailSnapshot(java.util.List.of(), java.util.List.of(),
                        "Failed to load mail from " + _paths.databaseFilePath() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public MailSnapshot snapshotWithoutUnreadCounts() {
        synchronized (_readLock) {
            try {
                return augmentSnapshotWithPendingAuth(MailDb.loadSnapshotWithoutUnreadCounts(_readConnection, _paths,
                        SNAPSHOT_THREAD_LIMIT));
            } catch (SQLException e) {
                LOG.error("Failed to load mail snapshot", e);
                return new MailSnapshot(java.util.List.of(), java.util.List.of(),
                        "Failed to load mail from " + _paths.databaseFilePath() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public MailThreadPage loadThreads(String query, int offset, int limit) {
        return loadThreads(query, offset, limit, MailThreadFilter.all());
    }

    @Override
    public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
        synchronized (_readLock) {
            try {
                return MailDb.loadThreadPage(_readConnection, query, offset, limit, filter);
            } catch (SQLException e) {
                LOG.error("Failed to load mail threads", e);
                return new MailThreadPage(java.util.List.of(), 0);
            }
        }
    }

    @Override
    public MailMessageDetail loadMessage(long threadId) {
        return loadThreadMessage(threadId);
    }

    @Override
    public MailMessageDetail loadMessageById(long messageId) {
        return loadSpecificMessage(messageId);
    }

    @Override
    public java.util.List<MailMessageSummary> loadThreadMessages(long threadId) {
        synchronized (_readLock) {
            try {
                return MailDb.loadThreadMessages(_readConnection, threadId);
            } catch (SQLException e) {
                LOG.error("Failed to load mail thread messages for thread {}", threadId, e);
                return java.util.List.of();
            }
        }
    }

    @Override
    public Map<Long, List<MailMessageSummary>> loadThreadMessages(List<Long> threadIds) {
        synchronized (_readLock) {
            try {
                return MailDb.loadThreadMessages(_readConnection, threadIds);
            } catch (SQLException e) {
                LOG.error("Failed to load mail thread messages for {} threads", threadIds == null ? 0 : threadIds.size(), e);
                return new LinkedHashMap<>();
            }
        }
    }

    @Override
    public List<String> loadTagNames() {
        synchronized (_readLock) {
            try {
                return MailDb.loadTagNames(_readConnection);
            } catch (SQLException e) {
                LOG.error("Failed to load mail tag names", e);
                return List.of();
            }
        }
    }

    @Override
    public Map<String, Integer> loadTagUnreadCounts() {
        synchronized (_readLock) {
            try {
                return MailDb.loadTagUnreadCounts(_readConnection);
            } catch (SQLException e) {
                LOG.error("Failed to load mail tag unread counts", e);
                return Map.of();
            }
        }
    }

    @Override
    public Map<String, Integer> loadAccountUnreadCounts() {
        synchronized (_readLock) {
            try {
                return MailDb.loadAccountUnreadCounts(_readConnection);
            } catch (SQLException e) {
                LOG.error("Failed to load mail account unread counts", e);
                return Map.of();
            }
        }
    }

    @Override
    public int loadUnsortedUnreadCount() {
        synchronized (_readLock) {
            try {
                return MailDb.loadUnsortedUnreadCount(_readConnection);
            } catch (SQLException e) {
                LOG.error("Failed to load unsorted unread count", e);
                return 0;
            }
        }
    }

    @Override
    public void markMessageRead(long messageId) {
        synchronized (_writeLock) {
            try {
                MailDb.markMessageRead(_writeConnection, messageId);
            } catch (SQLException e) {
                LOG.warn("Failed to mark message {} as read", messageId, e);
            }
        }
    }

    @Override
    public void refresh() {
        if (_closed || _backfillInFlight.get() || !_refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        long generation = _refreshGeneration.incrementAndGet();
        try {
            java.util.Map<String, AccountSyncState> syncStates;
            synchronized (_readLock) {
                syncStates = MailDb.loadAccountSyncStates(_readConnection);
            }
            MailSyncEngine.RefreshPlan plan = _syncEngine.prepare(_paths, syncStates);
            if (_closed) {
                return;
            }
            synchronized (_writeLock) {
                if (_closed) {
                    return;
                }
                _syncEngine.apply(_writeConnection, plan);
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
                synchronized (_writeLock) {
                    MailDb.appendSentDraft(_writeConnection, account.normalizedId(), account.displayName(), account.username(),
                            draft, Instant.now().toString());
                    MailDb.reapplyTags(_writeConnection);
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
        synchronized (_writeLock) {
            try {
                _writeConnection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close mail database", e);
            }
        }
        synchronized (_readLock) {
            try {
                _readConnection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close mail database reader", e);
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
        synchronized (_writeLock) {
            MailDb.syncAccounts(_writeConnection, accounts);
            MailDb.syncTagRules(_writeConnection, rules);
            MailDb.reapplyTags(_writeConnection);
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
                    long cursorBeforeFetch = result.adapter().backfillCursor();
                    try {
                        batch = result.adapter().fetchNext(result.account());
                    } catch (Exception e) {
                        LOG.warn("Failed to backfill mail for {}", result.account().normalizedId(), e);
                        recordBackfillFailure(result.account().normalizedId(), rootMessage(e), generation);
                        break;
                    }
                    boolean cursorStalled = backfillCursorStalled(cursorBeforeFetch, result.adapter());
                    applyBackfillBatch(result.account(), result.adapter(), batch, generation);
                    if (cursorStalled) {
                        LOG.warn("Stopped mail backfill for {} because the sync cursor did not advance",
                                result.account().normalizedId());
                        recordBackfillStopped(result.account().normalizedId(), "sync cursor did not advance", generation);
                        break;
                    }
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
        synchronized (_writeLock) {
            if (_closed || generation != _refreshGeneration.get()) {
                return;
            }
            try {
                boolean originalAutoCommit = _writeConnection.getAutoCommit();
                _writeConnection.setAutoCommit(false);
                try {
                    if (batch.success()) {
                        MailDb.upsertAccountMessagesAndRefreshThreads(
                                _writeConnection, account.normalizedId(), batch.messages());
                        int cachedMessages = MailDb.countMessages(_writeConnection, account.normalizedId());
                        String status = adapter.hasMore()
                                ? JakartaMailSupport.backgroundSyncStatus(cachedMessages, batch.totalMessages())
                                : cachedMessages + " messages";
                        AccountSyncState currentState = MailDb.loadAccountSyncStates(_writeConnection)
                                .getOrDefault(account.normalizedId(), AccountSyncState.empty(account.normalizedId()));
                        MailDb.recordAccountSyncState(_writeConnection, account.normalizedId(), Instant.now().toString(), status,
                                !adapter.hasMore(),
                                Math.max(currentState.lastSeenUid(), batch.highWatermarkUid()),
                                adapter.hasMore() ? batch.nextBackfillUid() : 0L);
                    } else {
                        AccountSyncState currentState = MailDb.loadAccountSyncStates(_writeConnection)
                                .getOrDefault(account.normalizedId(), AccountSyncState.empty(account.normalizedId()));
                        MailDb.recordAccountSyncState(_writeConnection, account.normalizedId(), null, batch.statusMessage(), false,
                                currentState.lastSeenUid(),
                                currentState.nextBackfillUid());
                    }
                    _writeConnection.commit();
                } catch (SQLException e) {
                    _writeConnection.rollback();
                    throw e;
                } finally {
                    _writeConnection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to apply backfill batch for " + account.normalizedId(), e);
            }
        }
    }

    private void recordBackfillStopped(String accountId, String message, long generation) {
        recordBackfillStatus(accountId, "Backfill stopped: " + message, generation);
    }

    private void recordBackfillFailure(String accountId, String message, long generation) {
        recordBackfillStatus(accountId, "Backfill failed: " + message, generation);
    }

    private void recordBackfillStatus(String accountId, String statusMessage, long generation) {
        synchronized (_writeLock) {
            if (_closed || generation != _refreshGeneration.get()) {
                return;
            }
            try {
                AccountSyncState currentState = MailDb.loadAccountSyncStates(_writeConnection)
                        .getOrDefault(accountId, AccountSyncState.empty(accountId));
                MailDb.recordAccountSyncState(_writeConnection, accountId, null, statusMessage, false,
                        currentState.lastSeenUid(),
                        currentState.nextBackfillUid());
            } catch (SQLException e) {
                LOG.warn("Failed to record backfill status for {}", accountId, e);
            }
        }
    }

    private static boolean backfillCursorStalled(long cursorBeforeFetch, MailSyncAdapter adapter) {
        return adapter.hasMore() && cursorBeforeFetch > 0L && adapter.backfillCursor() >= cursorBeforeFetch;
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

    private MailSnapshot augmentSnapshotWithPendingAuth(MailSnapshot snapshot) {
        if (snapshot == null || snapshot.accounts().isEmpty()) {
            return snapshot;
        }
        OAuthTokenCache cache = loadOAuthTokenCache();
        if (cache.accounts().isEmpty()) {
            return snapshot;
        }
        boolean changed = false;
        var accounts = new java.util.ArrayList<org.fisk.swim.mail.MailAccountSummary>(snapshot.accounts().size());
        for (var account : snapshot.accounts()) {
            String status = account.syncStatus();
            OAuthTokenRecord record = cache.accounts().get(account.id());
            String pending = pendingAuthMessage(record);
            if (!pending.isBlank()) {
                if (!pending.equals(status)) {
                    status = pending;
                    changed = true;
                }
            } else if (hasValidCachedAccessToken(record) && isAuthPrompt(status)) {
                status = "";
                changed = true;
            }
            if (status == null) {
                status = "";
                changed = true;
            }
            accounts.add(new org.fisk.swim.mail.MailAccountSummary(
                    account.id(),
                    account.name(),
                    account.protocol(),
                    account.threadCount(),
                    account.unreadCount(),
                    account.lastSyncAt(),
                    status));
        }
        if (!changed) {
            return snapshot;
        }
        String statusMessage = snapshot.statusMessage();
        if (isAuthPrompt(statusMessage) && accounts.stream().noneMatch(account -> isAuthPrompt(account.syncStatus()))) {
            statusMessage = "";
        }
        return new MailSnapshot(accounts, snapshot.threads(), statusMessage);
    }

    private OAuthTokenCache loadOAuthTokenCache() {
        try {
            EmailConfigStore.ensureDefaultFiles(_paths);
            if (!Files.isRegularFile(_paths.oauthTokensPath()) || Files.readString(_paths.oauthTokensPath()).trim().isEmpty()) {
                return OAuthTokenCache.empty();
            }
            try (Reader reader = Files.newBufferedReader(_paths.oauthTokensPath())) {
                OAuthTokenCache cache = GSON.fromJson(reader, OAuthTokenCache.class);
                return cache == null ? OAuthTokenCache.empty() : cache;
            }
        } catch (IOException e) {
            LOG.debug("Failed to load OAuth token cache for mail snapshot", e);
            return OAuthTokenCache.empty();
        }
    }

    private static String pendingAuthMessage(OAuthTokenRecord record) {
        if (hasValidCachedAccessToken(record)) {
            return "";
        }
        String device = pendingDeviceMessage(record);
        if (!device.isBlank()) {
            return device;
        }
        return pendingBrowserMessage(record);
    }

    private static boolean hasValidCachedAccessToken(OAuthTokenRecord record) {
        if (record == null || record.accessToken() == null || record.accessToken().isBlank()) {
            return false;
        }
        if (record.expiresAt() == null || record.expiresAt().isBlank()) {
            return false;
        }
        return java.time.Instant.parse(record.expiresAt()).minusSeconds(60).isAfter(java.time.Instant.now());
    }

    private static boolean isAuthPrompt(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return status.contains("Authorize mail at")
                || status.contains("Complete browser sign-in at")
                || status.contains("Mail sign-in complete")
                || status.contains("enter the code");
    }

    private static String pendingBrowserMessage(OAuthTokenRecord record) {
        if (record == null || record.pendingBrowserAuthorization() == null) {
            return "";
        }
        var pending = record.pendingBrowserAuthorization();
        if (pending.authorizationUrl() == null || pending.authorizationUrl().isBlank()) {
            return "";
        }
        if (pending.expiresAt() == null || pending.expiresAt().isBlank()) {
            return "";
        }
        if (java.time.Instant.parse(pending.expiresAt()).isBefore(java.time.Instant.now())) {
            return "";
        }
        if (pending.error() != null && !pending.error().isBlank()) {
            return "";
        }
        if (pending.authorizationCode() != null && !pending.authorizationCode().isBlank()) {
            return "Mail sign-in complete";
        }
        return "Complete browser sign-in at " + pending.authorizationUrl()
                + " and wait for the callback, then press e in the mail panel.";
    }

    private static String pendingDeviceMessage(OAuthTokenRecord record) {
        if (record == null || record.pendingDeviceAuthorization() == null) {
            return "";
        }
        var pending = record.pendingDeviceAuthorization();
        if (pending.expiresAt() == null || pending.expiresAt().isBlank()) {
            return "";
        }
        if (java.time.Instant.parse(pending.expiresAt()).isBefore(java.time.Instant.now())) {
            return "";
        }
        if (pending.message() != null && !pending.message().isBlank()) {
            return pending.message() + " Then press e in the mail panel.";
        }
        String uri = pending.verificationUriComplete() != null && !pending.verificationUriComplete().isBlank()
                ? pending.verificationUriComplete()
                : pending.verificationUri();
        if (uri == null || uri.isBlank() || pending.userCode() == null || pending.userCode().isBlank()) {
            return "";
        }
        return "Authorize mail at " + uri + " with code " + pending.userCode()
                + ", then press e in the mail panel.";
    }

    private MailMessageDetail loadThreadMessage(long threadId) {
        MailMessageDetail detail;
        synchronized (_readLock) {
            try {
                detail = MailDb.loadMessage(_readConnection, threadId);
            } catch (SQLException e) {
                LOG.error("Failed to load mail message for thread {}", threadId, e);
                return new MailMessageDetail(0L, threadId, "(error)", "", "", "", "", e.getMessage(), java.util.List.of());
            }
        }
        return hydrateMessageBodyIfNeeded(detail, () -> {
            synchronized (_readLock) {
                return MailDb.loadMessageHydrationTarget(_readConnection, threadId);
            }
        }, () -> {
            synchronized (_readLock) {
                return MailDb.loadMessage(_readConnection, threadId);
            }
        }, "thread " + threadId);
    }

    private MailMessageDetail loadSpecificMessage(long messageId) {
        MailMessageDetail detail;
        synchronized (_readLock) {
            try {
                detail = MailDb.loadMessageById(_readConnection, messageId);
            } catch (SQLException e) {
                LOG.error("Failed to load mail message {}", messageId, e);
                return new MailMessageDetail(0L, 0L, "(error)", "", "", "", "", e.getMessage(), java.util.List.of());
            }
        }
        return hydrateMessageBodyIfNeeded(detail, () -> {
            synchronized (_readLock) {
                return MailDb.loadMessageHydrationTargetByMessageId(_readConnection, messageId);
            }
        }, () -> {
            synchronized (_readLock) {
                return MailDb.loadMessageById(_readConnection, messageId);
            }
        }, "message " + messageId);
    }

    private MailMessageDetail hydrateMessageBodyIfNeeded(
            MailMessageDetail detail,
            ThrowingSupplier<MailDb.MessageHydrationTarget> targetSupplier,
            ThrowingSupplier<MailMessageDetail> detailSupplier,
            String label) {
        if (_closed || detail.messageId() == 0L || (detail.bodyText() != null && !detail.bodyText().isBlank())) {
            return detail;
        }
        try {
            MailDb.MessageHydrationTarget target = targetSupplier.get();
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
            synchronized (_writeLock) {
                if (_closed) {
                    return detail;
                }
                MailDb.updateMessageBody(_writeConnection, target.messageId(), target.threadId(), bodyText);
            }
            synchronized (_readLock) {
                return detailSupplier.get();
            }
        } catch (Exception e) {
            LOG.warn("Failed to hydrate mail body for {}", label, e);
            return detail;
        }
    }

    private static void closeConnectionQuietly(Connection connection, Throwable originalFailure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
