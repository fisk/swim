package org.fisk.swim.event;

import com.googlecode.lanterna.input.KeyStroke;

public class KeyStrokeEvent extends Event {
    private KeyStroke _keyStroke;

    public KeyStrokeEvent(KeyStroke keyStroke) {
        _keyStroke = keyStroke;
    }

    public KeyStroke getKeyStroke() {
        return _keyStroke;
    }
}
