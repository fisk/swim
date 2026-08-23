package org.fisk.swim.event;

import java.util.function.Supplier;

import org.fisk.swim.EventThread;

public class IOThread extends Thread {
    private final Supplier<KeyStroke> _input;

    public IOThread(Supplier<KeyStroke> input) {
        setDaemon(true);
        _input = input;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                KeyStroke keyStroke = _input == null ? null : _input.get();
                if (keyStroke == null) {
                    Thread.sleep(10);
                    continue;
                }
                var event = new KeyStrokeEvent(keyStroke);
                EventThread.getInstance().enqueue(event);
            } catch (InterruptedException e) {
                interrupt();
                break;
            } catch (RuntimeException e) {
                break;
            }
        }
    }
}
