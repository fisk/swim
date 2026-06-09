package org.fisk.swim.event;

import java.util.List;

import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.input.KeyType;

public class MotionResponder implements EventResponder, KeyBindingHintProvider {
    private static final Logger _log = LogFactory.createLog();
    
    private String _motion;
    private EventResponder _prefixResponder;
    private EventResponder _motionResponder;
    private final KeyBindingHint _hint;
    
    private Responder _delegate;
    
    public static interface Responder {
        void respond(int count);
    }

    private StringBuffer _prefix = new StringBuffer();
    
    private EventResponder getInitialResponder() {
        return new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                _prefix = new StringBuffer();
                boolean consumedDigit = false;
                for (;;) {
                    var event = events.current();
                    if (event.getKeyType() != KeyType.Character) {
                        return Response.YES;
                    }
                    char character = event.getCharacter();
                    if (!consumedDigit && (character < '1' || character > '9')) {
                        return Response.YES;
                    }
                    if (consumedDigit && (character < '0' || character > '9')) {
                        return Response.YES;
                    }
                    
                    consumedDigit = true;
                    _prefix.append(Character.toString(character));
                    
                    if (!events.hasNext()) {
                        events.consume(1);
                        return Response.YES;
                    } else {
                        events.consume(1);
                    }
                }
            }

            @Override
            public void respond() {
            }
        };
    }

    public MotionResponder(String motion, Responder responder) {
        this(motion, null, null, responder);
    }

    public MotionResponder(String motion, String group, String summary, Responder responder) {
        _motion = motion;
        _prefixResponder = getInitialResponder();
        _motionResponder = new TextEventResponder(_motion, () -> {});
        _delegate = responder;
        _hint = group == null || group.isBlank() || summary == null || summary.isBlank()
                ? null
                : KeyBindingHint.of(motion, group, summary);
    }

    public MotionResponder(String motion, String group, String summary, String commandName, Responder responder) {
        _motion = motion;
        _prefixResponder = getInitialResponder();
        _motionResponder = new TextEventResponder(_motion, () -> {});
        _delegate = responder;
        _hint = group == null || group.isBlank() || summary == null || summary.isBlank()
                ? null
                : KeyBindingHint.of(motion, group, summary, commandName);
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        _prefixResponder.processEvent(events);
        if (events.consumed()) {
            return Response.MAYBE;
        }
        return _motionResponder.processEvent(events);
    }

    @Override
    public void respond() {
        var prefixStr = _prefix.toString();
        if (prefixStr.equals("")) {
            _delegate.respond(1);
        } else {
            _delegate.respond(Integer.parseInt(prefixStr));
        }
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _hint == null ? List.of() : List.of(_hint);
    }
}
