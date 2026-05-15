package org.fisk.swim.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class MailStatusServiceTest {
    @Test
    void firstPollEstablishesUnreadBaselineWithoutNotification() {
        var clientRef = new AtomicReference<MailClient>(new FakeMailClient(snapshot(4, "alpha", "Initial sync")));
        var service = new MailStatusService(0, 1_000, clientRef::get, false);
        try {
            service.pollNow();

            MailStatus status = service.currentStatus();
            assertEquals(4, status.unreadCount());
            assertNull(status.notification());
        } finally {
            service.close();
        }
    }

    @Test
    void laterPollCreatesNotificationWhenUnreadCountIncreases() {
        var clientRef = new AtomicReference<MailClient>(new FakeMailClient(
                snapshot(2, "alpha", "Initial sync"),
                snapshot(5, "build-bot", "Nightly build failed")));
        var service = new MailStatusService(0, 1_000, clientRef::get, false);
        try {
            service.pollNow();
            service.pollNow();

            MailStatus status = service.currentStatus();
            assertEquals(5, status.unreadCount());
            assertNotNull(status.notification());
            assertEquals("3 new emails", status.notification().heading());
            assertEquals("build-bot", status.notification().sender());
            assertEquals("Nightly build failed +2 more", status.notification().detail());
        } finally {
            service.close();
        }
    }

    private static MailSnapshot snapshot(int unreadCount, String sender, String subject) {
        return new MailSnapshot(
                List.of(new MailAccountSummary("account", "Inbox", "imap", unreadCount, unreadCount, "", "")),
                List.of(new MailThreadSummary(1L, "account", subject, sender, "snippet", "2026-05-15T10:00:00Z", true, 1,
                        List.of("inbox"))),
                "");
    }

    private static final class FakeMailClient implements MailClient {
        private final ArrayDeque<MailSnapshot> _snapshots = new ArrayDeque<>();
        private MailSnapshot _current = new MailSnapshot(List.of(), List.of(), "");

        private FakeMailClient(MailSnapshot... snapshots) {
            _snapshots.addAll(List.of(snapshots));
        }

        @Override
        public MailSnapshot snapshot() {
            return _current;
        }

        @Override
        public MailMessageDetail loadMessage(long threadId) {
            return new MailMessageDetail(threadId, threadId, "", "", "", "", "", List.of());
        }

        @Override
        public void refresh() {
            if (!_snapshots.isEmpty()) {
                _current = _snapshots.removeFirst();
            }
        }

        @Override
        public Path getDataPath() {
            return Path.of(".");
        }
    }
}
