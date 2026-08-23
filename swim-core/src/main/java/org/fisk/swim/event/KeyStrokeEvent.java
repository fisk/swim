package org.fisk.swim.event;

import org.fisk.swim.event.KeyStroke;

public class KeyStrokeEvent extends Event {
    private KeyStroke _keyStroke;

    public KeyStrokeEvent(KeyStroke keyStroke) {
        _keyStroke = keyStroke;
    }

    public KeyStroke getKeyStroke() {
        return _keyStroke;
    }
}
