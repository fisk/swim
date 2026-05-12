package org.fisk.swim.event;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class ListEventResponder implements EventResponder {
    public static final class Layer {
        private final List<EventResponder> _responders = new ArrayList<EventResponder>();

        public void addEventResponder(EventResponder responder) {
            _responders.add(responder);
        }

        public void addEventResponder(String pattern, Runnable runnable) {
            _responders.add(new TextEventResponder(pattern, runnable));
        }

        public void removeEventResponder(EventResponder responder) {
            _responders.remove(responder);
        }
    }

    private EventResponder _responder;
    private final Layer _baseLayer = new Layer();
    private final List<Layer> _layers = new ArrayList<Layer>();

    public ListEventResponder() {
        _layers.add(_baseLayer);
    }

    public Layer addLayer() {
        var layer = new Layer();
        _layers.add(0, layer);
        return layer;
    }

    public void addEventResponder(EventResponder responder) {
        _baseLayer.addEventResponder(responder);
    }

    public void addEventResponder(String pattern, Runnable runnable) {
        _baseLayer.addEventResponder(pattern, runnable);
    }

    public void removeEventResponder(EventResponder responder) {
        _baseLayer.removeEventResponder(responder);
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        for (var layer : _layers) {
            var response = processLayer(layer, events);
            if (response == Response.MAYBE) {
                return Response.MAYBE;
            }
            if (response == Response.YES) {
                return Response.YES;
            }
        }
        return Response.NO;
    }

    private Response processLayer(Layer layer, KeyStrokes events) {
        boolean maybe = false;
        EventResponder yes = null;
        for (var responder : layer._responders) {
            var response = responder.processEvent(new KeyStrokes(events));
            if (response == Response.MAYBE) {
                maybe = true;
            }
            if (response == Response.YES) {
                yes = responder;
            }
        }
        if (maybe) {
            return Response.MAYBE;
        } else if (yes != null) {
            _responder = yes;
            return Response.YES;
        } else {
            return Response.NO;
        }
    }

    @Override
    public void respond() {
        _responder.respond();
        _responder = null;
    }
}
