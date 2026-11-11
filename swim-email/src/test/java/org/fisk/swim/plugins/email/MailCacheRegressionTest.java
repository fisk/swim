package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailThreadFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailCacheRegressionTest {
    @TempDir Path tempDir;

    @Test
    void scopedAllAndUnsortedKeepAccountsIsolatedIncludingTokenTags() throws Exception {
        try (var connection = database()) {
            MailDb.syncTagRules(connection, new EmailTagRulesConfig(List.of(
                    new EmailTagRuleConfig("sorted", "sender", "tagged@fixture.invalid", "exact"),
                    new EmailTagRuleConfig("token", "subject", "fixturetoken", "token"))));
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(
                    message("work", 1, true, false, false), message("work", 2, false, false, false),
                    message("work", 3, true, true, false), message("work", 4, false, false, true)));
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "personal", List.of(message("personal", 5, false, false, false)));

            var work = MailDb.loadThreadPage(connection, "", 0, 100, MailThreadFilter.account("work"));
            var unsorted = MailDb.loadThreadPage(connection, "", 0, 100, MailThreadFilter.accountUnsorted("work"));
            assertEquals(4, work.totalCount());
            assertEquals(2, unsorted.totalCount());
            assertTrue(unsorted.threads().stream().allMatch(thread -> thread.accountId().equals("work")));
            assertEquals(1, MailDb.loadThreadPage(connection, "", 0, 100, MailThreadFilter.accountUnsorted("personal")).totalCount());
            assertEquals(Map.of("work", 2, "personal", 1), MailDb.loadAccountUnsortedUnreadCounts(connection));
            assertEquals(3, MailDb.loadUnsortedUnreadCount(connection));
        }
    }

    @Test
    void orderedAccountPagesUseTheCompositeIndex() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into threads(account_id, subject, last_message_at)
                    select case when mod(x, 2) = 0 then 'work' else 'personal' end,
                        'Fixture', lpad(cast(x as varchar), 20, '0')
                    from system_range(1, 10000)
                    """);
            try (var explain = connection.prepareStatement("""
                    explain analyze select t.id from threads t
                    where exists (select 1 from accounts a where a.id = t.account_id)
                        and t.account_id = ? order by t.account_id, t.last_message_at desc, t.id desc limit 100
                    """)) {
                explain.setString(1, "work");
                try (var rows = explain.executeQuery()) {
                    assertTrue(rows.next());
                    String plan = rows.getString(1);
                    assertTrue(plan.contains("IDX_THREADS_ACCOUNT_ORDER"), plan);
                    assertTrue(plan.toLowerCase().contains("index sorted"), plan);
                    assertTrue(plan.contains("scanCount: 100 */"), plan);
                }
            }
            var page = MailDb.loadThreadPage(connection, "", 0, 100, MailThreadFilter.account("work"));
            assertEquals(5000, page.totalCount());
            assertEquals(100, page.threads().size());
        }
    }

    @Test
    void statusCountsDoNotBlockMailboxPages() throws Exception {
        EmailPaths paths = configuredPaths();
        MailSyncAdapterFactory factory = ignored -> account -> MailSyncBatch.success(List.of(), "");
        try (var client = client(paths, factory); var connection = DriverManager.getConnection(paths.databaseJdbcUrl())) {
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(message("work", 1, false, false, false)));
            var field = H2MailClient.class.getDeclaredField("_countsLock");
            field.setAccessible(true);
            var status = Thread.ofPlatform().unstarted(client::snapshot);
            synchronized (field.get(client)) {
                status.start();
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    while (status.isAlive() && status.getState() != Thread.State.BLOCKED) {
                        Thread.sleep(1);
                    }
                    assertEquals(Thread.State.BLOCKED, status.getState());
                });
                assertTimeoutPreemptively(java.time.Duration.ofMillis(300),
                        () -> assertEquals(1, client.loadThreads("", 0, 100, MailThreadFilter.account("work")).threads().size()));
            }
            status.join(2000);
            assertFalse(status.isAlive());
        }
    }

    @Test
    void captureKeysCommitWithMessagesAndSurviveReopening() throws Exception {
        Path file = tempDir.resolve("keys");
        String url = "jdbc:h2:file:" + file;
        try (var connection = DriverManager.getConnection(url)) {
            MailDb.initialize(connection);
            var message = message("work", 1, false, false, false).withSyncKey("provider-fixture-key");
            connection.setAutoCommit(false);
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(message));
            connection.rollback();
            assertTrue(MailDb.capturedKeys(connection, "work", List.of("provider-fixture-key")).isEmpty());
            connection.setAutoCommit(true);
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(message));
        }
        try (var connection = DriverManager.getConnection(url)) {
            assertEquals(Set.of("provider-fixture-key"), MailDb.capturedKeys(connection, "work", List.of("provider-fixture-key")));
            assertTrue(MailDb.capturedKeys(connection, "personal", List.of("provider-fixture-key")).isEmpty());
        }
    }

    @Test
    void emptyBodyIsFetchedOnceAndRemainsCachedAfterRestart() throws Exception {
        EmailPaths paths = configuredPaths();
        var loads = new AtomicInteger();
        MailSyncAdapterFactory factory = ignored -> new MailSyncAdapter() {
            @Override public MailSyncBatch fetch(EmailAccountConfig account) { return MailSyncBatch.success(List.of(), ""); }
            @Override public String loadBody(EmailAccountConfig account, String folder, String id) { loads.incrementAndGet(); return ""; }
        };
        long threadId;
        try (var client = client(paths, factory); var connection = DriverManager.getConnection(paths.databaseJdbcUrl())) {
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(message("work", 1, false, false, false)));
            threadId = client.loadThreads("", 0, 1).threads().getFirst().threadId();
            client.loadMessage(threadId);
            client.loadMessage(threadId);
            assertEquals(1, loads.get());
        }
        try (var client = client(paths, factory)) {
            client.loadMessage(threadId);
            assertEquals(1, loads.get());
        }
    }

    @Test
    void concurrentViewsShareOneBodyFetch() throws Exception {
        EmailPaths paths = configuredPaths();
        var loads = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        MailSyncAdapterFactory factory = ignored -> new MailSyncAdapter() {
            @Override public MailSyncBatch fetch(EmailAccountConfig account) { return MailSyncBatch.success(List.of(), ""); }
            @Override public String loadBody(EmailAccountConfig account, String folder, String id) throws Exception {
                loads.incrementAndGet(); entered.countDown(); release.await(3, TimeUnit.SECONDS); return "Fixture body";
            }
        };
        try (var client = client(paths, factory); var connection = DriverManager.getConnection(paths.databaseJdbcUrl());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MailDb.upsertAccountMessagesAndRefreshThreads(connection, "work", List.of(message("work", 1, false, false, false)));
            long thread = client.loadThreads("", 0, 1).threads().getFirst().threadId();
            var first = executor.submit(() -> client.loadMessage(thread));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> client.loadMessage(thread));
            release.countDown();
            assertEquals("Fixture body", first.get(3, TimeUnit.SECONDS).bodyText());
            assertEquals("Fixture body", second.get(3, TimeUnit.SECONDS).bodyText());
            assertEquals(1, loads.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void changedImapUidValidityReplacesTheOldHighWatermark() throws Exception {
        try (var connection = database()) {
            MailDb.recordAccountSyncState(connection, "work", "cached", "", true, 1000, 0);
            MailDb.recordUidValidity(connection, "work", 11);
            var state = MailDb.loadAccountSyncStates(connection).get("work");
            var batch = MailSyncBatch.success(List.of(), "", 10, 0, 0, 10, 0).withUidValidity(22);
            MailSyncAdapter adapter = account -> batch;
            var plan = new MailSyncEngine.RefreshPlan(new EmailAccountsConfig(List.of(account("work"))),
                    EmailTagRulesConfig.empty(), List.of(new MailSyncEngine.AccountSyncResult(account("work"), state, adapter, batch)));
            new MailSyncEngine(ignored -> adapter).apply(connection, plan);
            var saved = MailDb.loadAccountSyncStates(connection).get("work");
            assertEquals(22, saved.uidValidity());
            assertEquals(10, saved.lastSeenUid());
        }
    }

    private Connection database() throws Exception {
        var connection = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID());
        MailDb.initialize(connection);
        MailDb.syncAccounts(connection, new EmailAccountsConfig(List.of(account("work"), account("personal"))));
        return connection;
    }

    static EmailAccountConfig account(String id) {
        return new EmailAccountConfig(id, id, "IMAP", "fixture.invalid", 993, null, null, null,
                "owner-" + id + "@fixture.invalid", "FIXTURE_PASSWORD", "INBOX", null, null, "PASSWORD", null, null, List.of());
    }

    private static ImportedMailMessage message(String account, int id, boolean tagged, boolean direct, boolean token) {
        String messageId = "<fixture-" + id + "@fixture.invalid>";
        return new ImportedMailMessage(account, "INBOX", messageId, "message-id:" + messageId,
                "Fixture " + id + (token ? " fixturetoken" : ""), "Fixture", tagged ? "tagged@fixture.invalid" : "other@fixture.invalid",
                "", direct ? List.of(new ImportedMailRecipient("TO", "", "owner-" + account + "@fixture.invalid")) : List.of(),
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "", "", true);
    }

    private EmailPaths configuredPaths() throws Exception {
        Path home = tempDir.resolve("mail");
        var paths = new EmailPaths(tempDir, home, home.resolve("accounts.json"), home.resolve("tag-rules.json"),
                home.resolve("cache.mv.db"), home.resolve("oauth.json"));
        EmailConfigStore.ensureDefaultFiles(paths);
        Files.writeString(paths.accountsPath(), new Gson().toJson(new EmailAccountsConfig(List.of(account("work")))));
        Files.writeString(paths.tagRulesPath(), "{\"rules\":[]}");
        return paths;
    }

    private H2MailClient client(EmailPaths paths, MailSyncAdapterFactory factory) throws Exception {
        return new H2MailClient(paths, factory, (account, draft) -> MailSendResult.success("fixture"), false);
    }
}
