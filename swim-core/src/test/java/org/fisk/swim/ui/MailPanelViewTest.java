package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
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
import org.fisk.swim.mail.MailThreadPage;
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
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 100, 1, "", "")),
                        sampleThreads(100),
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

    @Test
    void searchReloadsThreadsUsingEnteredQuery() {
        AtomicReference<String> lastQuery = new AtomicReference<>("");
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 120, 3, "", "")),
                        sampleThreads(100),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit) {
                lastQuery.set(query);
                if ("boss".equals(query)) {
                    return new MailThreadPage(List.of(
                            new MailThreadSummary(7L, "work", "Boss update", "Boss", "Need review",
                                    "2026-05-13T09:00:00Z", true, 1, List.of("vip"))), 1);
                }
                return new MailThreadPage(sampleThreads(100), 120);
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                return new MailMessageDetail(threadId, threadId, "Subject " + threadId, "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('/'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('b'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('o'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('s'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('s'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.enter());

        assertEquals("boss", lastQuery.get());
        assertEquals(7L, lastLoadedThread.get());
        assertEquals("boss", HeadlessWindowHarness.getField(panel, "_searchQuery", String.class));
    }

    @Test
    void movingToBottomLoadsAdditionalThreadPages() {
        var offsets = new ArrayList<Integer>();
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 150, 5, "", "")),
                        sampleThreads(100),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit) {
                offsets.add(offset);
                int total = 150;
                if (offset >= total) {
                    return new MailThreadPage(List.of(), total);
                }
                int count = Math.min(limit, total - offset);
                var page = new ArrayList<MailThreadSummary>();
                for (int i = 0; i < count; i++) {
                    long threadId = offset + i + 1L;
                    page.add(new MailThreadSummary(threadId, "work", "Thread " + threadId, "Boss",
                            "Snippet " + threadId, "2026-05-13T08:00:00Z", false, 1, List.of()));
                }
                return new MailThreadPage(page, total);
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('G'));

        assertTrue(offsets.contains(100));
        assertEquals(150L, lastLoadedThread.get());
    }

    private static List<MailThreadSummary> sampleThreads(int count) {
        var threads = new ArrayList<MailThreadSummary>();
        for (int i = 0; i < count; i++) {
            long threadId = i + 1L;
            threads.add(new MailThreadSummary(threadId, "work", "Thread " + threadId, "Boss",
                    "Snippet " + threadId, "2026-05-13T08:00:00Z", i == 0, 1, List.of()));
        }
        return threads;
    }
}
