package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.mail.*;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailSyncCachingTest {
    @TempDir Path tempDir;

    @Test
    void unchangedExchangeItemsAreNotDownloadedAgainEvenAfterRestart() throws Exception {
        String property = "SWIM_FIXTURE_EWS_PASSWORD";
        String original = System.getProperty(property);
        System.setProperty(property, "fixture-password");
        try {
            var version = new AtomicInteger(1);
            var fetched = new ArrayList<String>();
            ExchangeEwsMailSyncAdapter.EwsTransport transport = (account, request) -> {
                if (request.contains("<m:FindItem")) {
                    return """
                            <RootFolder IncludesLastItemInRange="true"><Items>
                            <Message><ItemId Id="fixture-1" ChangeKey="v%d" />
                            <InternetMessageId>&lt;fixture-1@fixture.invalid&gt;</InternetMessageId>
                            <LastModifiedTime>%s</LastModifiedTime></Message>
                            <Message><ItemId Id="fixture-2" ChangeKey="v1" />
                            <InternetMessageId>&lt;fixture-2@fixture.invalid&gt;</InternetMessageId>
                            <LastModifiedTime>2026-01-01T00:00:00Z</LastModifiedTime></Message>
                            </Items></RootFolder>
                            """.formatted(version.get(), version.get() == 1 ? "2026-01-01T00:00:00Z" : "2999-01-01T00:00:00Z");
                }
                fetched.add(request);
                StringBuilder items = new StringBuilder("<Items>");
                for (int id = 1; id <= 2; id++) {
                    if (request.contains("Id=\"fixture-" + id + "\"")) {
                        items.append("""
                                <Message><ItemId Id="fixture-%d" ChangeKey="v%d" />
                                <InternetMessageId>&lt;fixture-%d@fixture.invalid&gt;</InternetMessageId>
                                <Subject>Fixture %d</Subject><Body>Fixture body</Body><IsRead>true</IsRead></Message>
                                """.formatted(id, id == 1 ? version.get() : 1, id, id));
                    }
                }
                return items.append("</Items>").toString();
            };
            Path home = tempDir.resolve("mail");
            var paths = new EmailPaths(tempDir, home, home.resolve("accounts.json"), home.resolve("rules.json"),
                    home.resolve("cache.mv.db"), home.resolve("oauth.json"));
            EmailConfigStore.ensureDefaultFiles(paths);
            var account = new EmailAccountConfig("work", "Work", "EXCHANGE", null, null, null, null, null,
                    "fixture-user", property, "INBOX", "https://fixture.invalid/EWS", null, "BASIC", null, null, List.of());
            Files.writeString(paths.accountsPath(), new Gson().toJson(new EmailAccountsConfig(List.of(account))));
            Files.writeString(paths.tagRulesPath(), "{\"rules\":[]}");
            MailSyncAdapterFactory factory = ignored -> new ExchangeEwsMailSyncAdapter(transport);
            try (var client = new H2MailClient(paths, factory, (a, draft) -> MailSendResult.failure("fixture"), false)) {
                client.refresh();
                client.refresh();
                assertEquals(1, fetched.size());
                assertEquals(2, client.snapshot().threads().size());
                // An existing installation has bodies and Message-IDs but no
                // provider/version keys yet. Reconcile identities using metadata.
                try (var connection = java.sql.DriverManager.getConnection(paths.databaseJdbcUrl());
                        var statement = connection.createStatement()) {
                    statement.executeUpdate("delete from captured_mail_keys");
                }
                client.refresh();
                assertEquals(1, fetched.size(), "Reconciliation must reuse already stored bodies");
                try (var connection = java.sql.DriverManager.getConnection(paths.databaseJdbcUrl());
                        var statement = connection.createStatement();
                        var rows = statement.executeQuery("select count(*) from captured_mail_keys")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
            }
            try (var client = new H2MailClient(paths, factory, (a, draft) -> MailSendResult.failure("fixture"), false)) {
                client.refresh();
                assertEquals(1, fetched.size());
                version.set(2);
                client.refresh();
                assertEquals(2, fetched.size());
                assertTrue(fetched.getLast().contains("Id=\"fixture-1\""));
                assertFalse(fetched.getLast().contains("Id=\"fixture-2\""));
                client.refresh();
                assertEquals(2, fetched.size(), "The new version must also remain cached");
            }
        } finally {
            if (original == null) { System.clearProperty(property); }
            else { System.setProperty(property, original); }
        }
    }

    @Test
    void pop3ChecksStableIdentifiersBeforeRequestingHeaders() throws Exception {
        Session session = Session.getInstance(new java.util.Properties());
        Message cached = new MimeMessage(session);
        Message fresh = new MimeMessage(session);
        var identities = new AtomicInteger();
        var folder = new org.eclipse.angus.mail.pop3.POP3Folder(
                new org.eclipse.angus.mail.pop3.POP3Store(session, null), "INBOX") {
            @Override public void fetch(Message[] messages, FetchProfile profile) {
                assertTrue(profile.contains(UIDFolder.FetchProfileItem.UID));
                assertFalse(profile.contains(FetchProfile.Item.ENVELOPE));
                identities.incrementAndGet();
            }
            @Override public String getUID(Message message) { return message == cached ? "cached" : "fresh"; }
        };
        var keys = new java.util.IdentityHashMap<Message, String>();
        Message[] result = JakartaMailSupport.uncachedPop3Messages(folder, "INBOX", new Message[] {cached, fresh},
                requested -> Set.of("pop3|INBOX|cached"), keys);
        assertArrayEquals(new Message[] {fresh}, result);
        assertEquals(1, identities.get());
        assertEquals("pop3|INBOX|fresh", keys.get(fresh));
    }

    @Test
    void imapRejectsPreviouslyCapturedUidEvenWhenServerRangeReturnsIt() throws Exception {
        Session session = Session.getInstance(new java.util.Properties());
        Message cached = new MimeMessage(session);
        Message fresh = new MimeMessage(session);
        UIDFolder folder = (UIDFolder) java.lang.reflect.Proxy.newProxyInstance(UIDFolder.class.getClassLoader(),
                new Class<?>[] {UIDFolder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) { return args[0] == cached ? 100L : 101L; }
                    throw new UnsupportedOperationException();
                });
        assertArrayEquals(new Message[] {fresh},
                JakartaMailSupport.excludeCapturedUids(folder, new Message[] {cached, fresh}, 100));
        assertEquals(0, JakartaMailSupport.excludeCapturedUids(folder, new Message[] {cached}, 100).length);
    }

    @Test
    void databaseStartupRunsInBackgroundAndLateResultIsClosedOnUnload() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        MailClient fixture = new MailClient() {
            @Override public MailSnapshot snapshot() { return new MailSnapshot(List.of(), List.of(), ""); }
            @Override public MailMessageDetail loadMessage(long id) { return null; }
            @Override public void refresh() { }
            @Override public Path getDataPath() { return tempDir; }
            @Override public void close() { closed.countDown(); }
        };
        var client = assertTimeoutPreemptively(java.time.Duration.ofMillis(300),
                () -> new BackgroundMailClient(tempDir, () -> {
                    entered.countDown(); release.await(3, TimeUnit.SECONDS); return fixture;
                }, SwimPluginWorkers.unmanaged()));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(tempDir, client.getDataPath());
            client.close();
            release.countDown();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            client.close();
        }
    }
}
