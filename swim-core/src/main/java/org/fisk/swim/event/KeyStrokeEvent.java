package org.fisk.swim.event;



public class KeyStrokeEvent extends Event {
    private KeyStroke _keyStroke;

    public KeyStrokeEvent(KeyStroke keyStroke) {
        _keyStroke = keyStroke;
    }

    public KeyStroke getKeyStroke() {
        return _keyStroke;
    }
}
