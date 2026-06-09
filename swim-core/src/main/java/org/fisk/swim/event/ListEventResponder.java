package org.fisk.swim.event;

import java.util.ArrayList;
import java.util.List;

public class ListEventResponder implements EventResponder, KeyBindingHintProvider {
    public static final class Layer {
        private final List<EventResponder> _responders = new ArrayList<EventResponder>();

        public void addEventResponder(EventResponder responder) {
            _responders.add(responder);
        }

        public void addEventResponder(String pattern, Runnable runnable) {
            _responders.add(new TextEventResponder(pattern, runnable));
        }

        public void addEventResponder(String pattern, String group, String summary, Runnable runnable) {
            addEventResponder(pattern, group, summary, "", runnable);
        }

        public void addEventResponder(String pattern, String group, String summary, String commandName,
                Runnable runnable) {
            _responders.add(new TextEventResponder(pattern, runnable,
                    KeyBindingHint.of(pattern, group, summary, commandName)));
        }

        public void addKeyBindingHint(String pattern, String group, String summary) {
            _responders.add(new HintOnlyResponder(KeyBindingHint.of(pattern, group, summary)));
        }

        public List<KeyBindingHint> keyBindingHints() {
            var hints = new ArrayList<KeyBindingHint>();
            for (var responder : _responders) {
                if (responder instanceof KeyBindingHintProvider provider) {
                    hints.addAll(provider.keyBindingHints());
                }
            }
            return List.copyOf(hints);
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

    public void addEventResponder(String pattern, String group, String summary, Runnable runnable) {
        _baseLayer.addEventResponder(pattern, group, summary, runnable);
    }

    public void addEventResponder(String pattern, String group, String summary, String commandName, Runnable runnable) {
        _baseLayer.addEventResponder(pattern, group, summary, commandName, runnable);
    }

    public void addKeyBindingHint(String pattern, String group, String summary) {
        _baseLayer.addKeyBindingHint(pattern, group, summary);
    }

    public void removeEventResponder(EventResponder responder) {
        _baseLayer.removeEventResponder(responder);
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        var hints = new ArrayList<KeyBindingHint>();
        for (var layer : _layers) {
            hints.addAll(layer.keyBindingHints());
        }
        return List.copyOf(hints);
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

    private record HintOnlyResponder(KeyBindingHint hint) implements EventResponder, KeyBindingHintProvider {
        @Override
        public Response processEvent(KeyStrokes events) {
            return Response.NO;
        }

        @Override
        public void respond() {
        }

        @Override
        public List<KeyBindingHint> keyBindingHints() {
            return List.of(hint);
        }
    }
}
