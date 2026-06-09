package org.fisk.swim.event;

import java.util.List;

public interface KeyBindingHintProvider {
    default String keyHintContext() {
        return null;
    }

    List<KeyBindingHint> keyBindingHints();
}
