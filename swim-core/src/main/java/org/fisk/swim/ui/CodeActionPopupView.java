package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.DiagnosticAction;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public class CodeActionPopupView extends View {
    private static final int MIN_WIDTH = 32;
    private static final int MAX_WIDTH = 96;
    private static final int MAX_VISIBLE_ROWS = 8;

    private final ListEventResponder _responders = new ListEventResponder();
    private List<DiagnosticAction> _actions = List.of();
    private String _title = "Code Actions";
    private int _selection;
    private int _scrollOffset;
    private Point _anchor = Point.create(0, 0);
    private Runnable _onClose = () -> {
    };

    public CodeActionPopupView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        _responders.addEventResponder("j", () -> moveSelection(1));
        _responders.addEventResponder("k", () -> moveSelection(-1));
        _responders.addEventResponder("<DOWN>", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", () -> moveSelection(-1));
        _responders.addEventResponder("<ENTER>", this::acceptSelection);
        _responders.addEventResponder("<ESC>", () -> {
            allowEditorDriveAction("close code actions");
            _onClose.run();
        });
    }

    public void configure(List<DiagnosticAction> actions, Point anchor, String title) {
        _actions = actions == null ? List.of() : List.copyOf(actions);
        _anchor = anchor == null ? Point.create(0, 0) : anchor;
        _title = title == null || title.isBlank() ? "Code Actions" : title;
        _selection = Math.max(0, Math.min(_selection, Math.max(0, _actions.size() - 1)));
        _scrollOffset = 0;
        syncBounds();
        setNeedsRedraw();
    }

    public void setOnClose(Runnable onClose) {
        _onClose = onClose == null ? () -> {
        } : onClose;
    }

    public List<DiagnosticAction> getActions() {
        return _actions;
    }

    public String getTitle() {
        return _title;
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
        if (_actions.isEmpty()) {
            return Response.NO;
        }
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public void draw(Rect rect) {
        if (_actions.isEmpty()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();

        int visibleRows = Math.min(MAX_VISIBLE_ROWS, _actions.size());
        ensureSelectionVisible(visibleRows);

        var header = new AttributedString();
        header.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + (_selection + 1) + "/" + _actions.size() + " ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        header.append(" enter apply  esc close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int rowY = y + 1;
        for (int i = 0; i < visibleRows; ++i) {
            int index = _scrollOffset + i;
            if (index >= _actions.size()) {
                break;
            }
            boolean selected = index == _selection;
            var action = _actions.get(index);
            var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : (i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED);
            UiTheme.fillRow(graphics, Point.create(x, rowY + i), width, background);
            var line = new AttributedString();
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE, background);
            line.append(clip(action.title(), Math.max(0, width - 2)),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(x, rowY + i), width, line, UiTheme.TEXT_MUTED, background);
        }

        var footer = new AttributedString();
        footer.append(" detail ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        var selected = _actions.isEmpty() ? null : _actions.get(_selection);
        footer.append(selected == null ? "" : " " + clip(selected.detail(), Math.max(0, width - 10)),
                UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(x, y + rect.getSize().getHeight() - 1), width, footer,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private void moveSelection(int delta) {
        allowEditorDriveAction("code action selection");
        if (_actions.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_actions.size() - 1, _selection + delta));
        setNeedsRedraw();
    }

    private void acceptSelection() {
        allowEditorDriveAction("apply code action");
        if (_actions.isEmpty()) {
            return;
        }
        _actions.get(_selection).apply().run();
        _onClose.run();
    }

    private static void allowEditorDriveAction(String action) {
        if (Window.getInstance() != null) {
            Window.getInstance().allowEditorDriveAction(action);
        }
    }

    private void ensureSelectionVisible(int visibleRows) {
        if (_selection < _scrollOffset) {
            _scrollOffset = _selection;
        } else if (_selection >= _scrollOffset + visibleRows) {
            _scrollOffset = _selection - visibleRows + 1;
        }
    }

    private Rect calculateBounds(Size parentSize) {
        if (_actions.isEmpty()) {
            return Rect.create(0, 0, 0, 0);
        }
        int width = Math.min(parentSize.getWidth(), preferredWidth());
        int height = Math.min(parentSize.getHeight(), Math.min(MAX_VISIBLE_ROWS, _actions.size()) + 2);
        int x = Math.max(0, Math.min(_anchor.getX(), parentSize.getWidth() - width));
        int belowY = _anchor.getY() + 1;
        int y = belowY + height <= parentSize.getHeight()
                ? belowY
                : Math.max(0, _anchor.getY() - height + 1);
        return Rect.create(x, y, width, height);
    }

    private int preferredWidth() {
        int width = MIN_WIDTH;
        for (var action : _actions) {
            width = Math.max(width, 4 + action.title().length());
            width = Math.max(width, 10 + action.detail().length());
        }
        return Math.min(MAX_WIDTH, width);
    }

    private static String clip(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        return UiTheme.fit(text, width);
    }
}
