package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailPluginRegistry;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailStatusService;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailNotificationViewTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        MailPluginRegistry.clear();
        MailStatusService.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

    @Test
    void drawsUpperRightNotificationWhenNewMailArrives() throws Exception {
        var terminal = TerminalContextTestSupport.install(60, 12);
        MailPluginRegistry.register(new FakeMailClient(
                snapshot(2, "alpha", "Initial sync"),
                snapshot(3, "bob@example.com", "Quarterly report")));

        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("mail.txt"), 60, 12)) {
            var service = MailStatusService.getInstance();
            service.pollNow();
            service.pollNow();

            harness.getWindow().getRootView().update(Rect.create(0, 0, 60, 12), true);

            assertTrue(terminal.drawCalls().stream().anyMatch(call -> call.y() == 0 && call.text().contains("1 new email")));
            assertTrue(terminal.drawCalls().stream().anyMatch(call -> call.y() == 1 && call.text().contains("bob@example.com")));
            assertTrue(terminal.drawCalls().stream().anyMatch(call -> call.y() == 2 && call.text().contains("Quarterly report")));
            assertTrue(terminal.drawCalls().stream()
                    .filter(call -> call.text().contains("1 new email"))
                    .allMatch(call -> call.x() >= 8));
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
