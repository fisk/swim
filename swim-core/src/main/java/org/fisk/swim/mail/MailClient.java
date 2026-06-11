package org.fisk.swim.mail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface MailClient extends AutoCloseable {
    MailSnapshot snapshot();

    default MailSnapshot snapshotWithoutUnreadCounts() {
        MailSnapshot snapshot = snapshot();
        var accounts = new ArrayList<MailAccountSummary>();
        for (MailAccountSummary account : snapshot.accounts()) {
            accounts.add(new MailAccountSummary(
                    account.id(),
                    account.name(),
                    account.protocol(),
                    account.threadCount(),
                    0,
                    account.lastSyncAt(),
                    account.syncStatus()));
        }
        return new MailSnapshot(accounts, snapshot.threads(), snapshot.statusMessage());
    }

    MailMessageDetail loadMessage(long threadId);

    default MailMessageDetail loadMessageById(long messageId) {
        return loadMessage(messageId);
    }

    default List<MailMessageSummary> loadThreadMessages(long threadId) {
        MailMessageDetail detail = loadMessage(threadId);
        if (detail == null) {
            return List.of();
        }
        return List.of(new MailMessageSummary(
                detail.messageId(),
                threadId,
                0L,
                detail.subject(),
                detail.from(),
                detail.to(),
                detail.sentAt(),
                detail.bodyText(),
                false));
    }

    default Map<Long, List<MailMessageSummary>> loadThreadMessages(List<Long> threadIds) {
        var result = new LinkedHashMap<Long, List<MailMessageSummary>>();
        if (threadIds == null) {
            return result;
        }
        for (Long threadId : threadIds) {
            if (threadId == null) {
                continue;
            }
            result.put(threadId, loadThreadMessages(threadId));
        }
        return result;
    }

    default List<String> loadTagNames() {
        var tags = new LinkedHashSet<String>();
        for (MailThreadSummary thread : snapshot().threads()) {
            tags.addAll(thread.tags());
        }
        return List.copyOf(tags);
    }

    default Map<String, Integer> loadTagUnreadCounts() {
        return Map.of();
    }

    default Map<String, Integer> loadAccountUnreadCounts() {
        var counts = new LinkedHashMap<String, Integer>();
        for (MailAccountSummary account : snapshot().accounts()) {
            counts.put(account.id(), account.unreadCount());
        }
        return counts;
    }

    default int loadUnsortedUnreadCount() {
        return 0;
    }

    default void markMessageRead(long messageId) {
    }

    default MailThreadPage loadThreads(String query, int offset, int limit) {
        return loadThreads(query, offset, limit, MailThreadFilter.all());
    }

    default MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
        MailSnapshot snapshot = snapshot();
        List<MailThreadSummary> filtered = filterThreads(snapshot.threads(), query);
        if (filter != null && filter.kind() != MailThreadFilter.Kind.ALL) {
            filtered = filtered.stream()
                    .filter(filter::matches)
                    .toList();
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        if (safeOffset >= filtered.size() || safeLimit == 0) {
            return new MailThreadPage(List.of(), filtered.size());
        }
        int endExclusive = Math.min(filtered.size(), safeOffset + safeLimit);
        return new MailThreadPage(filtered.subList(safeOffset, endExclusive), filtered.size());
    }

    void refresh();

    default MailSendResult sendDraft(MailDraft draft) {
        return MailSendResult.failure("Sending is not available");
    }

    Path getDataPath();

    @Override
    default void close() {
    }

    private static List<MailThreadSummary> filterThreads(List<MailThreadSummary> threads, String query) {
        if (query == null || query.isBlank()) {
            return threads;
        }
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        var filtered = new ArrayList<MailThreadSummary>();
        for (MailThreadSummary thread : threads) {
            if (matches(thread, normalized)) {
                filtered.add(thread);
            }
        }
        return filtered;
    }

    private static boolean matches(MailThreadSummary thread, String query) {
        if (contains(thread.subject(), query)
                || contains(thread.participants(), query)
                || contains(thread.snippet(), query)
                || contains(thread.receivedAt(), query)) {
            return true;
        }
        for (String tag : thread.tags()) {
            if (contains(tag, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
