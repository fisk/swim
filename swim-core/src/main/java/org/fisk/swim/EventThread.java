package org.fisk.swim;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.fisk.swim.event.Event;
import org.fisk.swim.event.KeyStrokeEvent;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;

public class EventThread extends Thread {
    private static Logger _log = LogFactory.createLog();
    private static final long FUTURE_POLL_MILLIS = 50L;

    private final ListEventResponder _responder;
    private final ArrayBlockingQueue<Event> _events = new ArrayBlockingQueue<>(1024, true);
    private final ExecutorService _eventExecutor;
    private static volatile EventThread _instance;
    private final List<Runnable> _onEventRunnables = new CopyOnWriteArrayList<>();
    private final List<Consumer<KeyStroke>> _keyStrokeObservers = new CopyOnWriteArrayList<>();
    private volatile boolean _running = true;
    private volatile Future<EventExecutionResult> _currentFuture;

    public static EventThread getInstance() {
        var instance = _instance;
        if (instance == null) {
            synchronized (EventThread.class) {
                instance = _instance;
                if (instance == null) {
                    instance = new EventThread();
                    _instance = instance;
                }
            }
        }
        return instance;
    }

    public EventThread() {
        super("swim-event-thread");
        setDaemon(true);
        _responder = new ListEventResponder();
        _eventExecutor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "event-thread-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void shutdownInstance() {
        EventThread instance = _instance;
        if (instance == null) {
            return;
        }
        instance.shutdown();
        if (Thread.currentThread() != instance) {
            try {
                instance.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        _instance = null;
    }

    @Override
    public void run() {
        ArrayList<KeyStroke> events = new ArrayList<>();
        ArrayDeque<Event> deferred = new ArrayDeque<>();
        while (_running) {
            try {
                Event event = waitForNextEvent(deferred);
                if (!_running || event == null) {
                    break;
                }
                processEvent(event, events, deferred);
                while (_running && (event = pollNextEvent(deferred)) != null) {
                    processEvent(event, events, deferred);
                }
                runPostEventHooks();
            } catch (Throwable e) {
                _log.warn("Fatal error in event loop: {}", errorSummary(e));
                _log.debug("Fatal error in event loop", e);
            }
        }
    }

    private Event waitForNextEvent(ArrayDeque<Event> deferred) {
        Event deferredEvent = deferred.pollFirst();
        if (deferredEvent != null) {
            return deferredEvent;
        }
        while (_running) {
            try {
                Event event = _events.poll(1, TimeUnit.SECONDS);
                _log.debug("Poked event");
                if (event != null) {
                    return event;
                }
            } catch (InterruptedException e) {
            }
        }
        return null;
    }

    private Event pollNextEvent(ArrayDeque<Event> deferred) {
        Event deferredEvent = deferred.pollFirst();
        if (deferredEvent != null) {
            return deferredEvent;
        }
        return _events.poll();
    }

    private void processEvent(Event event, ArrayList<KeyStroke> events, ArrayDeque<Event> deferred) {
        Future<EventExecutionResult> future = _eventExecutor.submit(() -> executeEvent(event, events));
        _currentFuture = future;
        boolean cancelled = false;
        try {
            while (_running) {
                try {
                    EventExecutionResult result = future.get(FUTURE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    result.apply(events);
                    return;
                } catch (TimeoutException e) {
                    Event queued = _events.poll();
                    if (queued == null) {
                        continue;
                    }
                    if (isCancelEvent(queued)) {
                        _log.debug("Cancelling in-flight event future");
                        future.cancel(true);
                        events.clear();
                        cancelled = true;
                        break;
                    }
                    deferred.addLast(queued);
                } catch (CancellationException e) {
                    cancelled = true;
                    events.clear();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (future.isCancelled() || isCancellationThrowable(cause)) {
                        cancelled = true;
                        events.clear();
                        break;
                    }
                    _log.error("Error processing event: ", cause);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (_running) {
                        future.cancel(true);
                    }
                    cancelled = true;
                    events.clear();
                    break;
                }
            }
        } finally {
            _currentFuture = null;
            if (!_running) {
                future.cancel(true);
            } else if (cancelled) {
                drainCancelEvents(deferred);
            }
        }
    }

    private EventExecutionResult executeEvent(Event event, ArrayList<KeyStroke> events) {
        if (event instanceof KeyStrokeEvent keyEvent) {
            _log.debug("Received key stroke event");
            if (isForceRedrawEvent(keyEvent.getKeyStroke())) {
                var window = Window.getInstance();
                if (window != null) {
                    window.forceRedraw();
                }
                notifyKeyObservers(keyEvent.getKeyStroke());
                return ignored -> ignored.clear();
            }
            if (keyEvent.getKeyStroke() instanceof MouseAction mouseAction) {
                return executeMouseEvent(mouseAction);
            }
            var updated = new ArrayList<KeyStroke>(events);
            updated.add(keyEvent.getKeyStroke());
            var keys = new KeyStrokes(updated);
            switch (_responder.processEvent(keys)) {
            case MAYBE:
                _log.debug("Maybe");
                notifyKeyObservers(keyEvent.getKeyStroke());
                return ignored -> {
                    ignored.clear();
                    ignored.addAll(updated);
                };
            case YES:
                _log.debug("Yes");
                _responder.respond();
                notifyKeyObservers(keyEvent.getKeyStroke());
                return ignored -> ignored.clear();
            case NO:
            default:
                _log.debug("No/Clear");
                notifyKeyObservers(keyEvent.getKeyStroke());
                return ignored -> ignored.clear();
            }
        }
        if (event instanceof RunnableEvent runnableEvent) {
            _log.debug("Received runnable event");
            runnableEvent.execute();
        }
        return ignored -> {
        };
    }

    private EventExecutionResult executeMouseEvent(MouseAction mouseAction) {
        var keys = new KeyStrokes(List.of(mouseAction));
        switch (_responder.processEvent(keys)) {
        case YES:
            _responder.respond();
            break;
        case MAYBE:
        case NO:
        default:
            break;
        }
        return List::clear;
    }

    private static boolean isCancelEvent(Event event) {
        if (!(event instanceof KeyStrokeEvent keyStrokeEvent)) {
            return false;
        }
        KeyStroke stroke = keyStrokeEvent.getKeyStroke();
        return stroke != null
                && stroke.getKeyType() == com.googlecode.lanterna.input.KeyType.Character
                && stroke.isCtrlDown()
                && (stroke.getCharacter() == 'q' || stroke.getCharacter() == 'Q');
    }

    private static boolean isForceRedrawEvent(KeyStroke stroke) {
        return stroke != null
                && stroke.getKeyType() == com.googlecode.lanterna.input.KeyType.Character
                && stroke.isCtrlDown()
                && (stroke.getCharacter() == 'l' || stroke.getCharacter() == 'L');
    }

    private void drainCancelEvents(ArrayDeque<Event> deferred) {
        deferred.removeIf(EventThread::isCancelEvent);
    }

    private static boolean isCancellationThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void runPostEventHooks() {
        _log.debug("Run post-event hooks");
        for (Runnable runnable : _onEventRunnables) {
            try {
                runnable.run();
            } catch (Throwable e) {
                _log.warn("Error processing post-event hook: {}", errorSummary(e));
                _log.debug("Error processing post-event hook", e);
            }
        }
        _log.debug("Ran post-event hooks");
    }

    public void enqueue(Event event) {
        while (_running) {
            try {
                if (_events.offer(event, 1, TimeUnit.SECONDS)) {
                    _log.debug("Sent event");
                    return;
                }
                if (getState() == State.TERMINATED) {
                    _log.warn("Dropping event because event thread has terminated");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public ListEventResponder getResponder() {
        return _responder;
    }

    public void addOnEvent(Runnable runnable) {
        _onEventRunnables.add(runnable);
    }

    public void addKeyStrokeObserver(Consumer<KeyStroke> observer) {
        _keyStrokeObservers.add(observer);
    }

    public void shutdown() {
        _running = false;
        _onEventRunnables.clear();
        _keyStrokeObservers.clear();
        Future<EventExecutionResult> currentFuture = _currentFuture;
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
        _eventExecutor.shutdownNow();
        _events.offer(new RunnableEvent(() -> {
        }));
    }

    @FunctionalInterface
    private interface EventExecutionResult {
        void apply(ArrayList<KeyStroke> events);
    }

    private void notifyKeyObservers(KeyStroke keyStroke) {
        for (var observer : _keyStrokeObservers) {
            try {
                observer.accept(keyStroke);
            } catch (Exception e) {
                _log.error("Error in key stroke observer", e);
            }
        }
    }

    private static String errorSummary(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}
