package org.fisk.swim.ui;

import java.util.function.Consumer;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class TodoQuickCaptureView extends View {
    private static final int MIN_WIDTH = 36;
    private static final int MAX_WIDTH = 76;
    private static final int HEIGHT = 5;

    private final StringBuilder _title = new StringBuilder();
    private Runnable _onCancel = () -> {
    };
    private Consumer<String> _onSubmit = ignored -> {
    };
    private Runnable _pendingAction;
    private String _message = "";

    public TodoQuickCaptureView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
    }

    public void setOnCancel(Runnable onCancel) {
        _onCancel = onCancel == null ? () -> {
        } : onCancel;
    }

    public void setOnSubmit(Consumer<String> onSubmit) {
        _onSubmit = onSubmit == null ? ignored -> {
        } : onSubmit;
    }

    public String getTitle() {
        return "Todo Capture";
    }

    public String getValue() {
        return _title.toString();
    }

    public String getMessage() {
        return _message;
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
        _pendingAction = null;
        if (events.remaining() != 0) {
            return Response.NO;
        }
        _pendingAction = actionFor(events.current());
        return _pendingAction == null ? Response.NO : Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingAction != null) {
            _pendingAction.run();
            _pendingAction = null;
        }
    }

    @Override
    public void draw(Rect rect) {
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int height = rect.getSize().getHeight();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();
        if (width <= 0 || height <= 0) {
            return;
        }

        if (height >= 1) {
            var header = new AttributedString();
            header.append(" Todo ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
            header.append("Inbox", UiTheme.ACCENT_GOLD, UiTheme.SURFACE_ACCENT);
            UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        }

        if (height >= 2) {
            UiTheme.fillRow(graphics, Point.create(x, y + 1), width, UiTheme.SURFACE_ELEVATED);
        }

        if (height >= 3) {
            var input = new AttributedString();
            input.append(" > ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_MUTED);
            input.append(UiTheme.fit(_title.toString(), Math.max(0, width - 4)), UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
            UiTheme.drawLine(graphics, Point.create(x, y + 2), width, input, UiTheme.TEXT_SUBTLE, UiTheme.SURFACE_MUTED);
        }

        if (height >= 4) {
            UiTheme.fillRow(graphics, Point.create(x, y + 3), width, UiTheme.SURFACE_ELEVATED);
        }

        if (height >= 5) {
            var footer = new AttributedString();
            String message = _message == null || _message.isBlank() ? "Enter add  Esc cancel" : _message;
            footer.append(" " + UiTheme.fit(message, Math.max(0, width - 2)), footerColour(), UiTheme.SURFACE_ELEVATED);
            UiTheme.drawLine(graphics, Point.create(x, y + 4), width, footer, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ELEVATED);
        }
    }

    private Runnable actionFor(KeyStroke event) {
        if (event.getKeyType() == KeyType.Character && event.isCtrlDown() && matchesCtrl(event, 't')) {
            return () -> {
            };
        }
        if (event.getKeyType() == KeyType.Enter) {
            return this::submit;
        }
        if (event.getKeyType() == KeyType.Escape) {
            return _onCancel;
        }
        if (event.getKeyType() == KeyType.Backspace) {
            return () -> {
                if (!_title.isEmpty()) {
                    _title.delete(_title.length() - 1, _title.length());
                    _message = "";
                    setNeedsRedraw();
                }
            };
        }
        if (event.getKeyType() == KeyType.Character && !event.isCtrlDown() && !event.isAltDown()) {
            Character character = event.getCharacter();
            if (character == null) {
                return null;
            }
            return () -> {
                _title.append(character);
                _message = "";
                setNeedsRedraw();
            };
        }
        return null;
    }

    private void submit() {
        String value = _title.toString().trim();
        if (value.isBlank()) {
            _message = "Todo title cannot be empty";
            setNeedsRedraw();
            return;
        }
        _onSubmit.accept(value);
    }

    private com.googlecode.lanterna.TextColor footerColour() {
        return _message == null || _message.isBlank() ? UiTheme.TEXT_MUTED : UiTheme.ACCENT_GOLD;
    }

    private static Rect calculateBounds(Size parentSize) {
        int parentWidth = parentSize == null ? MIN_WIDTH : parentSize.getWidth();
        int parentHeight = parentSize == null ? HEIGHT : parentSize.getHeight();
        int width = Math.max(1, Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, parentWidth - 8)));
        width = Math.min(width, Math.max(1, parentWidth));
        int height = Math.min(HEIGHT, Math.max(1, parentHeight));
        int x = Math.max(0, (parentWidth - width) / 2);
        int y = Math.max(0, (parentHeight - height) / 2);
        return Rect.create(x, y, width, height);
    }

    private static boolean matchesCtrl(KeyStroke event, char character) {
        Character actual = event.getCharacter();
        if (actual == null) {
            return false;
        }
        char lower = Character.toLowerCase(character);
        return Character.toLowerCase(actual) == lower || actual == (char) (lower - 'a' + 1);
    }
}
