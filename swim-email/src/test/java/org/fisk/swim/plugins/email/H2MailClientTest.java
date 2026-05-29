package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2MailClientTest {
    @TempDir
    Path tempDir;

    @Test
    void creatingClientBootstrapsEmailHome() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            try (var client = new H2MailClient(paths)) {
                assertTrue(Files.exists(paths.emailHome()));
                assertTrue(Files.exists(paths.accountsPath()));
                assertTrue(Files.exists(paths.tagRulesPath()));
                assertTrue(Files.exists(paths.databaseFilePath()));
                assertTrue(Files.exists(paths.oauthTokensPath()));
                assertTrue(client.snapshot().statusMessage().contains(paths.accountsPath().toString()));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void creatingClientToleratesBlankAuxiliaryJsonFiles() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "oracle",
                          "name": "Oracle Mail",
                          "protocol": "IMAP",
                          "host": "outlook.office365.com",
                          "port": 993,
                          "username": "erik.osterlund@oracle.com",
                          "folder": "INBOX",
                          "authType": "OAUTH2",
                          "tenant": "organizations",
                          "clientId": "client-id"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), "");
            Files.writeString(paths.oauthTokensPath(), "");

            try (var client = new H2MailClient(paths,
                    account -> ignored -> MailSyncBatch.success(List.of(), "0 messages"))) {
                var snapshot = client.snapshot();
                assertEquals(1, snapshot.accounts().size());
                assertEquals("oracle", snapshot.accounts().get(0).id());
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void creatingClientBootstrapsConfiguredAccountsWithoutRefresh() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            try (var client = new H2MailClient(paths,
                    account -> ignored -> MailSyncBatch.success(List.of(), "0 messages"),
                    (account, draft) -> org.fisk.swim.mail.MailSendResult.success("sent"),
                    false)) {
                var snapshot = client.snapshot();
                assertEquals(1, snapshot.accounts().size());
                assertEquals("work", snapshot.accounts().get(0).id());
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void refreshReappliesTagRulesToExistingMessages() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    {
                      "rules": [
                        {
                          "tag": "vip",
                          "field": "sender",
                          "contains": "boss@example.com"
                        }
                      ]
                    }
                    """);

            try (var client = new H2MailClient(paths);
                    var connection = DriverManager.getConnection(paths.databaseJdbcUrl());
                    var threadInsert = connection.prepareStatement("""
                            insert into threads (id, account_id, folder_name, subject, participants, snippet, last_message_at, unread_count, message_count)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    var messageInsert = connection.prepareStatement("""
                            insert into messages (id, account_id, thread_id, subject, from_email, to_summary, sent_at, received_at, snippet, body_text, is_read)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                threadInsert.setLong(1, 7L);
                threadInsert.setString(2, "work");
                threadInsert.setString(3, "INBOX");
                threadInsert.setString(4, "Quarterly review");
                threadInsert.setString(5, "boss@example.com");
                threadInsert.setString(6, "Please review");
                threadInsert.setString(7, "2026-05-13T08:30:00Z");
                threadInsert.setInt(8, 1);
                threadInsert.setInt(9, 1);
                threadInsert.executeUpdate();

                messageInsert.setLong(1, 11L);
                messageInsert.setString(2, "work");
                messageInsert.setLong(3, 7L);
                messageInsert.setString(4, "Quarterly review");
                messageInsert.setString(5, "boss@example.com");
                messageInsert.setString(6, "me@example.com");
                messageInsert.setString(7, "2026-05-13T08:30:00Z");
                messageInsert.setString(8, "2026-05-13T08:30:05Z");
                messageInsert.setString(9, "Please review");
                messageInsert.setString(10, "The quarterly review is attached.");
                messageInsert.setInt(11, 0);
                messageInsert.executeUpdate();

                client.refresh();

                var snapshot = client.snapshot();
                assertEquals(1, snapshot.accounts().size());
                assertEquals(1, snapshot.threads().size());
                assertTrue(snapshot.threads().get(0).tags().contains("vip"));
                assertTrue(client.loadMessage(7L).tags().contains("vip"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void refreshImportsMessagesFromSyncAdapterIntoThreadedSnapshot() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    {
                      "rules": [
                        {
                          "tag": "vip",
                          "field": "sender",
                          "contains": "boss@example.com"
                        }
                      ]
                    }
                    """);

            MailSyncAdapterFactory factory = account -> ignored -> MailSyncBatch.success(List.of(
                    new ImportedMailMessage(
                            "work", "INBOX", "<m1@example.com>", "thread:quarterly-review",
                            "Quarterly review", "Boss", "boss@example.com", "me@example.com",
                            "2026-05-13T08:30:00Z", "2026-05-13T08:31:00Z",
                            "Please review", "Please review the attached document.", true),
                    new ImportedMailMessage(
                            "work", "INBOX", "<m2@example.com>", "thread:quarterly-review",
                            "Re: Quarterly review", "Me", "me@example.com", "boss@example.com",
                            "2026-05-13T09:00:00Z", "2026-05-13T09:00:05Z",
                            "Looks good", "Looks good to me.", false)), "2 messages");

            try (var client = new H2MailClient(paths, factory)) {
                var snapshot = client.snapshot();

                assertEquals(1, snapshot.accounts().size());
                assertEquals("2 messages", snapshot.accounts().get(0).syncStatus());
                assertEquals(1, snapshot.threads().size());
                assertEquals(2, snapshot.threads().get(0).messageCount());
                assertTrue(snapshot.threads().get(0).tags().contains("vip"));
                assertTrue(client.loadMessage(snapshot.threads().get(0).threadId()).bodyText().contains("Looks good"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void loadMessageHydratesAndCachesBodyOnDemand() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD",
                          "folder": "INBOX"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            AtomicInteger bodyLoads = new AtomicInteger();
            MailSyncAdapterFactory factory = account -> new MailSyncAdapter() {
                @Override
                public MailSyncBatch fetch(EmailAccountConfig ignored) {
                    return MailSyncBatch.success(List.of(), "0 messages");
                }

                @Override
                public String loadBody(EmailAccountConfig ignored, String folderName, String internetMessageId) {
                    bodyLoads.incrementAndGet();
                    return "Hydrated body for " + internetMessageId + " in " + folderName;
                }
            };

            try (var client = new H2MailClient(paths, factory,
                    (account, draft) -> org.fisk.swim.mail.MailSendResult.success("sent"),
                    false);
                    var connection = DriverManager.getConnection(paths.databaseJdbcUrl());
                    var threadInsert = connection.prepareStatement("""
                            insert into threads (id, account_id, folder_name, subject, participants, snippet, last_message_at, unread_count, message_count)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    var messageInsert = connection.prepareStatement("""
                            insert into messages (id, account_id, thread_id, internet_message_id, folder_name, subject, from_email, to_summary, sent_at, received_at, snippet, body_text, is_read)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                threadInsert.setLong(1, 7L);
                threadInsert.setString(2, "work");
                threadInsert.setString(3, "INBOX");
                threadInsert.setString(4, "Quarterly review");
                threadInsert.setString(5, "boss@example.com");
                threadInsert.setString(6, "Quarterly review");
                threadInsert.setString(7, "2026-05-13T08:30:00Z");
                threadInsert.setInt(8, 1);
                threadInsert.setInt(9, 1);
                threadInsert.executeUpdate();

                messageInsert.setLong(1, 11L);
                messageInsert.setString(2, "work");
                messageInsert.setLong(3, 7L);
                messageInsert.setString(4, "<m1@example.com>");
                messageInsert.setString(5, "INBOX");
                messageInsert.setString(6, "Quarterly review");
                messageInsert.setString(7, "boss@example.com");
                messageInsert.setString(8, "me@example.com");
                messageInsert.setString(9, "2026-05-13T08:30:00Z");
                messageInsert.setString(10, "2026-05-13T08:30:05Z");
                messageInsert.setString(11, "Quarterly review");
                messageInsert.setString(12, "");
                messageInsert.setInt(13, 0);
                messageInsert.executeUpdate();

                var firstLoad = client.loadMessage(7L);
                var secondLoad = client.loadMessage(7L);

                assertTrue(firstLoad.bodyText().contains("Hydrated body for <m1@example.com>"));
                assertTrue(secondLoad.bodyText().contains("Hydrated body for <m1@example.com>"));
                assertEquals(1, bodyLoads.get());
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void closeDoesNotWaitForRefreshStuckInRemoteFetch() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            CountDownLatch fetchStarted = new CountDownLatch(1);
            CountDownLatch allowFetchToFinish = new CountDownLatch(1);
            AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
            MailSyncAdapterFactory factory = account -> ignored -> {
                fetchStarted.countDown();
                allowFetchToFinish.await(2, TimeUnit.SECONDS);
                return MailSyncBatch.success(List.of(), "0 messages");
            };

            var client = new H2MailClient(paths, factory,
                    (account, draft) -> org.fisk.swim.mail.MailSendResult.success("sent"),
                    false);
            try {
                Thread refreshThread = new Thread(() -> {
                    try {
                        client.refresh();
                    } catch (Throwable t) {
                        refreshFailure.set(t);
                    }
                }, "test-mail-refresh");
                refreshThread.start();

                assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));

                long startedAt = System.nanoTime();
                client.close();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                allowFetchToFinish.countDown();
                refreshThread.join(2000);

                assertTrue(elapsedMs < 500, "close should not block on remote fetch");
                assertTrue(refreshFailure.get() == null, () -> "refresh failed: " + refreshFailure.get());
            } finally {
                allowFetchToFinish.countDown();
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void exchangeAccountSurfacesPendingEwsStatus() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "exchange",
                          "name": "Exchange",
                          "protocol": "EXCHANGE",
                          "ewsUrl": "https://mail.example.com/EWS/Exchange.asmx",
                          "username": "DOMAIN\\\\user",
                          "passwordEnv": "SWIM_EXCHANGE_PASSWORD",
                          "authType": "NTLM"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            try (var client = new H2MailClient(paths)) {
                var snapshot = client.snapshot();

                assertEquals(1, snapshot.accounts().size());
                assertTrue(snapshot.accounts().get(0).syncStatus().contains("BASIC/PASSWORD auth only"));
                assertTrue(snapshot.statusMessage().contains("BASIC/PASSWORD auth only"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void sendDraftUsesDeliveryServiceAndAppendsSentMessage() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "outlook.office365.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "MAIL_PASSWORD",
                          "smtpHost": "smtp.office365.com",
                          "smtpPort": 587
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            try (var client = new H2MailClient(paths,
                    account -> ignored -> MailSyncBatch.success(List.of(), "0 messages"),
                    (account, draft) -> org.fisk.swim.mail.MailSendResult.success("sent"))) {
                var result = client.sendDraft(new MailDraft("work", "boss@example.com", "Status", "Looks good"));

                assertTrue(result.success());
                var snapshot = client.snapshot();
                assertEquals(1, snapshot.threads().size());
                assertEquals("Status", snapshot.threads().get(0).subject());
                assertTrue(client.loadMessage(snapshot.threads().get(0).threadId()).bodyText().contains("Looks good"));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void loadThreadsPaginatesAndSearchesDatabaseContent() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.accountsPath(), """
                    {
                      "accounts": [
                        {
                          "id": "work",
                          "name": "Work",
                          "protocol": "IMAP",
                          "host": "mail.example.com",
                          "port": 993,
                          "username": "me@example.com",
                          "passwordEnv": "SWIM_MAIL_PASSWORD"
                        }
                      ]
                    }
                    """);
            Files.writeString(paths.tagRulesPath(), """
                    { "rules": [] }
                    """);

            try (var client = new H2MailClient(paths,
                    account -> ignored -> MailSyncBatch.success(List.of(), "0 messages"));
                    var connection = DriverManager.getConnection(paths.databaseJdbcUrl());
                    var threadInsert = connection.prepareStatement("""
                            insert into threads (id, account_id, folder_name, subject, participants, snippet, last_message_at, unread_count, message_count)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    var messageInsert = connection.prepareStatement("""
                            insert into messages (id, account_id, thread_id, subject, from_name, from_email, to_summary, sent_at, received_at, snippet, body_text, is_read)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                OffsetDateTime start = OffsetDateTime.of(2026, 5, 13, 8, 0, 0, 0, ZoneOffset.UTC);
                for (int i = 1; i <= 120; i++) {
                    String timestamp = start.plusMinutes(i).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    threadInsert.setLong(1, i);
                    threadInsert.setString(2, "work");
                    threadInsert.setString(3, "INBOX");
                    threadInsert.setString(4, "Thread " + i);
                    threadInsert.setString(5, "Boss");
                    threadInsert.setString(6, i == 65 ? "Generic snippet" : "Snippet " + i);
                    threadInsert.setString(7, timestamp);
                    threadInsert.setInt(8, i % 5 == 0 ? 1 : 0);
                    threadInsert.setInt(9, 1);
                    threadInsert.addBatch();

                    messageInsert.setLong(1, i);
                    messageInsert.setString(2, "work");
                    messageInsert.setLong(3, i);
                    messageInsert.setString(4, "Thread " + i);
                    messageInsert.setString(5, "Boss");
                    messageInsert.setString(6, "boss@example.com");
                    messageInsert.setString(7, "me@example.com");
                    messageInsert.setString(8, timestamp);
                    messageInsert.setString(9, timestamp);
                    messageInsert.setString(10, "Snippet " + i);
                    messageInsert.setString(11, i == 65 ? "Oracle migration plan and rollout details." : "Body " + i);
                    messageInsert.setInt(12, 1);
                    messageInsert.addBatch();
                }
                threadInsert.executeBatch();
                messageInsert.executeBatch();

                var firstPage = client.loadThreads("", 0, 50);
                var secondPage = client.loadThreads("", 50, 50);
                var finalPage = client.loadThreads("", 100, 50);
                var searchPage = client.loadThreads("migration", 0, 10);

                assertEquals(120, firstPage.totalCount());
                assertEquals(50, firstPage.threads().size());
                assertEquals(50, secondPage.threads().size());
                assertEquals(20, finalPage.threads().size());
                assertEquals(120L, firstPage.threads().get(0).threadId());
                assertEquals(70L, secondPage.threads().get(0).threadId());
                assertEquals(20L, finalPage.threads().get(0).threadId());
                assertEquals(1, searchPage.totalCount());
                assertEquals(65L, searchPage.threads().get(0).threadId());
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }
}
