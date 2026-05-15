package org.fisk.swim.mail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface MailClient extends AutoCloseable {
    MailSnapshot snapshot();

    MailMessageDetail loadMessage(long threadId);

    default MailThreadPage loadThreads(String query, int offset, int limit) {
        MailSnapshot snapshot = snapshot();
        List<MailThreadSummary> filtered = filterThreads(snapshot.threads(), query);
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
