package org.fisk.swim.mail;

import java.util.List;

public record MailThreadPage(
        List<MailThreadSummary> threads,
        int totalCount) {
    public MailThreadPage {
        threads = threads == null ? List.of() : List.copyOf(threads);
        totalCount = Math.max(0, totalCount);
    }

    public boolean hasMore(int offset) {
        return Math.max(0, offset) + threads.size() < totalCount;
    }
}
