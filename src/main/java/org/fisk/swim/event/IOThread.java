package org.fisk.swim.event;

import java.io.IOException;

import org.fisk.swim.EventThread;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

public class IOThread extends Thread {
    private Screen _screen;

    public IOThread(Screen screen) {
        _screen = screen;
    }

    @Override
    public void run() {
        while (true) {
            try {
                KeyStroke keyStroke = _screen.readInput();
                var event = new KeyStrokeEvent(keyStroke);
                EventThread.getInstance().enqueue(event);
            } catch (IOException e) {
            }
        }
    }
}
