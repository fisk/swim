package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fisk.swim.mail.*;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.event.MouseAction;
import org.fisk.swim.event.MouseActionType;
import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailLoadingTest {
    @TempDir Path tempDir;

    @AfterEach
    void closeTerminal() {
        TerminalContext.shutdownInstance();
    }

    @Test
    void slowCacheDoesNotBlockOpeningAndFirstPageIsLoadedOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pages = new AtomicInteger();
        var loaded = new CountDownLatch(1);
        var client = new FixtureClient() {
            @Override public boolean backgroundReads() { return true; }
            @Override public MailSnapshot sidebarSnapshot() {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.snapshot();
            }
            @Override public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                pages.incrementAndGet();
                assertEquals(MailThreadFilter.Kind.UNSORTED, filter.kind());
                return super.loadThreads(query, offset, limit, filter);
            }
            @Override public MailMessageDetail loadMessage(long id) {
                loaded.countDown();
                return super.loadMessage(id);
            }
        };
        var panel = assertTimeoutPreemptively(Duration.ofMillis(300),
                () -> new MailPanelView(Rect.create(0, 0, 100, 30), client));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, pages.get());
            release.countDown();
            assertTrue(loaded.await(3, TimeUnit.SECONDS));
            assertEquals(1, pages.get());
        } finally {
            release.countDown();
            panel.disposeForReload();
        }
    }

    @Test
    void eachMailboxHasSeparateAllAndUnsortedViews() {
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), new FixtureClient());
        try {
            jump(panel, '4'); // work / #all
            assertEquals(3, threads(panel).size());
            assertTrue(threads(panel).stream().allMatch(row -> row.accountId().equals("work")));
            jump(panel, '5'); // work / #unsorted
            assertEquals(2, threads(panel).size());
            assertTrue(threads(panel).stream().allMatch(row -> row.accountId().equals("work")));
            assertTrue(threads(panel).stream().noneMatch(row -> row.threadId() == 2));
            jump(panel, '8'); // personal / #unsorted
            assertEquals(List.of(4L), threads(panel).stream().map(MailThreadSummary::threadId).toList());
            jump(panel, '2'); // global / #all
            assertEquals(4, threads(panel).size());
        } finally {
            panel.disposeForReload();
        }
    }

    @Test
    void composingFromAnEmptyMailboxChildKeepsThatAccount() {
        for (char child : new char[] {'7', '8'}) {
            var panel = new MailPanelView(Rect.create(0, 0, 100, 30), new FixtureClient() {
                @Override public MailSnapshot snapshot() {
                    var snapshot = super.snapshot();
                    return new MailSnapshot(snapshot.accounts(), snapshot.threads().stream()
                            .filter(thread -> thread.accountId().equals("work")).toList(), "");
                }
            });
            try {
                jump(panel, child);
                assertTrue(threads(panel).isEmpty());
                HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('c'));
                assertEquals("personal", HeadlessWindowHarness.getField(panel, "_composeAccountId", String.class));
            } finally {
                panel.disposeForReload();
            }
        }
    }

    @Test
    void changingToEmptyMailboxClearsThePreviousMessage() {
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), new FixtureClient() {
            @Override public MailSnapshot snapshot() {
                var snapshot = super.snapshot();
                return new MailSnapshot(snapshot.accounts(), snapshot.threads().stream()
                        .filter(thread -> thread.accountId().equals("work")).toList(), "");
            }
        });
        var buffer = new BufferContext(Rect.create(0, 0, 40, 30), tempDir.resolve("fixture-body"));
        panel.attachMessageBuffer(buffer);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!buffer.getBuffer().getString().contains("Subject: Fixture")) {
                    Thread.sleep(10);
                }
            });
            jump(panel, '7');
            assertFalse(buffer.getBuffer().getString().contains("Subject: Fixture"));
        } finally {
            panel.disposeForReload();
        }
    }

    @Test
    void scrollingMailboxSidebarPreservesFilterAndMessageSelection() {
        TerminalContextTestSupport.install(100, 8);
        var panel = new MailPanelView(Rect.create(0, 0, 100, 8), new FixtureClient());
        try {
            int selected = HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class);
            HeadlessWindowHarness.dispatch(panel,
                    new MouseAction(MouseActionType.SCROLL_DOWN, 5, new MouseAction.Position(2, 3)));
            panel.draw(panel.getBounds());
            assertEquals(0, HeadlessWindowHarness.getField(panel, "_sidebarSelection", Integer.class));
            assertEquals(selected, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
            assertTrue(HeadlessWindowHarness.getField(panel, "_sidebarScrollOffset", Integer.class) > 0);
        } finally {
            panel.disposeForReload();
        }
    }

    @Test
    void jumpToEndReadsOnlyTheLastPageOfALargeMailbox() throws Exception {
        var offsets = new CopyOnWriteArrayList<Integer>();
        var first = new CountDownLatch(1);
        var last = new CountDownLatch(1);
        var client = new FixtureClient() {
            @Override public boolean backgroundReads() { return true; }
            @Override public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                offsets.add(offset);
                var rows = new ArrayList<MailThreadSummary>();
                for (int i = offset; i < Math.min(10_000, offset + limit); i++) {
                    rows.add(thread(i + 1, "work", List.of(), false));
                }
                return new MailThreadPage(rows, 10_000);
            }
            @Override public MailMessageDetail loadMessage(long id) {
                if (id == 1) { first.countDown(); }
                if (id == 10_000) { last.countDown(); }
                return super.loadMessage(id);
            }
        };
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
        try {
            assertTrue(first.await(3, TimeUnit.SECONDS));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('G'));
            assertTrue(last.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(0, 9900), offsets);
            assertEquals(100, threads(panel).size());
            assertEquals(99, HeadlessWindowHarness.getField(panel, "_selectedIndex", Integer.class));
        } finally {
            panel.disposeForReload();
        }
    }

    @Test
    void delayedFilterResultCannotReplaceANewerMailboxSelection() throws Exception {
        var initialLoaded = new CountDownLatch(1);
        var workQuery = new CountDownLatch(1);
        var releaseWork = new CountDownLatch(1);
        var personalLoaded = new CountDownLatch(1);
        var client = new FixtureClient() {
            @Override public boolean backgroundReads() { return true; }
            @Override public MailThreadPage loadThreads(String query, int offset, int limit, MailThreadFilter filter) {
                if (filter.kind() == MailThreadFilter.Kind.ACCOUNT_UNSORTED && filter.value().equals("work")) {
                    workQuery.countDown();
                    try { releaseWork.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return super.loadThreads(query, offset, limit, filter);
            }
            @Override public MailMessageDetail loadMessage(long id) {
                if (id == 1) { initialLoaded.countDown(); }
                if (id == 4) { personalLoaded.countDown(); }
                return super.loadMessage(id);
            }
        };
        var panel = new MailPanelView(Rect.create(0, 0, 100, 30), client);
        try {
            assertTrue(initialLoaded.await(3, TimeUnit.SECONDS));
            jump(panel, '5');
            assertTrue(workQuery.await(2, TimeUnit.SECONDS));
            jump(panel, '8');
            releaseWork.countDown();
            assertTrue(personalLoaded.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("personal"), threads(panel).stream().map(MailThreadSummary::accountId).distinct().toList());
        } finally {
            releaseWork.countDown();
            panel.disposeForReload();
        }
    }

    private static void jump(MailPanelView panel, char number) {
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key(number));
        HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('g'));
    }

    @SuppressWarnings("unchecked")
    private static List<MailThreadSummary> threads(MailPanelView panel) {
        return (List<MailThreadSummary>) HeadlessWindowHarness.getField(panel, "_threads");
    }

    private static MailThreadSummary thread(long id, String account, List<String> tags, boolean addressed) {
        return new MailThreadSummary(id, account, "Fixture " + id, "", "", "", false, 1, tags, addressed);
    }

    private static class FixtureClient implements MailClient {
        @Override public MailSnapshot snapshot() {
            return new MailSnapshot(List.of(
                    new MailAccountSummary("work", "Work", "IMAP", 3, 0, "cached", ""),
                    new MailAccountSummary("personal", "Personal", "IMAP", 1, 0, "cached", "")),
                    List.of(thread(1, "work", List.of(), false), thread(2, "work", List.of("sorted"), false),
                            thread(3, "work", List.of("sorted"), true), thread(4, "personal", List.of(), false)), "");
        }
        @Override public MailMessageDetail loadMessage(long id) {
            return new MailMessageDetail(id, id, "Fixture", "", "", "", "", List.of());
        }
        @Override public void refresh() { }
        @Override public Path getDataPath() { return Path.of("/tmp/mail-test-fixture"); }
    }
}
