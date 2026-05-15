package org.fisk.swim.mail;

import java.util.List;

public record MailSnapshot(
        List<MailAccountSummary> accounts,
        List<MailThreadSummary> threads,
        String statusMessage) {
    public MailSnapshot {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        threads = threads == null ? List.of() : List.copyOf(threads);
        statusMessage = statusMessage == null ? "" : statusMessage;
    }
}
