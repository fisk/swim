package org.fisk.swim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.fisk.swim.event.Event;
import org.fisk.swim.event.KeyStrokeEvent;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.input.KeyStroke;

public class EventThread extends Thread {
    private static Logger _log = LogFactory.createLog();

    private final ListEventResponder _responder;
    private final ArrayBlockingQueue<Event> _events = new ArrayBlockingQueue<>(1024, true);
    private static volatile EventThread _instance;
    private final List<Runnable> _onEventRunnables = new CopyOnWriteArrayList<>();
    private volatile boolean _running = true;

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
        setDaemon(true);
        _responder = new ListEventResponder();
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
        while (_running) {
            Event event = null;
            while (_running) {
                try {
                    event = _events.poll(1, TimeUnit.SECONDS);
                    _log.info("Poked event");
                    if (event != null) {
                        break;
                    }
                } catch (InterruptedException e) {}
            }
            if (!_running) {
                break;
            }
            if (event instanceof KeyStrokeEvent) {
                try {
                    _log.info("Received key stroke event");
                    var keyEvent = (KeyStrokeEvent) event;
                    events.add(keyEvent.getKeyStroke());
                    var keys = new KeyStrokes(events);
                    switch (_responder.processEvent(keys)) {
                    case MAYBE:
                        _log.info("Maybe");
                        break;
                    case YES:
                        _log.info("Yes");
                        _responder.respond();
                    case NO:
                        _log.info("No/Clear");
                        events.clear();
                        break;
                    }
                } catch (Exception e) {
                    _log.error("Error processing event: ", e);
                }
            } else if (event instanceof RunnableEvent) {
                _log.info("Received runnable event");
                var runnableEvent = (RunnableEvent) event;
                try {
                    runnableEvent.execute();
                } catch (Exception e) {
                    _log.error("Error processing event: ", e);
                }
            }
            _log.info("Run post-event hooks");
            for (Runnable runnable: _onEventRunnables) {
                try {
                    runnable.run();
                } catch (Exception e) {
                    _log.error("Error processing event: ", e);
                }
            }
            _log.info("Ran post-event hooks");
        }
    }

    public void enqueue(Event event) {
        while (true) {
            try {
                if (_events.offer(event, 1, TimeUnit.SECONDS)) {
                    _log.info("Sent event");
                    return;
                }
            } catch (InterruptedException e) {}
        }
    }

    public ListEventResponder getResponder() {
        return _responder;
    }

    public void addOnEvent(Runnable runnable) {
        _onEventRunnables.add(runnable);
    }

    public void shutdown() {
        _running = false;
        _onEventRunnables.clear();
        _events.offer(new RunnableEvent(() -> {
        }));
    }
}
