package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.junit.jupiter.api.Test;

class MailPanelViewTest {
    @Test
    void constructorRefreshesWhenCachedSnapshotStillHasLegacyHundredMessageCap() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(1);
        new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 100, 0, "", "")),
                        List.of(new MailThreadSummary(1L, "work", "Quarterly review", "Boss", "Please review",
                                "2026-05-13T08:00:00Z", false, 100, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public void refresh() {
                refreshed.countDown();
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        assertTrue(refreshed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void extractsActionableUrlFromOAuthStatusText() {
        String url = MailPanelView.firstUrl(
                "Complete browser sign-in at https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press r in the mail panel.");

        assertEquals("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc", url);
    }

    @Test
    void composeReplySendsDraftUsingSelectedMessageDefaults() {
        AtomicReference<MailDraft> sentDraft = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 1, 1, "", "")),
                        List.of(new MailThreadSummary(1L, "work", "Quarterly review", "Boss", "Please review",
                                "2026-05-13T08:00:00Z", true, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public void refresh() {
                refreshed.countDown();
            }

            @Override
            public MailSendResult sendDraft(MailDraft draft) {
                sentDraft.set(draft);
                return MailSendResult.success("sent");
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        try {
            refreshed.await(2, TimeUnit.SECONDS);
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.ctrl('s'));

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (sentDraft.get() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(sentDraft.get() != null);
        assertEquals("work", sentDraft.get().accountId());
        assertEquals("boss@example.com", sentDraft.get().to());
        assertEquals("Re: Quarterly review", sentDraft.get().subject());
    }
}
