package org.fisk.swim.event;

import java.io.IOException;

import org.fisk.swim.EventThread;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

public class IOThread extends Thread {
    private Screen _screen;

    public IOThread(Screen screen) {
        setDaemon(true);
        _screen = screen;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                KeyStroke keyStroke = _screen.pollInput();
                if (keyStroke == null) {
                    Thread.sleep(10);
                    continue;
                }
                var event = new KeyStrokeEvent(keyStroke);
                EventThread.getInstance().enqueue(event);
            } catch (InterruptedException e) {
                interrupt();
                break;
            } catch (IOException | RuntimeException e) {
                break;
            }
        }
    }
}
