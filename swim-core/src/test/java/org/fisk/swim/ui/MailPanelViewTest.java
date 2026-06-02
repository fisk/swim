package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailMessageSummary;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadPage;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailPanelViewTest {
    @TempDir
    Path tempDir;

    @Test
    void constructorRefreshesWhenAccountHasNeverSynced() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(1);
        new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 100, 0, "", "100 messages")),
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
    void constructorDoesNotRefreshWhenAccountAlreadyHasSyncState() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(1);
        new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 100, 0, "2026-05-15T08:00:00Z", "100 messages")),
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

        assertTrue(!refreshed.await(250, TimeUnit.MILLISECONDS));
    }

    @Test
    void extractsActionableUrlFromOAuthStatusText() {
        String url = MailPanelView.firstUrl(
                "Complete browser sign-in at https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press r in the mail panel.");

        assertEquals("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc", url);
    }

    @Test
    void openLinkFallsBackToCopyWhenBrowserLaunchFails() {
        AtomicReference<String> openedUrl = new AtomicReference<>();
        AtomicReference<String> copiedUrl = new AtomicReference<>();
        ExternalResourceSupport.setUrlOpenerForTesting(url -> {
            openedUrl.set(url);
            return false;
        });
        ExternalResourceSupport.setTextCopierForTesting(text -> {
            copiedUrl.set(text);
            return true;
        });
        try {
            var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            List.of(new MailAccountSummary("work", "Work", "IMAP", 0, 0, "", "")),
                            List.of(),
                            "Complete browser sign-in at https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press r in the mail panel.");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return null;
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return Path.of("/tmp/mail");
                }
            });

            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('o'));

            assertEquals("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc", openedUrl.get());
            assertEquals(openedUrl.get(), copiedUrl.get());
            assertEquals("Could not open URL. Copied sign-in link for manual use",
                    HeadlessWindowHarness.getField(panel, "_statusMessage", String.class));
        } finally {
            ExternalResourceSupport.resetForTesting();
        }
    }

    @Test
    void replySendsDraftUsingSelectedMessageDefaults() {
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
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                var detail = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
                if (detail != null && detail.messageId() != 0L) {
                    break;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('r'));
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
    void replyAllUsesSenderAndExistingRecipients() {
        AtomicReference<MailDraft> sentDraft = new AtomicReference<>();
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
                        "me@example.com, teammate@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public MailSendResult sendDraft(MailDraft draft) {
                sentDraft.set(draft);
                return MailSendResult.success("sent");
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var detail = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
            if (detail != null && detail.messageId() != 0L) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('R'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.ctrl('s'));

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sentDraft.get() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(sentDraft.get() != null);
        assertEquals("boss@example.com, me@example.com, teammate@example.com", sentDraft.get().to());
        assertEquals("Re: Quarterly review", sentDraft.get().subject());
    }

    @Test
    void composeSendCanProceedWhileRefreshIsRunning() throws Exception {
        AtomicReference<MailDraft> sentDraft = new AtomicReference<>();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch allowRefreshToFinish = new CountDownLatch(1);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 0, 0, "", "")),
                        List.of(),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return null;
            }

            @Override
            public void refresh() {
                refreshStarted.countDown();
                try {
                    allowRefreshToFinish.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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

        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('b'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('o'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('s'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('s'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('@'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('x'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('a'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('m'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('p'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('l'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('.'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('o'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('m'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.ctrl('s'));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sentDraft.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        allowRefreshToFinish.countDown();

        assertTrue(sentDraft.get() != null);
        assertEquals("boss@example.com", sentDraft.get().to());
    }

    @Test
    void composeStartsBlankFromC() {
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
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });
        panel.attachMessageBuffer(new BufferContext(Rect.create(0, 0, 40, 20), tempDir.resolve("mail-compose-body.txt")));

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));

        assertEquals("COMPOSE", HeadlessWindowHarness.getField(panel, "_mode", Enum.class).name());
        assertEquals("", HeadlessWindowHarness.getField(panel, "_composeTo", StringBuilder.class).toString());
        assertEquals("", HeadlessWindowHarness.getField(panel, "_composeCc", StringBuilder.class).toString());
        assertEquals("", HeadlessWindowHarness.getField(panel, "_composeBcc", StringBuilder.class).toString());
        assertEquals("", HeadlessWindowHarness.getField(panel, "_composeSubject", StringBuilder.class).toString());
        assertEquals("", HeadlessWindowHarness.getField(panel, "_messageBufferContext", BufferContext.class).getBuffer().getString());
    }

    @Test
    void replySeedsQuotedBodyInMessageBuffer() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 10, 1, "", "")),
                        sampleThreads(10),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review\nSecond line", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });
        panel.attachMessageBuffer(new BufferContext(Rect.create(0, 0, 40, 20), tempDir.resolve("mail-reply-body.txt")));

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('r'));

        String body = HeadlessWindowHarness.getField(panel, "_messageBufferContext", BufferContext.class).getBuffer().getString();
        assertTrue(body.contains("Boss <boss@example.com> wrote this 2026-05-13T08:00:00Z:"));
        assertTrue(body.contains("> Please review"));
        assertTrue(body.contains("> Second line"));
    }

    @Test
    void detailViewShowsCcWhenPresent() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 10, 1, "", "")),
                        sampleThreads(10),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
                        "me@example.com", "team@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        MailMessageDetail detail = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
        assertEquals("team@example.com", detail.cc());
    }

    @Test
    void refreshUsesEKey() {
        AtomicReference<Integer> refreshCalls = new AtomicReference<>(0);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 100, 1, "2026-05-15T08:00:00Z", "")),
                        sampleThreads(10),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of());
            }

            @Override
            public void refresh() {
                refreshCalls.set(refreshCalls.get() + 1);
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (refreshCalls.get() == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(1, refreshCalls.get());
    }

    @Test
    void composeTabOrderIncludesCcAndBcc() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 10, 1, "", "")),
                        sampleThreads(10),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Quarterly review", "Boss <boss@example.com>",
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

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        assertEquals("TO", HeadlessWindowHarness.getField(panel, "_composeField", Enum.class).name());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.tab());
        assertEquals("CC", HeadlessWindowHarness.getField(panel, "_composeField", Enum.class).name());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.tab());
        assertEquals("BCC", HeadlessWindowHarness.getField(panel, "_composeField", Enum.class).name());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.tab());
        assertEquals("SUBJECT", HeadlessWindowHarness.getField(panel, "_composeField", Enum.class).name());
    }

    @Test
    void leftAndRightArrowSwitchBetweenSidebarAndThreadsPane() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("work", "Work", "IMAP", 1, 1, "", ""),
                                new MailAccountSummary("personal", "Personal", "IMAP", 1, 0, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "work", "Work thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of("vip")),
                                new MailThreadSummary(2L, "personal", "Personal thread", "Friend", "snippet",
                                        "2026-05-12T08:00:00Z", false, 1, List.of("friends"))),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "sender@example.com",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        assertEquals("THREADS", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.left());
        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.right());
        assertEquals("THREADS", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
    }

    @Test
    void selectingAccountInSidebarFiltersVisibleThreads() throws Exception {
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("work", "Work", "IMAP", 1, 1, "", ""),
                                new MailAccountSummary("personal", "Personal", "IMAP", 1, 0, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "work", "Work thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of("vip")),
                                new MailThreadSummary(2L, "personal", "Personal thread", "Friend", "snippet",
                                        "2026-05-12T08:00:00Z", false, 1, List.of("friends"))),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "sender@example.com",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(1L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.left());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.down());

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(1L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
        assertEquals(1L, lastLoadedThread.get());
    }

    @Test
    void constructorDefaultsToUnsortedAndSidebarCanSwitchToAll() throws Exception {
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 2, 1, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "work", "Tagged thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of("vip")),
                                new MailThreadSummary(2L, "work", "Untagged thread", "Friend", "snippet",
                                        "2026-05-12T08:00:00Z", false, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "sender@example.com",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(2L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.left());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.down());

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(1L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
        assertEquals(1L, lastLoadedThread.get());
    }

    @Test
    void loadingUnreadMessageMarksItReadAndUpdatesUnreadCounts() throws Exception {
        AtomicReference<Long> markedRead = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 1, 1, "", "")),
                        List.of(new MailThreadSummary(7L, "work", "Tagged thread", "Boss", "snippet",
                                "2026-05-13T08:00:00Z", true, 1, List.of("vip"))),
                        "");
            }

            @Override
            public Map<String, Integer> loadTagUnreadCounts() {
                return Map.of("vip", 1);
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(11L, threadId, "Tagged thread", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body", List.of("vip"));
            }

            @Override
            public void markMessageRead(long messageId) {
                markedRead.set(messageId);
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.left());
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.down());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(11L).equals(markedRead.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Integer.valueOf(0).equals(HeadlessWindowHarness.getField(panel, "_allUnreadCount", Integer.class))
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(11L, markedRead.get());
        assertEquals(0, HeadlessWindowHarness.getField(panel, "_allUnreadCount", Integer.class));
        @SuppressWarnings("unchecked")
        Map<String, Integer> tagUnreadCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_tagUnreadCounts");
        assertEquals(0, tagUnreadCounts.get("vip"));
        MailSnapshot snapshot = HeadlessWindowHarness.getField(panel, "_snapshot", MailSnapshot.class);
        assertEquals(0, snapshot.accounts().getFirst().unreadCount());
    }

    @Test
    void searchReloadsThreadsUsingEnteredQuery() throws Exception {
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

        long searchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(7L).equals(lastLoadedThread.get()) && System.nanoTime() < searchDeadline) {
            Thread.sleep(10);
        }

        assertEquals("boss", lastQuery.get());
        assertEquals(7L, lastLoadedThread.get());
        assertEquals("boss", HeadlessWindowHarness.getField(panel, "_searchQuery", String.class));
    }

    @Test
    void threadColumnGroupsRepliesIntoConversationTreeOrderedByNewestConversation() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 2, 1, "", "")),
                        List.of(
                                new MailThreadSummary(7L, "work", "Re: Quarterly review", "Boss", "Approved",
                                        "2026-05-13T10:00:05Z", true, 3, List.of("vip")),
                                new MailThreadSummary(9L, "work", "Team notes", "Teammate", "FYI",
                                        "2026-05-13T09:30:05Z", false, 1, List.of())),
                        "");
            }

            @Override
            public List<MailMessageSummary> loadThreadMessages(long threadId) {
                if (threadId == 7L) {
                    return List.of(
                            new MailMessageSummary(11L, 7L, 0L, "Quarterly review", "Boss <boss@example.com>",
                                    "me@example.com",
                                    "2026-05-13T08:31:00Z", "Initial note", true),
                            new MailMessageSummary(12L, 7L, 11L, "Re: Quarterly review", "Me <me@example.com>",
                                    "boss@example.com",
                                    "2026-05-13T09:00:05Z", "Looks good", false),
                            new MailMessageSummary(13L, 7L, 12L, "Re: Quarterly review", "Boss <boss@example.com>",
                                    "me@example.com",
                                    "2026-05-13T10:00:05Z", "Approved", false));
                }
                return List.of(new MailMessageSummary(21L, 9L, 0L, "Team notes", "Teammate <mate@example.com>",
                        "me@example.com",
                        "2026-05-13T09:30:05Z", "FYI", false));
            }

            @Override
            public MailMessageDetail loadMessageById(long messageId) {
                return new MailMessageDetail(messageId, messageId == 21L ? 9L : 7L, "Message " + messageId,
                        "sender@example.com", "me@example.com", "2026-05-13T08:00:00Z", "Body " + messageId, List.of());
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId,
                        "sender@example.com", "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) HeadlessWindowHarness.getField(panel, "_threadRows");

        assertEquals(4, rows.size());
        assertTrue(rows.get(0).toString().contains("Quarterly review"));
        assertTrue(rows.get(1).toString().contains("Re: Quarterly review"));
        assertTrue(rows.get(2).toString().contains("Re: Quarterly review"));
        assertTrue(rows.get(3).toString().contains("Team notes"));
    }

    @Test
    void selectingDifferentRowsWithinThreadLoadsSelectedMessage() throws Exception {
        AtomicReference<Long> lastLoadedMessage = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 1, 1, "", "")),
                        List.of(new MailThreadSummary(7L, "work", "Re: Quarterly review", "Boss", "Approved",
                                "2026-05-13T10:00:05Z", true, 3, List.of())),
                        "");
            }

            @Override
            public List<MailMessageSummary> loadThreadMessages(long threadId) {
                return List.of(
                        new MailMessageSummary(11L, 7L, 0L, "Quarterly review", "Boss <boss@example.com>",
                                "me@example.com",
                                "2026-05-13T08:31:00Z", "Initial note", true),
                        new MailMessageSummary(12L, 7L, 11L, "Re: Quarterly review", "Me <me@example.com>",
                                "boss@example.com",
                                "2026-05-13T09:00:05Z", "Looks good", false),
                        new MailMessageSummary(13L, 7L, 12L, "Re: Quarterly review", "Boss <boss@example.com>",
                                "me@example.com",
                                "2026-05-13T10:00:05Z", "Approved", false));
            }

            @Override
            public MailMessageDetail loadMessageById(long messageId) {
                lastLoadedMessage.set(messageId);
                return new MailMessageDetail(messageId, 7L, "Message " + messageId,
                        "sender@example.com", "me@example.com", "2026-05-13T08:00:00Z", "Body " + messageId, List.of());
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId,
                        "sender@example.com", "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });
        BufferContext messageBuffer = new BufferContext(Rect.create(0, 0, 40, 20), tempDir.resolve("mail-message-buffer.txt"));
        panel.attachMessageBuffer(messageBuffer);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(11L).equals(lastLoadedMessage.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('j'));

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(12L).equals(lastLoadedMessage.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
        assertEquals(12L, lastLoadedMessage.get());
        assertEquals("Message 12", HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class).subject());
        assertEquals(0, messageBuffer.getBuffer().getCursor().getPosition());
        assertEquals(0, messageBuffer.getBufferView().getStartLine());
    }

    @Test
    void movingToBottomLoadsAdditionalThreadPages() throws Exception {
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

        long loadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(150L).equals(lastLoadedThread.get()) && System.nanoTime() < loadDeadline) {
            Thread.sleep(10);
        }

        assertTrue(offsets.contains(100));
        assertEquals(150L, lastLoadedThread.get());
    }

    @Test
    void movingPastInitialTotalRetriesWhenBackgroundBackfillAddsMoreThreads() throws Exception {
        var offsets = new ArrayList<Integer>();
        AtomicReference<Integer> totalCount = new AtomicReference<>(250);
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 250, 5, "2026-05-15T08:00:00Z", "")),
                        page(0, 100, totalCount.get()).threads(),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit) {
                offsets.add(offset);
                if (offset >= 200) {
                    totalCount.set(300);
                }
                return page(offset, limit, totalCount.get());
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
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('j'));

        long loadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(251L).equals(lastLoadedThread.get()) && System.nanoTime() < loadDeadline) {
            Thread.sleep(10);
        }

        assertTrue(offsets.contains(200));
        assertEquals(300, HeadlessWindowHarness.getField(panel, "_totalThreadCount", Integer.class));
        assertEquals(251L, lastLoadedThread.get());
    }

    @Test
    void navigationDoesNotBlockWhenMessageLoadingIsSlow() throws Exception {
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch allowLoadsToFinish = new CountDownLatch(1);
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 3, 1, "2026-05-15T08:00:00Z", "")),
                        sampleThreads(3),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                firstLoadStarted.countDown();
                try {
                    allowLoadsToFinish.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        assertTrue(firstLoadStarted.await(2, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('j'));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 500, "navigation should not wait for loadMessage");
        assertEquals(1, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));

        allowLoadsToFinish.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(2L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(2L, lastLoadedThread.get());
    }

    @Test
    void composeStartsWithoutWaitingForSlowMessageLoad() throws Exception {
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch allowLoadsToFinish = new CountDownLatch(1);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 3, 1, "2026-05-15T08:00:00Z", "")),
                        sampleThreads(3),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                firstLoadStarted.countDown();
                try {
                    allowLoadsToFinish.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body " + threadId, List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        assertTrue(firstLoadStarted.await(2, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 500, "compose should not wait for loadMessage");
        assertEquals("COMPOSE", HeadlessWindowHarness.getField(panel, "_mode", Enum.class).name());

        allowLoadsToFinish.countDown();
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

    private static MailThreadPage page(int offset, int limit, int total) {
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
}
