package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.SwimHomeFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorMailIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinarySupportsSeededMailBrowseAndSearchWorkflow() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-email-0.0.1-SNAPSHOT.jar");

        Path file = tempDir.resolve("mail-browse.txt");
        Files.writeString(file, "mail fixture\n");
        SwimHomeFixture home = createSeededMailHome();

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("mail fixture", STARTUP_TIMEOUT);

            session.sendLiteral("e");
            session.waitForText("Work [IMAP]", UI_TIMEOUT);
            session.waitForText("Boss update", UI_TIMEOUT);
            session.waitForText("Needle detail body", UI_TIMEOUT);
            session.sendLiteral("j");
            session.waitForText("Team notes", UI_TIMEOUT);
            session.waitForText("Plain body", UI_TIMEOUT);
            session.sendLiteral("k");
            session.waitForText("Boss update", UI_TIMEOUT);

            session.sendLiteral("/");
            session.sendLiteral("needle");
            session.sendEnter();
            session.waitForText("Filter: needle", UI_TIMEOUT);
            session.waitForText("Boss update", UI_TIMEOUT);

            session.sendLiteral("q");
            session.waitForText("mail fixture", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinarySupportsSeededMailSearchCancelWorkflow() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-email-0.0.1-SNAPSHOT.jar");

        Path file = tempDir.resolve("mail-search-cancel.txt");
        Files.writeString(file, "mail cancel fixture\n");
        SwimHomeFixture home = createSeededMailHome();

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("mail cancel fixture", STARTUP_TIMEOUT);

            session.sendLiteral("e");
            session.waitForText("Boss update", UI_TIMEOUT);
            session.sendLiteral("/");
            session.waitForText("Search:", UI_TIMEOUT);
            session.sendLiteral("team");
            session.sendEscape();
            session.waitForText("Boss update", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("mail cancel fixture", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinarySupportsSeededMailComposeOpenAndCancelWorkflow() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-email-0.0.1-SNAPSHOT.jar");

        Path file = tempDir.resolve("mail-compose.txt");
        Files.writeString(file, "mail compose fixture\n");
        SwimHomeFixture home = createSeededMailHome();

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("mail compose fixture", STARTUP_TIMEOUT);

            session.sendLiteral("e");
            session.waitForText("Boss update", UI_TIMEOUT);
            session.sendLiteral("c");
            session.waitForText("Compose", UI_TIMEOUT);
            session.waitForText("boss@example.com", UI_TIMEOUT);
            session.sendEscape();
            session.waitForText("Boss update", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("mail compose fixture", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    private SwimHomeFixture createSeededMailHome() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/sqlite3")), "sqlite3 is required for seeded mail tmux tests");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        home.writeEmailAccounts("""
                {
                  "accounts": [
                    {
                      "id": "work",
                      "name": "Work",
                      "protocol": "IMAP",
                      "host": "mail.example.com",
                      "port": 993,
                      "username": "me@example.com",
                      "passwordEnv": "SWIM_TEST_PASSWORD",
                      "smtpHost": "smtp.example.com",
                      "smtpPort": 587
                    }
                  ]
                }
                """);
        home.writeEmailTagRules("""
                {
                  "rules": []
                }
                """);
        home.writeEmailOAuthTokens("""
                {
                  "accounts": {}
                }
                """);
        home.runSqlite("""
                create table if not exists accounts (
                    id text primary key,
                    name text not null,
                    protocol text not null,
                    host text,
                    port integer,
                    username text,
                    password_env text,
                    folder text,
                    ews_url text,
                    domain text,
                    auth_type text,
                    updated_at text not null
                );
                create table if not exists threads (
                    id integer primary key autoincrement,
                    account_id text not null,
                    folder_name text,
                    subject text,
                    participants text,
                    snippet text,
                    last_message_at text,
                    unread_count integer not null default 0,
                    message_count integer not null default 0
                );
                create table if not exists messages (
                    id integer primary key autoincrement,
                    account_id text not null,
                    thread_id integer,
                    internet_message_id text,
                    folder_name text,
                    subject text,
                    from_name text,
                    from_email text,
                    to_summary text,
                    sent_at text,
                    received_at text,
                    snippet text,
                    body_text text,
                    is_read integer not null default 0
                );
                create table if not exists tags (
                    name text primary key,
                    color text
                );
                create table if not exists tag_rules (
                    id integer primary key autoincrement,
                    tag_name text not null,
                    field_name text not null,
                    contains_value text not null
                );
                create table if not exists message_tags (
                    message_id integer not null,
                    tag_name text not null,
                    primary key (message_id, tag_name)
                );
                create table if not exists account_sync_state (
                    account_id text primary key,
                    last_sync_at text,
                    status_message text not null default ''
                );
                insert into accounts (id, name, protocol, host, port, username, password_env, updated_at)
                values ('work', 'Work', 'IMAP', 'mail.example.com', 993, 'me@example.com', 'SWIM_TEST_PASSWORD', '2026-05-29T08:00:00Z');
                insert into account_sync_state (account_id, last_sync_at, status_message)
                values ('work', '2026-05-29T08:00:00Z', 'seeded');
                insert into threads (id, account_id, folder_name, subject, participants, snippet, last_message_at, unread_count, message_count)
                values
                  (1, 'work', 'INBOX', 'Boss update', 'Boss <boss@example.com>', 'Needle summary snippet', '2026-05-29T09:00:00Z', 1, 1),
                  (2, 'work', 'INBOX', 'Team notes', 'Teammate <mate@example.com>', 'General team update', '2026-05-28T09:00:00Z', 0, 1);
                insert into messages (id, account_id, thread_id, internet_message_id, folder_name, subject, from_name, from_email, to_summary, sent_at, received_at, snippet, body_text, is_read)
                values
                  (11, 'work', 1, '<m1@example.com>', 'INBOX', 'Boss update', 'Boss', 'boss@example.com', 'me@example.com', '2026-05-29T09:00:00Z', '2026-05-29T09:00:05Z', 'Needle summary snippet', 'Needle detail body', 0),
                  (12, 'work', 2, '<m2@example.com>', 'INBOX', 'Team notes', 'Teammate', 'mate@example.com', 'me@example.com', '2026-05-28T09:00:00Z', '2026-05-28T09:00:05Z', 'General team update', 'Plain body', 1);
                """);
        return home;
    }
}
