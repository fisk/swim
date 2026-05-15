package org.fisk.swim.mail;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Supplier;

import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.EventThread;
import org.fisk.swim.ui.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MailStatusService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MailStatusService.class);
    private static final long DEFAULT_POLL_INTERVAL_MS = Long.getLong("swim.mail.poll.interval.ms", 30_000L);
    private static final long DEFAULT_NOTIFICATION_DURATION_MS = Long.getLong("swim.mail.notification.duration.ms", 8_000L);

    private static volatile MailStatusService _instance;

    private final Supplier<MailClient> _clientSupplier;
    private final Timer _timer;
    private final long _notificationDurationMs;

    private volatile MailStatus _status = MailStatus.empty();
    private MailClient _currentClient;
    private boolean _baselineEstablished;
    private long _notificationSequence;
    private TimerTask _dismissTask;

    public static MailStatusService getInstance() {
        var instance = _instance;
        if (instance == null) {
            synchronized (MailStatusService.class) {
                instance = _instance;
                if (instance == null) {
                    instance = new MailStatusService(
                            DEFAULT_POLL_INTERVAL_MS,
                            DEFAULT_NOTIFICATION_DURATION_MS,
                            MailPluginRegistry::getClient,
                            true);
                    _instance = instance;
                }
            }
        }
        return instance;
    }

    public static void shutdownInstance() {
        synchronized (MailStatusService.class) {
            if (_instance != null) {
                _instance.close();
                _instance = null;
            }
        }
    }

    MailStatusService(
            long pollIntervalMs,
            long notificationDurationMs,
            Supplier<MailClient> clientSupplier,
            boolean startPolling) {
        _clientSupplier = clientSupplier;
        _notificationDurationMs = Math.max(0L, notificationDurationMs);
        _timer = new Timer(true);
        if (startPolling && pollIntervalMs > 0) {
            _timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    pollNow();
                }
            }, 0L, pollIntervalMs);
        }
    }

    public MailStatus currentStatus() {
        return _status;
    }

    public synchronized void pollNow() {
        MailClient client = _clientSupplier.get();
        if (client != _currentClient) {
            _currentClient = client;
            _baselineEstablished = false;
            clearNotificationLocked(false);
            if (client == null) {
                updateStatusLocked(MailStatus.empty());
                return;
            }
        } else if (client == null) {
            updateStatusLocked(MailStatus.empty());
            return;
        }

        try {
            client.refresh();
            applySnapshotLocked(client.snapshot());
        } catch (RuntimeException e) {
            LOG.warn("Failed to refresh mail status", e);
        }
    }

    @Override
    public synchronized void close() {
        clearNotificationLocked(false);
        _timer.cancel();
        _status = MailStatus.empty();
        _currentClient = null;
        _baselineEstablished = false;
    }

    private void applySnapshotLocked(MailSnapshot snapshot) {
        int unreadCount = unreadCount(snapshot);
        MailNotification notification = _status.notification();
        if (_baselineEstablished) {
            int delta = unreadCount - _status.unreadCount();
            if (delta > 0) {
                notification = buildNotification(delta, unreadCount, snapshot);
                scheduleDismissLocked();
            }
        } else {
            _baselineEstablished = true;
        }
        updateStatusLocked(new MailStatus(unreadCount, notification));
    }

    private void updateStatusLocked(MailStatus nextStatus) {
        _status = nextStatus == null ? MailStatus.empty() : nextStatus;
        requestUiRefresh();
    }

    private void scheduleDismissLocked() {
        clearNotificationLocked(false);
        if (_notificationDurationMs <= 0) {
            return;
        }
        long sequence = ++_notificationSequence;
        _dismissTask = new TimerTask() {
            @Override
            public void run() {
                dismissNotification(sequence);
            }
        };
        _timer.schedule(_dismissTask, _notificationDurationMs);
    }

    private synchronized void dismissNotification(long sequence) {
        if (sequence != _notificationSequence || _status.notification() == null) {
            return;
        }
        clearNotificationLocked(true);
    }

    private void clearNotificationLocked(boolean refreshUi) {
        if (_dismissTask != null) {
            _dismissTask.cancel();
            _dismissTask = null;
        }
        if (_status.notification() != null) {
            _status = new MailStatus(_status.unreadCount(), null);
            if (refreshUi) {
                requestUiRefresh();
            }
        }
    }

    private MailNotification buildNotification(int delta, int unreadCount, MailSnapshot snapshot) {
        MailThreadSummary preview = snapshot.threads().stream()
                .filter(MailThreadSummary::unread)
                .findFirst()
                .orElseGet(() -> snapshot.threads().isEmpty() ? null : snapshot.threads().get(0));
        String heading = delta == 1 ? "1 new email" : delta + " new emails";
        if (preview == null) {
            return new MailNotification(heading, "", unreadCount + " unread total");
        }
        String sender = firstNonBlank(preview.participants(), preview.accountId(), "mail");
        String detail = firstNonBlank(preview.subject(), preview.snippet(), unreadCount + " unread total");
        if (delta > 1 && !detail.equals(unreadCount + " unread total")) {
            detail = detail + " +" + (delta - 1) + " more";
        }
        return new MailNotification(heading, sender, detail);
    }

    private int unreadCount(MailSnapshot snapshot) {
        if (snapshot.accounts().isEmpty()) {
            return (int) snapshot.threads().stream().filter(MailThreadSummary::unread).count();
        }
        return snapshot.accounts().stream().mapToInt(MailAccountSummary::unreadCount).sum();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private void requestUiRefresh() {
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        Runnable refresh = window::refreshChromeState;
        EventThread eventThread = EventThread.getInstance();
        if (eventThread.isAlive() && Thread.currentThread() != eventThread) {
            eventThread.enqueue(new RunnableEvent(refresh));
        } else {
            refresh.run();
        }
    }
}
