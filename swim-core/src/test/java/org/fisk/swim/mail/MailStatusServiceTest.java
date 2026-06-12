package org.fisk.swim.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.EventThread;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

class MailStatusServiceTest {
    @AfterEach
    void tearDown() throws Exception {
        MailStatusService.shutdownInstance();
        EventThread.shutdownInstance();
        setWindowInstance(null);
    }

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

    @Test
    void timerPollDefersUiRefreshUntilEventThreadRuns() throws Exception {
        EventThread.shutdownInstance();
        EventThread eventThread = EventThread.getInstance();
        TrackingWindow.reset();
        setWindowInstance(allocate(TrackingWindow.class));
        var client = new LatchingMailClient(snapshot(1, "alpha", "Initial sync"));
        var service = new MailStatusService(60_000, 1_000, () -> client, true);
        try {
            assertTrue(client.awaitRefresh(), "mail status timer did not poll");

            assertNull(TrackingWindow.refreshThreadName());

            eventThread.start();

            assertTrue(TrackingWindow.awaitRefresh(), "queued mail chrome refresh did not run");
            assertNotEquals(client.refreshThreadName(), TrackingWindow.refreshThreadName());
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

    private static final class LatchingMailClient implements MailClient {
        private final FakeMailClient _delegate;
        private final CountDownLatch _refreshLatch = new CountDownLatch(1);
        private final AtomicReference<String> _refreshThreadName = new AtomicReference<>();

        private LatchingMailClient(MailSnapshot... snapshots) {
            _delegate = new FakeMailClient(snapshots);
        }

        @Override
        public MailSnapshot snapshot() {
            return _delegate.snapshot();
        }

        @Override
        public MailMessageDetail loadMessage(long threadId) {
            return _delegate.loadMessage(threadId);
        }

        @Override
        public void refresh() {
            _delegate.refresh();
            _refreshThreadName.set(Thread.currentThread().getName());
            _refreshLatch.countDown();
        }

        @Override
        public Path getDataPath() {
            return _delegate.getDataPath();
        }

        private boolean awaitRefresh() throws InterruptedException {
            return _refreshLatch.await(2, TimeUnit.SECONDS);
        }

        private String refreshThreadName() {
            return _refreshThreadName.get();
        }
    }

    private static void setWindowInstance(Window window) throws Exception {
        Field field = Window.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, window);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(lookupUnsafe().allocateInstance(type));
    }

    private static Unsafe lookupUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class TrackingWindow extends Window {
        private static final AtomicReference<String> REFRESH_THREAD_NAME = new AtomicReference<>();
        private static CountDownLatch refreshLatch = new CountDownLatch(1);

        private TrackingWindow() {
            super((Path) null);
        }

        @Override
        public void refreshChromeState() {
            REFRESH_THREAD_NAME.set(Thread.currentThread().getName());
            refreshLatch.countDown();
        }

        private static void reset() {
            REFRESH_THREAD_NAME.set(null);
            refreshLatch = new CountDownLatch(1);
        }

        private static String refreshThreadName() {
            return REFRESH_THREAD_NAME.get();
        }

        private static boolean awaitRefresh() throws InterruptedException {
            return refreshLatch.await(2, TimeUnit.SECONDS);
        }
    }
}
