package org.fisk.swim.event;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.Window;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class FancyJumpResponder implements EventResponder, KeyBindingHintProvider {
    private static final String HINT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public enum TargetKind {
        WORD_START,
        CHARACTER
    }

    private final BufferContext _bufferContext;
    private final TextEventResponder _prefixResponder;
    private final TargetKind _targetKind;
    private final String _prefix;

    private List<HintTarget> _activeTargets = List.of();
    private String _typedHint = "";
    private HintTarget _selectedTarget;

    public FancyJumpResponder(BufferContext bufferContext, String prefix) {
        this(bufferContext, prefix, TargetKind.WORD_START);
    }

    public FancyJumpResponder(BufferContext bufferContext, String prefix, TargetKind targetKind) {
        _bufferContext = bufferContext;
        _prefix = prefix;
        _prefixResponder = new TextEventResponder(prefix, () -> {
        });
        _targetKind = targetKind;
    }

    private record HintTarget(int position, String label) {
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        clearState();
        var result = _prefixResponder.processEvent(events);
        if (result != Response.YES) {
            return result;
        }
        if (events.consumed()) {
            return Response.MAYBE;
        }

        var searchKey = events.current();
        if (searchKey.getKeyType() != KeyType.Character) {
            return Response.NO;
        }

        var targets = findVisibleTargets(searchKey.getCharacter());
        if (targets.isEmpty()) {
            return Response.NO;
        }

        events.consume(1);
        if (targets.size() == 1) {
            _selectedTarget = targets.get(0);
            _activeTargets = List.of();
            _typedHint = "";
            return Response.YES;
        }

        _activeTargets = targets;
        _typedHint = "";

        while (!events.consumed()) {
            var hintKey = events.current();
            if (hintKey.getKeyType() != KeyType.Character) {
                clearState();
                return Response.NO;
            }

            _typedHint += hintKey.getCharacter();
            targets = filterTargetsByPrefix(targets, _typedHint);
            if (targets.isEmpty()) {
                clearState();
                return Response.NO;
            }

            _activeTargets = targets;
            events.consume(1);
            if (targets.size() == 1) {
                _selectedTarget = targets.get(0);
                _activeTargets = List.of();
                _typedHint = "";
                return Response.YES;
            }
        }

        return Response.MAYBE;
    }

    @Override
    public void respond() {
        if (_selectedTarget == null) {
            return;
        }
        if (Window.getInstance() != null) {
            Window.getInstance().allowEditorDriveAction("fancy jump");
        }
        _bufferContext.getBuffer().getCursor().setPosition(_selectedTarget.position());
        _bufferContext.getBufferView().adaptViewToCursor();
        clearState();
    }

    public AttributedString decorate(Glyph glyph, AttributedString character) {
        if (_activeTargets.isEmpty()) {
            return character;
        }
        for (var target : _activeTargets) {
            if (target.position() == glyph.getPosition()) {
                int hintIndex = Math.min(_typedHint.length(), target.label().length() - 1);
                return AttributedString.create(target.label().substring(hintIndex, hintIndex + 1),
                        TextColor.ANSI.RED, TextColor.ANSI.DEFAULT);
            }
        }
        return character;
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return List.of(KeyBindingHint.of(_prefix + " <CHAR>",
                _targetKind == TargetKind.CHARACTER ? "Navigation" : "Navigation",
                _targetKind == TargetKind.CHARACTER ? "jump to visible character" : "jump to visible word start"));
    }

    private void clearState() {
        _activeTargets = List.of();
        _typedHint = "";
        _selectedTarget = null;
    }

    private List<HintTarget> findVisibleTargets(char searchCharacter) {
        var positions = new ArrayList<Integer>();
        _bufferContext.getTextLayout().getGlyphs().forEach(glyph -> {
            if (matchesTarget(glyph, searchCharacter)) {
                positions.add(glyph.getPosition());
            }
        });

        int labelWidth = requiredLabelWidth(positions.size());
        var targets = new ArrayList<HintTarget>(positions.size());
        for (int i = 0; i < positions.size(); ++i) {
            targets.add(new HintTarget(positions.get(i), encodeLabel(i, labelWidth)));
        }
        return targets;
    }

    private boolean matchesTarget(Glyph glyph, char searchCharacter) {
        if (glyph.getCharacter().length() != 1 || glyph.getCharacter().charAt(0) != searchCharacter) {
            return false;
        }
        return switch (_targetKind) {
        case WORD_START -> isWordStart(glyph.getPosition());
        case CHARACTER -> true;
        };
    }

    private boolean isWordStart(int position) {
        var text = _bufferContext.getBuffer().getString();
        if (position < 0 || position >= text.length()) {
            return false;
        }
        char current = text.charAt(position);
        if (!isWordCharacter(current)) {
            return false;
        }
        if (position == 0) {
            return true;
        }
        return !isWordCharacter(text.charAt(position - 1));
    }

    private boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private int requiredLabelWidth(int count) {
        if (count <= 1) {
            return 1;
        }
        int digits = 1;
        long capacity = HINT_ALPHABET.length();
        while (count > capacity) {
            digits++;
            capacity *= HINT_ALPHABET.length();
        }
        return digits;
    }

    private String encodeLabel(int number, int width) {
        int radix = HINT_ALPHABET.length();
        char[] label = new char[width];
        for (int i = width - 1; i >= 0; --i) {
            label[i] = HINT_ALPHABET.charAt(number % radix);
            number /= radix;
        }
        return new String(label);
    }

    private List<HintTarget> filterTargetsByPrefix(List<HintTarget> targets, String prefix) {
        var filtered = new ArrayList<HintTarget>();
        for (var target : targets) {
            if (target.label().startsWith(prefix)) {
                filtered.add(target);
            }
        }
        return filtered;
    }
}
