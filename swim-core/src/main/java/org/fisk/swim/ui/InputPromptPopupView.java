package org.fisk.swim.ui;

import java.util.List;
import java.util.function.Consumer;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.input.KeyType;

public class InputPromptPopupView extends View implements KeyBindingHintProvider {
    private static final int MIN_WIDTH = 36;
    private static final int MAX_WIDTH = 96;

    private static final class PromptCursor extends Cursor {
        private final InputPromptPopupView _owner;

        private PromptCursor(InputPromptPopupView owner) {
            super(null);
            _owner = owner;
        }

        @Override
        public int getXOnScreen() {
            return _owner.cursorScreenPosition().getX();
        }

        @Override
        public int getYOnScreen() {
            return _owner.cursorScreenPosition().getY();
        }

        @Override
        public TerminalCursorShape getShape() {
            return TerminalCursorShape.BAR;
        }
    }

    private final ListEventResponder _responders = new ListEventResponder();
    private final String _title;
    private final String _label;
    private final StringBuilder _value;
    private final PromptCursor _cursor;
    private Consumer<String> _onSubmit = ignored -> {
    };
    private Runnable _onCancel = () -> {
    };

    public InputPromptPopupView(Rect bounds, String title, String label, String initialValue) {
        super(bounds);
        _title = title == null || title.isBlank() ? "Input" : title;
        _label = label == null || label.isBlank() ? "value" : label;
        _value = new StringBuilder(initialValue == null ? "" : initialValue);
        _cursor = new PromptCursor(this);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        _responders.addEventResponder("<ENTER>", "Prompt", "submit", this::submit);
        _responders.addEventResponder("<ESC>", "Prompt", "cancel", this::cancel);
        _responders.addEventResponder("<BACKSPACE>", "Prompt", "delete character", () -> {
            allowEditorDriveAction("edit lsp prompt");
            if (_value.length() > 0) {
                _value.deleteCharAt(_value.length() - 1);
                setNeedsRedraw();
            }
        });
        _responders.addKeyBindingHint("<CHAR>", "Prompt", "type text");
        _responders.addEventResponder(new EventResponder() {
            private char _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                var event = events.current();
                if (event.getKeyType() == KeyType.Character) {
                    _character = event.getCharacter();
                    return Response.YES;
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                allowEditorDriveAction("edit lsp prompt");
                _value.append(_character);
                setNeedsRedraw();
            }
        });
    }

    public String getTitle() {
        return _title;
    }

    public String getValue() {
        return _value.toString();
    }

    public void setOnSubmit(Consumer<String> onSubmit) {
        _onSubmit = onSubmit == null ? ignored -> {
        } : onSubmit;
    }

    public void setOnCancel(Runnable onCancel) {
        _onCancel = onCancel == null ? () -> {
        } : onCancel;
    }

    @Override
    public String keyHintContext() {
        return "prompt";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _responders.keyBindingHints();
    }

    public void syncBounds() {
        Size parentSize = getParent() == null ? getBounds().getSize() : getParent().getBounds().getSize();
        setBounds(calculateBounds(parentSize));
    }

    @Override
    public void resize(Size newParentSize) {
        setBounds(calculateBounds(newParentSize));
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public Cursor getCursor() {
        return _cursor;
    }

    @Override
    public void draw(Rect rect) {
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();

        var header = new AttributedString();
        header.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var input = new AttributedString();
        input.append(" " + _label + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        input.append(" " + UiTheme.fit(_value.toString(), Math.max(0, width - input.length() - 1)),
                UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_BACKGROUND);
        UiTheme.drawLine(graphics, Point.create(x, y + 1), width, input, UiTheme.TEXT_MUTED,
                UiTheme.COMMAND_BACKGROUND);

        var footer = new AttributedString();
        footer.append(" enter ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GREEN);
        footer.append(" submit  ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        footer.append(" esc ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_RED);
        footer.append(" cancel", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(x, y + 2), width, footer, UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private void submit() {
        allowEditorDriveAction("submit lsp prompt");
        _onSubmit.accept(_value.toString());
    }

    private void cancel() {
        allowEditorDriveAction("cancel lsp prompt");
        _onCancel.run();
    }

    private Point cursorScreenPosition() {
        syncBounds();
        Point origin = absoluteOrigin();
        int width = Math.max(1, getBounds().getSize().getWidth());
        int prefixLength = _label.length() + 3;
        int x = Math.min(width - 1, prefixLength + _value.length());
        return Point.create(origin.getX() + Math.max(0, x), origin.getY() + 1);
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (View parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    private Rect calculateBounds(Size parentSize) {
        int width = Math.min(parentSize.getWidth(), Math.max(MIN_WIDTH,
                Math.min(MAX_WIDTH, Math.max(_title.length() + 8, _label.length() + _value.length() + 8))));
        int height = Math.min(parentSize.getHeight(), 3);
        int x = Math.max(0, (parentSize.getWidth() - width) / 2);
        int y = Math.max(0, parentSize.getHeight() / 3);
        return Rect.create(x, y, width, height);
    }

    private static void allowEditorDriveAction(String action) {
        if (Window.getInstance() != null) {
            Window.getInstance().allowEditorDriveAction(action);
        }
    }
}
