package org.fisk.swim.plugins.email;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;

final class MailDb {
    private MailDb() {
    }

    static void initialize(Connection connection) throws SQLException {
        execute(connection, """
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
                )
                """);
        execute(connection, """
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
                )
                """);
        execute(connection, """
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
                )
                """);
        execute(connection, """
                create table if not exists tags (
                    name text primary key,
                    color text
                )
                """);
        execute(connection, """
                create table if not exists tag_rules (
                    id integer primary key autoincrement,
                    tag_name text not null,
                    field_name text not null,
                    contains_value text not null
                )
                """);
        execute(connection, """
                create table if not exists message_tags (
                    message_id integer not null,
                    tag_name text not null,
                    primary key (message_id, tag_name)
                )
                """);
        execute(connection, """
                create table if not exists account_sync_state (
                    account_id text primary key,
                    last_sync_at text,
                    status_message text not null default ''
                )
                """);
        execute(connection, "pragma user_version = 1");
    }

    static void syncAccounts(Connection connection, EmailAccountsConfig config) throws SQLException {
        execute(connection, "delete from accounts");
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into accounts (
                    id, name, protocol, host, port, username, password_env, folder, ews_url, domain, auth_type, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (EmailAccountConfig account : config.accounts()) {
                statement.setString(1, account.normalizedId());
                statement.setString(2, account.displayName());
                statement.setString(3, account.normalizedProtocol());
                statement.setString(4, account.host());
                if (account.port() == null) {
                    statement.setNull(5, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(5, account.port());
                }
                statement.setString(6, account.username());
                statement.setString(7, account.passwordEnv());
                statement.setString(8, account.folder());
                statement.setString(9, account.ewsUrl());
                statement.setString(10, account.domain());
                statement.setString(11, account.normalizedAuthType());
                statement.setString(12, java.time.Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static void syncTagRules(Connection connection, EmailTagRulesConfig config) throws SQLException {
        execute(connection, "delete from tag_rules");
        execute(connection, "delete from tags");
        try (PreparedStatement tagStatement = connection.prepareStatement(
                "insert into tags (name, color) values (?, ?)");
                PreparedStatement ruleStatement = connection.prepareStatement(
                        "insert into tag_rules (tag_name, field_name, contains_value) values (?, ?, ?)")) {
            for (EmailTagRuleConfig rule : config.rules()) {
                if (rule.tag() == null || rule.tag().isBlank() || rule.contains() == null || rule.contains().isBlank()) {
                    continue;
                }
                tagStatement.setString(1, rule.tag().trim());
                tagStatement.setString(2, null);
                tagStatement.addBatch();
                ruleStatement.setString(1, rule.tag().trim());
                ruleStatement.setString(2, rule.normalizedField());
                ruleStatement.setString(3, rule.contains().trim());
                ruleStatement.addBatch();
            }
            tagStatement.executeBatch();
            ruleStatement.executeBatch();
        }
    }

    static void reapplyTags(Connection connection) throws SQLException {
        execute(connection, "delete from message_tags");
        try (PreparedStatement rules = connection.prepareStatement(
                "select tag_name, field_name, contains_value from tag_rules order by id");
                ResultSet resultSet = rules.executeQuery()) {
            while (resultSet.next()) {
                String tag = resultSet.getString(1);
                String field = resultSet.getString(2);
                String contains = resultSet.getString(3).toLowerCase(java.util.Locale.ROOT);
                String sqlField = switch (field) {
                case "recipient" -> "coalesce(to_summary, '')";
                case "subject" -> "coalesce(subject, '')";
                case "body" -> "coalesce(body_text, '')";
                default -> "coalesce(from_email, '')";
                };
                try (PreparedStatement insert = connection.prepareStatement("""
                        insert or ignore into message_tags (message_id, tag_name)
                        select id, ? from messages where lower(""" + sqlField + ") like ?")) {
                    insert.setString(1, tag);
                    insert.setString(2, "%" + contains + "%");
                    insert.executeUpdate();
                }
            }
        }
    }

    static void replaceAccountMessages(Connection connection, String accountId, List<ImportedMailMessage> messages)
            throws SQLException {
        deleteAccountMessages(connection, accountId);
        if (messages.isEmpty()) {
            return;
        }

        Map<String, List<ImportedMailMessage>> grouped = new LinkedHashMap<>();
        List<ImportedMailMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing(ImportedMailMessage::effectiveTimestamp).reversed());
        for (ImportedMailMessage message : sorted) {
            grouped.computeIfAbsent(message.threadKey(), ignored -> new ArrayList<>()).add(message);
        }

        for (List<ImportedMailMessage> group : grouped.values()) {
            ImportedMailMessage latest = group.getFirst();
            long threadId = insertThread(connection, accountId, group, latest);
            insertMessages(connection, threadId, group);
        }
    }

    static void recordAccountSyncState(
            Connection connection,
            String accountId,
            String lastSyncAt,
            String statusMessage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into account_sync_state (account_id, last_sync_at, status_message)
                values (?, ?, ?)
                on conflict(account_id) do update set
                    last_sync_at = excluded.last_sync_at,
                    status_message = excluded.status_message
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, lastSyncAt);
            statement.setString(3, statusMessage == null ? "" : statusMessage);
            statement.executeUpdate();
        }
    }

    static void appendSentDraft(
            Connection connection,
            String accountId,
            String accountName,
            String fromEmail,
            MailDraft draft,
            String sentAt) throws SQLException {
        ImportedMailMessage message = new ImportedMailMessage(
                accountId,
                "Sent",
                "<swim-local-" + System.nanoTime() + "@swim>",
                "sent:" + normalizeSubject(draft.subject()) + "|" + normalizeWhitespace(fromEmail) + "|"
                        + normalizeWhitespace(draft.to()),
                draft.subject(),
                accountName,
                fromEmail,
                draft.to(),
                sentAt,
                sentAt,
                snippet(draft.body()),
                draft.body(),
                false);
        long threadId = insertThread(connection, accountId, List.of(message), message);
        insertMessages(connection, threadId, List.of(message));
    }

    static MailSnapshot loadSnapshot(Connection connection, EmailPaths paths) throws SQLException {
        List<MailAccountSummary> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select a.id, a.name, a.protocol,
                    coalesce((select count(*) from threads t where t.account_id = a.id), 0),
                    coalesce((select sum(t.unread_count) from threads t where t.account_id = a.id), 0),
                    s.last_sync_at,
                    s.status_message
                from accounts a
                left join account_sync_state s on s.account_id = a.id
                order by a.name
                """);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                accounts.add(new MailAccountSummary(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getInt(4),
                        resultSet.getInt(5),
                        resultSet.getString(6),
                        resultSet.getString(7)));
            }
        }

        List<MailThreadSummary> threads = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select id, account_id, subject, participants, snippet, last_message_at, unread_count, message_count
                from threads
                order by coalesce(last_message_at, '') desc, id desc
                limit 500
                """);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long threadId = resultSet.getLong(1);
                threads.add(new MailThreadSummary(
                        threadId,
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getString(6),
                        resultSet.getInt(7) > 0,
                        resultSet.getInt(8),
                        loadTagsForThread(connection, threadId)));
            }
        }

        String status = statusMessage(accounts, threads, paths);
        return new MailSnapshot(accounts, threads, status);
    }

    static MailMessageDetail loadMessage(Connection connection, long threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select id, thread_id, subject,
                    trim(coalesce(from_name, '') || case when from_email is null or from_email = '' then '' else ' <' || from_email || '>' end),
                    to_summary,
                    coalesce(sent_at, received_at),
                    body_text
                from messages
                where thread_id = ?
                order by coalesce(received_at, sent_at, '') desc, id desc
                limit 1
                """)) {
            statement.setLong(1, threadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new MailMessageDetail(
                            resultSet.getLong(1),
                            resultSet.getLong(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            resultSet.getString(5),
                            resultSet.getString(6),
                            resultSet.getString(7),
                            loadTagsForThread(connection, threadId));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                select subject, participants, snippet, last_message_at
                from threads
                where id = ?
                """)) {
            statement.setLong(1, threadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new MailMessageDetail(
                            0L,
                            threadId,
                            resultSet.getString(1),
                            resultSet.getString(2),
                            "",
                            resultSet.getString(4),
                            resultSet.getString(3),
                            loadTagsForThread(connection, threadId));
                }
            }
        }
        return new MailMessageDetail(0L, threadId, "(no message)", "", "", "", "", List.of());
    }

    private static List<String> loadTagsForThread(Connection connection, long threadId) throws SQLException {
        List<String> tags = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select distinct mt.tag_name
                from message_tags mt
                join messages m on m.id = mt.message_id
                where m.thread_id = ?
                order by mt.tag_name
                """)) {
            statement.setLong(1, threadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tags.add(resultSet.getString(1));
                }
            }
        }
        return tags;
    }

    private static void deleteAccountMessages(Connection connection, String accountId) throws SQLException {
        try (PreparedStatement deleteMessageTags = connection.prepareStatement("""
                delete from message_tags
                where message_id in (select id from messages where account_id = ?)
                """);
                PreparedStatement deleteMessages = connection.prepareStatement(
                        "delete from messages where account_id = ?");
                PreparedStatement deleteThreads = connection.prepareStatement(
                        "delete from threads where account_id = ?")) {
            deleteMessageTags.setString(1, accountId);
            deleteMessageTags.executeUpdate();
            deleteMessages.setString(1, accountId);
            deleteMessages.executeUpdate();
            deleteThreads.setString(1, accountId);
            deleteThreads.executeUpdate();
        }
    }

    private static long insertThread(
            Connection connection,
            String accountId,
            List<ImportedMailMessage> group,
            ImportedMailMessage latest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into threads (
                    account_id, folder_name, subject, participants, snippet, last_message_at, unread_count, message_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, accountId);
            statement.setString(2, latest.folderName());
            statement.setString(3, latest.subject());
            statement.setString(4, latest.participants());
            statement.setString(5, latest.snippet());
            statement.setString(6, latest.effectiveTimestamp());
            statement.setInt(7, (int) group.stream().filter(ImportedMailMessage::unread).count());
            statement.setInt(8, group.size());
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to create thread for account " + accountId);
    }

    private static void insertMessages(Connection connection, long threadId, List<ImportedMailMessage> messages)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into messages (
                    account_id, thread_id, internet_message_id, folder_name, subject, from_name, from_email,
                    to_summary, sent_at, received_at, snippet, body_text, is_read
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (ImportedMailMessage message : messages) {
                statement.setString(1, message.accountId());
                statement.setLong(2, threadId);
                statement.setString(3, message.internetMessageId());
                statement.setString(4, message.folderName());
                statement.setString(5, message.subject());
                statement.setString(6, message.fromName());
                statement.setString(7, message.fromEmail());
                statement.setString(8, message.toSummary());
                statement.setString(9, message.sentAt());
                statement.setString(10, message.receivedAt());
                statement.setString(11, message.snippet());
                statement.setString(12, message.bodyText());
                statement.setInt(13, message.unread() ? 0 : 1);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String statusMessage(
            List<MailAccountSummary> accounts,
            List<MailThreadSummary> threads,
            EmailPaths paths) {
        List<String> statuses = accounts.stream()
                .map(MailAccountSummary::syncStatus)
                .filter(status -> status != null && !status.isBlank())
                .distinct()
                .toList();
        if (!statuses.isEmpty() && threads.isEmpty()) {
            return String.join("  |  ", statuses);
        }
        if (!threads.isEmpty()) {
            return "";
        }
        return "Configure accounts in " + paths.accountsPath()
                + " and tag rules in " + paths.tagRulesPath()
                + ". Mail is stored in " + paths.databasePath() + ".";
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String normalized = normalizeWhitespace(body);
        return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        return subject.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }
}
