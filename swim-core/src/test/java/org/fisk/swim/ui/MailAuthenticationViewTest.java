package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MailAuthenticationViewTest {
    private static final String PROMPT = "Authorize mail at https://fixture.invalid/sign-in with code FIXTURE1, then press e in the mail panel.";

    @AfterEach
    void closeTerminal() {
        TerminalContext.shutdownInstance();
    }

    @Test
    void informationalStartupAndSyncStatusesNeverAskForSignIn() {
        var terminal = TerminalContextTestSupport.install(100, 30);
        for (String status : List.of("Loading cached mail...", "100 messages", "No mail has been synced yet.",
                "Mail sign-in complete", "Connection failed", "See https://fixture.invalid/status for server status")) {
            var client = new FixtureClient(false, snapshot(status, ""));
            var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
            try {
                terminal.drawCalls().clear();
                panel.draw(panel.getBounds());
                assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"), status);
            } finally {
                panel.disposeForReload();
            }
        }
    }

    @Test
    void openingWhileCacheIsLoadingDoesNotShowSignIn() throws Exception {
        var terminal = TerminalContextTestSupport.install(100, 30);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var initial = snapshot("100 messages", "");
        var client = new FixtureClient(true, initial) {
            @Override public MailSnapshot sidebarSnapshot() {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return super.sidebarSnapshot();
            }
        };
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            panel.draw(panel.getBounds());
            assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
            release.countDown();
            awaitSnapshot(panel, initial);
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
        } finally {
            release.countDown();
            panel.disposeForReload();
        }
    }

    @Test
    void secondaryAccountPromptCanBeDismissedAndDoesNotReturnOnTheSameRefresh() {
        var terminal = TerminalContextTestSupport.install(100, 30);
        var initial = snapshot("100 messages  |  " + PROMPT, PROMPT);
        var client = new FixtureClient(true, initial);
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
        try {
            awaitSnapshot(panel, initial);
            panel.draw(panel.getBounds());
            String text = rendered(terminal.drawCalls());
            assertTrue(text.contains("Mail Sign-In Required"));
            assertTrue(text.contains("o open link"));
            assertTrue(text.contains("Esc dismiss"));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.escape());
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
            assertTrue(rendered(terminal.drawCalls()).contains("Fixture cached message"));

            refresh(panel, client, snapshot("101 messages  |  " + PROMPT, PROMPT));
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));

            refresh(panel, client, snapshot("101 messages", PROMPT.replace("FIXTURE1", "FIXTURE2")));
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertTrue(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
        } finally {
            panel.disposeForReload();
        }
    }

    @Test
    void successfulBackgroundRefreshClearsThePromptAndItsOldLinkAndStatus() {
        var terminal = TerminalContextTestSupport.install(100, 30);
        var initial = snapshot("100 messages", PROMPT);
        var client = new FixtureClient(true, initial);
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
        try {
            awaitSnapshot(panel, initial);
            panel.draw(panel.getBounds());
            assertTrue(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.escape());
            refresh(panel, client, snapshot("101 messages", ""));
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertFalse(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
            assertEquals("101 messages", HeadlessWindowHarness.getField(panel, "_statusMessage"));
            assertNull(HeadlessWindowHarness.getField(panel, "_lastActionableUrl"));

            // A later genuine request must still appear, even with the same URL/code.
            refresh(panel, client, snapshot("101 messages", PROMPT));
            terminal.drawCalls().clear();
            panel.draw(panel.getBounds());
            assertTrue(rendered(terminal.drawCalls()).contains("Mail Sign-In Required"));
        } finally {
            panel.disposeForReload();
        }
    }

    private static void refresh(MailPanelView panel, FixtureClient client, MailSnapshot snapshot) {
        client.current.set(snapshot);
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));
        awaitSnapshot(panel, snapshot);
    }

    private static void awaitSnapshot(MailPanelView panel, MailSnapshot expected) {
        await(() -> {
            synchronized (panel) {
                return HeadlessWindowHarness.getField(panel, "_snapshot") == expected;
            }
        });
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!condition.getAsBoolean()) { Thread.sleep(10); }
        });
    }

    private static String rendered(List<TerminalContextTestSupport.DrawCall> calls) {
        return calls.stream().map(TerminalContextTestSupport.DrawCall::text).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static MailSnapshot snapshot(String status, String secondaryStatus) {
        return new MailSnapshot(List.of(
                new MailAccountSummary("work", "Work", "IMAP", 1, 0, "cached", "100 messages"),
                new MailAccountSummary("personal", "Personal", "IMAP", 0, 0, "cached", secondaryStatus)),
                List.of(new MailThreadSummary(1, "work", "Fixture cached message", "Fixture sender", "", "", false, 1, List.of())), status);
    }

    private static class FixtureClient implements MailClient {
        final AtomicReference<MailSnapshot> current;
        private final boolean background;

        FixtureClient(boolean background, MailSnapshot snapshot) {
            this.background = background;
            current = new AtomicReference<>(snapshot);
        }
        @Override public boolean backgroundReads() { return background; }
        @Override public MailSnapshot snapshot() { return current.get(); }
        @Override public MailSnapshot sidebarSnapshot() { return snapshot(); }
        @Override public MailMessageDetail loadMessage(long id) { return null; }
        @Override public void refresh() { }
        @Override public Path getDataPath() { return Path.of("/tmp/mail-fixture"); }
    }
}
