package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailMessageSummary;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadFilter;
import org.fisk.swim.mail.MailThreadPage;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

class MailPanelViewTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDownTerminal() {
        TerminalContext.shutdownInstance();
    }

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
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of(), "<root@example.com>");
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
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of(), "<root@example.com>");
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
    void constructorDoesNotWaitForUnreadCounts() throws Exception {
        CountDownLatch countsStarted = new CountDownLatch(1);
        CountDownLatch allowCountsToFinish = new CountDownLatch(1);

        long startedAt = System.nanoTime();
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 2, 2, "2026-05-15T08:00:00Z", "")),
                        List.of(new MailThreadSummary(1L, "work", "Async counts", "Boss", "snippet",
                                "2026-05-13T08:00:00Z", false, 1, List.of("vip"))),
                        "");
            }

            @Override
            public List<String> loadTagNames() {
                return List.of("vip");
            }

            @Override
            public Map<String, Integer> loadAccountUnreadCounts() {
                countsStarted.countDown();
                try {
                    allowCountsToFinish.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of("work", 2);
            }

            @Override
            public Map<String, Integer> loadTagUnreadCounts() {
                return Map.of("vip", 2);
            }

            @Override
            public int loadUnsortedUnreadCount() {
                return 1;
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(1L, threadId, "Async counts", "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body", List.of("vip"));
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 500, "constructor should not wait for unread count queries");
        assertTrue(countsStarted.await(2, TimeUnit.SECONDS));
        assertEquals(0, HeadlessWindowHarness.getField(panel, "_allUnreadCount", Integer.class));
        @SuppressWarnings("unchecked")
        Map<String, Integer> initialAccountCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_accountUnreadCounts");
        assertEquals(0, initialAccountCounts.getOrDefault("work", 0));
        @SuppressWarnings("unchecked")
        Map<String, Integer> initialCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_tagUnreadCounts");
        assertEquals(0, initialCounts.getOrDefault("vip", 0));

        allowCountsToFinish.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Map<String, Integer> loadedCounts = initialCounts;
        while (HeadlessWindowHarness.getField(panel, "_allUnreadCount", Integer.class) != 2
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
            loadedCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_tagUnreadCounts");
        }

        @SuppressWarnings("unchecked")
        Map<String, Integer> loadedAccountCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_accountUnreadCounts");
        assertEquals(2, loadedAccountCounts.get("work"));
        assertEquals(2, HeadlessWindowHarness.getField(panel, "_allUnreadCount", Integer.class));
        assertEquals(2, loadedCounts.get("vip"));
        assertEquals(1, HeadlessWindowHarness.getField(panel, "_unsortedUnreadCount", Integer.class));
    }

    @Test
    void extractsActionableUrlFromOAuthStatusText() {
        String url = MailPanelView.firstUrl(
                "Complete browser sign-in at https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press e in the mail panel.");

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
                            "Complete browser sign-in at https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press e in the mail panel.");
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
    void openLinkUsesAccountSyncStatusWhenGlobalMailStatusIsEmpty() {
        AtomicReference<String> openedUrl = new AtomicReference<>();
        ExternalResourceSupport.setUrlOpenerForTesting(url -> {
            openedUrl.set(url);
            return true;
        });
        try {
            var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            List.of(
                                    new MailAccountSummary("work", "Work", "IMAP", 1, 0, "", "1 messages"),
                                    new MailAccountSummary("outlook", "Outlook", "IMAP", 0, 0, "",
                                            "Complete browser sign-in at https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press e in the mail panel.")),
                            List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", false, 1, List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
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

            assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=abc", openedUrl.get());
        } finally {
            ExternalResourceSupport.resetForTesting();
        }
    }

    @Test
    void drawShowsModalOverlayForSecondaryAccountBrowserSignIn() {
        var terminal = TerminalContextTestSupport.install(80, 20);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("work", "Work", "IMAP", 1, 0, "", "1 messages"),
                                new MailAccountSummary("outlook", "Outlook", "IMAP", 0, 0, "",
                                        "Complete browser sign-in at https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=abc and wait for the callback, then press e in the mail panel.")),
                        List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                "snippet", "2026-05-15T10:00:00Z", false, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                        "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        panel.draw(panel.getBounds());
        String rendered = renderedText(terminal.drawCalls());

        assertTrue(rendered.contains("Mail Sign-In Required"));
        assertTrue(rendered.contains("login.microsoftonline.com"));
        assertTrue(rendered.contains("consumers"));
        assertTrue(rendered.contains("o open link"));
    }

    @Test
    void drawShowsModalOverlayForSecondaryAccountDeviceCodeSignIn() {
        var terminal = TerminalContextTestSupport.install(80, 20);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("work", "Work", "IMAP", 1, 0, "", "1 messages"),
                                new MailAccountSummary("outlook", "Outlook", "IMAP", 0, 0, "",
                                        "To sign in, use a web browser to open the page https://www.microsoft.com/link and enter the code KY29RFQF to authenticate. Then press e in the mail panel.")),
                        List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                "snippet", "2026-05-15T10:00:00Z", false, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                        "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        panel.draw(panel.getBounds());
        String rendered = renderedText(terminal.drawCalls());

        assertTrue(rendered.contains("Mail Sign-In Required"));
        assertTrue(rendered.contains("https://www.microsoft.com/link"));
        assertTrue(rendered.contains("KY29RFQF"));
        assertTrue(rendered.contains("o open link"));
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
                        "me@example.com", "2026-05-13T08:00:00Z", "Please review", List.of(), "<root@example.com>");
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
                if (detail != null && detail.messageId() > 0L && "<root@example.com>".equals(detail.internetMessageId())) {
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
        assertEquals("<root@example.com>", sentDraft.get().inReplyToMessageId());
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
            if (detail != null && detail.messageId() > 0L) {
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
    void detailViewShowsCcWhenPresent() throws Exception {
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

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        MailMessageDetail detail = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
        while ((detail == null || !"team@example.com".equals(detail.cc())) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            detail = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
        }
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
        MailMessageDetail selectedMessage = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
        while ((selectedMessage == null || selectedMessage.threadId() != 1L) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            selectedMessage = HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class);
        }

        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
        assertTrue(selectedMessage != null);
        assertEquals(1L, selectedMessage.threadId());
    }

    @Test
    void sidebarShowsAccountIdentifiersInsteadOfDisplayNames() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("oracle", "Erik Oesterlund", "IMAP", 1, 1, "", ""),
                                new MailAccountSummary("outlook", "Erik Oesterlund", "IMAP", 1, 0, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "oracle", "Oracle thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of()),
                                new MailThreadSummary(2L, "outlook", "Outlook thread", "Friend", "snippet",
                                        "2026-05-12T08:00:00Z", false, 1, List.of())),
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

        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) HeadlessWindowHarness.getField(panel, "_sidebarRows");

        assertEquals("oracle", HeadlessWindowHarness.getField(rows.get(2), "label", String.class));
        assertEquals("outlook", HeadlessWindowHarness.getField(rows.get(3), "label", String.class));
    }

    @Test
    void drawShowsNumberedSidebarItems() {
        var terminal = TerminalContextTestSupport.install(80, 20);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("oracle", "Erik Oesterlund", "IMAP", 1, 1, "", ""),
                                new MailAccountSummary("outlook", "Erik Oesterlund", "IMAP", 1, 0, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "oracle", "Oracle thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of()),
                                new MailThreadSummary(2L, "outlook", "Outlook thread", "Friend", "snippet",
                                        "2026-05-12T08:00:00Z", false, 1, List.of())),
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

        panel.draw(panel.getBounds());
        String rendered = renderedText(terminal.drawCalls());

        assertTrue(rendered.contains("1 #unsorted"));
        assertTrue(rendered.contains("2 #all"));
        assertTrue(rendered.contains("3 oracle"));
        assertTrue(rendered.contains("4 outlook"));
    }

    @Test
    void drawHighlightsUnreadThreadSubjects() {
        var terminal = TerminalContextTestSupport.install(80, 20);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        panel.draw(panel.getBounds());

        var call = drawCallContaining(terminal.drawCalls(), "Unread thread");
        assertEquals(UiTheme.MAIL_UNREAD_FOREGROUND, call.foreground());
    }

    @Test
    void drawHighlightsActiveComposeField() {
        var terminal = TerminalContextTestSupport.install(80, 20);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        panel.draw(panel.getBounds());

        var call = drawCallContaining(terminal.drawCalls(), "To:");
        assertEquals(UiTheme.ACCENT_BLUE, call.foreground());
        assertEquals(UiTheme.MAIL_COMPOSE_FIELD_BACKGROUND, call.background());
    }

    @Test
    void mouseClickSelectsThreadRow() throws Exception {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        HeadlessWindowHarness.dispatch(panel,
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(30, 3)));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class) != 1
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
        assertEquals("THREADS", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
    }

    @Test
    void mouseClickSelectsSidebarRow() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        HeadlessWindowHarness.dispatch(panel,
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(2, 3)));

        assertEquals(1, HeadlessWindowHarness.getField(panel, "_sidebarSelection", Integer.class));
        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
    }

    @Test
    void mouseWheelMovesThreadSelection() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        HeadlessWindowHarness.dispatch(panel,
                new MouseAction(MouseActionType.SCROLL_DOWN, 5, new TerminalPosition(30, 3)));

        assertEquals(2, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
    }

    @Test
    void mouseClickSelectsComposeField() {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), styledMailClient());

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
        HeadlessWindowHarness.dispatch(panel,
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(12, 5)));

        assertEquals("SUBJECT", HeadlessWindowHarness.getField(panel, "_composeField", Enum.class).name());
    }

    @Test
    void numberGJumpsToSidebarItem() throws Exception {
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(
                                new MailAccountSummary("oracle", "Erik Oesterlund", "IMAP", 1, 1, "", ""),
                                new MailAccountSummary("outlook", "Erik Oesterlund", "IMAP", 1, 0, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "oracle", "Oracle thread", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of()),
                                new MailThreadSummary(2L, "outlook", "Outlook thread", "Friend", "snippet",
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

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('4'));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('g'));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(2L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
        assertEquals(3, HeadlessWindowHarness.getField(panel, "_sidebarSelection", Integer.class));
        assertEquals(2L, lastLoadedThread.get());
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
                                new MailThreadSummary(1L, "work", "Tagged list thread", "Boss", "snippet",
                                        "2026-05-13T09:00:00Z", true, 1, List.of("vip")),
                                new MailThreadSummary(2L, "work", "Tagged direct thread", "Friend", "snippet",
                                        "2026-05-13T08:00:00Z", false, 1, List.of("vip"), true),
                                new MailThreadSummary(3L, "work", "Untagged thread", "Friend", "snippet",
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
        @SuppressWarnings("unchecked")
        List<MailThreadSummary> loadedThreads = (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
        while ((loadedThreads.isEmpty() || loadedThreads.getFirst().threadId() != 1L) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            loadedThreads = (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
        }

        assertEquals("SIDEBAR", HeadlessWindowHarness.getField(panel, "_browsePane", Enum.class).name());
        assertEquals(1L, loadedThreads.getFirst().threadId());
    }

    @Test
    void constructorLoadsAdditionalPagesWhenDefaultFilteredViewIsSparse() throws Exception {
        var offsets = new ArrayList<Integer>();
        AtomicReference<Long> lastLoadedThread = new AtomicReference<>(0L);
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 400, 20, "", "")),
                        sparseDirectAddressPage(0, 100, 400, MailThreadFilter.unsorted()).threads(),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                offsets.add(offset);
                return sparseDirectAddressPage(offset, limit, 400, filter);
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                lastLoadedThread.set(threadId);
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId, "Boss <boss@example.com>",
                        "me@example.com", "2026-05-13T08:00:00Z", "Body", List.of("vip"));
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
        while (!Long.valueOf(20L).equals(lastLoadedThread.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        @SuppressWarnings("unchecked")
        List<MailThreadSummary> loadedThreads = (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
        assertEquals(List.of(0), offsets);
        assertEquals(20, loadedThreads.size());
        assertEquals(20L, lastLoadedThread.get());
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
                                "2026-05-13T08:00:00Z", true, 1, List.of("vip"), true)),
                        "");
            }

            @Override
            public Map<String, Integer> loadAccountUnreadCounts() {
                return Map.of("work", Long.valueOf(11L).equals(markedRead.get()) ? 0 : 1);
            }

            @Override
            public Map<String, Integer> loadTagUnreadCounts() {
                return Map.of("vip", Long.valueOf(11L).equals(markedRead.get()) ? 0 : 1);
            }

            @Override
            public int loadUnsortedUnreadCount() {
                return Long.valueOf(11L).equals(markedRead.get()) ? 0 : 1;
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
        assertEquals(0, HeadlessWindowHarness.getField(panel, "_unsortedUnreadCount", Integer.class));
        @SuppressWarnings("unchecked")
        Map<String, Integer> tagUnreadCounts = (Map<String, Integer>) HeadlessWindowHarness.getField(panel, "_tagUnreadCounts");
        assertEquals(0, tagUnreadCounts.getOrDefault("vip", 0));
        MailSnapshot snapshot = HeadlessWindowHarness.getField(panel, "_snapshot", MailSnapshot.class);
        assertEquals(0, snapshot.accounts().getFirst().unreadCount());
    }

    @Test
    void searchReloadsThreadsUsingEnteredQuery() throws Exception {
        AtomicReference<String> lastQuery = new AtomicReference<>("");
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 120, 3, "", "")),
                        sampleThreads(100),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                lastQuery.set(query);
                if ("boss".equals(query)) {
                    return new MailThreadPage(List.of(
                            new MailThreadSummary(7L, "work", "Boss update", "Boss", "Need review",
                                    "2026-05-13T09:00:00Z", true, 1, List.of())), 1);
                }
                return new MailThreadPage(sampleThreads(100), 120);
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
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
        @SuppressWarnings("unchecked")
        List<MailThreadSummary> loadedThreads = (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
        while ((loadedThreads.isEmpty() || loadedThreads.getFirst().threadId() != 7L) && System.nanoTime() < searchDeadline) {
            Thread.sleep(10);
            loadedThreads = (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
        }

        assertEquals("boss", lastQuery.get());
        assertEquals(7L, loadedThreads.getFirst().threadId());
        assertEquals("boss", HeadlessWindowHarness.getField(panel, "_searchQuery", String.class));
    }

    @Test
    void threadColumnGroupsRepliesIntoConversationTreeOrderedByNewestConversation() throws Exception {
        var panel = new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 2, 1, "", "")),
                        List.of(
                                new MailThreadSummary(7L, "work", "Re: Quarterly review", "Boss", "Approved",
                                        "2026-05-13T10:00:05Z", true, 3, List.of("vip"), true),
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
                return new MailMessageDetail(13L, threadId, "Message 13",
                        "sender@example.com", "me@example.com", "2026-05-13T10:00:05Z", "Body 13", List.of());
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (rows.size() != 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
            rows = (List<Object>) HeadlessWindowHarness.getField(panel, "_threadRows");
        }

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
                return new MailMessageDetail(13L, threadId, "Message 13",
                        "sender@example.com", "me@example.com", "2026-05-13T10:00:05Z", "Body 13", List.of());
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
        while (!"Message 13".equals(HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class).subject())
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(2, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));

        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('k'));

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Long.valueOf(12L).equals(lastLoadedMessage.get()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!"Message 12".equals(HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class).subject())
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(1, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
        assertEquals(12L, lastLoadedMessage.get());
        assertEquals("Message 12", HeadlessWindowHarness.getField(panel, "_selectedMessage", MailMessageDetail.class).subject());
        assertEquals(0, messageBuffer.getBuffer().getCursor().getPosition());
        assertEquals(0, messageBuffer.getBufferView().getStartLine());
    }

    @Test
    void initialThreadPageSkipsBulkThreadMessageLoad() {
        AtomicInteger bulkLoads = new AtomicInteger();
        new MailPanelView(Rect.create(0, 0, 80, 20), new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 3, 1, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "work", "Thread 1", "Boss", "snippet",
                                        "2026-05-13T08:00:00Z", true, 1, List.of()),
                                new MailThreadSummary(2L, "work", "Thread 2", "Boss", "snippet",
                                        "2026-05-13T07:00:00Z", false, 1, List.of()),
                                new MailThreadSummary(3L, "work", "Thread 3", "Boss", "snippet",
                                        "2026-05-13T06:00:00Z", false, 1, List.of())),
                        "");
            }

            @Override
            public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                return new MailThreadPage(snapshot().threads(), 3);
            }

            @Override
            public Map<Long, List<MailMessageSummary>> loadThreadMessages(List<Long> threadIds) {
                bulkLoads.incrementAndGet();
                var result = new java.util.LinkedHashMap<Long, List<MailMessageSummary>>();
                for (Long threadId : threadIds) {
                    result.put(threadId, List.of(new MailMessageSummary(threadId, threadId, 0L, "Thread " + threadId,
                            "Boss <boss@example.com>", "me@example.com", "2026-05-13T08:00:00Z", "snippet", false)));
                }
                return result;
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId,
                        "Boss <boss@example.com>", "me@example.com", "2026-05-13T08:00:00Z", "Body", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        });

        assertEquals(0, bulkLoads.get());
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
            public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
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
            public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
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

    private static MailThreadPage sparseDirectAddressPage(int offset, int limit, int total, MailThreadFilter filter) {
        if (offset >= total) {
            return new MailThreadPage(List.of(), 20);
        }
        var matching = new ArrayList<MailThreadSummary>();
        for (long threadId = 1L; threadId <= total; threadId++) {
            boolean addressedToAccount = threadId % 20L == 0L;
            MailThreadSummary thread = new MailThreadSummary(
                    threadId,
                    "work",
                    "Thread " + threadId,
                    "Boss",
                    "snippet",
                    "2026-05-13T08:00:00Z",
                    addressedToAccount,
                    1,
                    List.of("vip"),
                    addressedToAccount);
            if (filter == null || filter.matches(thread)) {
                matching.add(thread);
            }
        }
        int safeOffset = Math.max(0, offset);
        if (safeOffset >= matching.size()) {
            return new MailThreadPage(List.of(), matching.size());
        }
        int endExclusive = Math.min(matching.size(), safeOffset + Math.max(0, limit));
        return new MailThreadPage(matching.subList(safeOffset, endExclusive), matching.size());
    }

    private static MailClient styledMailClient() {
        return new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(new MailAccountSummary("work", "Work", "IMAP", 3, 1, "", "")),
                        List.of(
                                new MailThreadSummary(1L, "work", "Read thread", "Alice <alice@example.com>",
                                        "read snippet", "2026-05-13T08:00:00Z", false, 1, List.of()),
                                new MailThreadSummary(2L, "work", "Unread thread", "Bob <bob@example.com>",
                                        "unread snippet", "2026-05-13T09:00:00Z", true, 1, List.of()),
                                new MailThreadSummary(3L, "work", "Later thread", "Carol <carol@example.com>",
                                        "later snippet", "2026-05-13T10:00:00Z", false, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(threadId, threadId, "Thread " + threadId,
                        "sender@example.com", "me@example.com", "2026-05-13T08:00:00Z",
                        "Body " + threadId, List.of());
            }

            @Override
            public Map<Long, List<MailMessageSummary>> loadThreadMessages(List<Long> threadIds) {
                var messages = new java.util.LinkedHashMap<Long, List<MailMessageSummary>>();
                for (Long threadId : threadIds) {
                    boolean unread = threadId == 2L;
                    String subject = switch (threadId.intValue()) {
                    case 1 -> "Read thread";
                    case 2 -> "Unread thread";
                    default -> "Later thread";
                    };
                    messages.put(threadId, List.of(new MailMessageSummary(
                            threadId,
                            threadId,
                            0L,
                            subject,
                            "sender@example.com",
                            "me@example.com",
                            "2026-05-13T08:00:00Z",
                            "snippet",
                            unread)));
                }
                return messages;
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return Path.of("/tmp/mail");
            }
        };
    }

    private static org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall drawCallContaining(
            List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls,
            String text) {
        return drawCalls.stream()
                .filter(call -> call.text().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No draw call containing " + text));
    }

    private static String renderedText(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls) {
        var text = new StringBuilder();
        for (var call : drawCalls) {
            text.append(call.text()).append('\n');
        }
        return text.toString();
    }
}
