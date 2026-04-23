package org.fisk.swim.event;

public interface EventListener {
    Response processEvent(KeyStrokes events);
}
