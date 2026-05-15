package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.java.JavaDefinitionMenuSession;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public class JavaDefinitionPopupView extends View {
    private static final int MIN_WIDTH = 28;
    private static final int MAX_WIDTH = 88;

    private final ListEventResponder _responders = new ListEventResponder();

    private JavaDefinitionMenuSession _session;
    private Runnable _onAccept = () -> {
    };
    private Runnable _onCancel = () -> {
    };

    public JavaDefinitionPopupView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        _responders.addEventResponder("j", () -> moveSelection(1));
        _responders.addEventResponder("k", () -> moveSelection(-1));
        _responders.addEventResponder("<DOWN>", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", () -> moveSelection(-1));
        _responders.addEventResponder("<ENTER>", () -> _onAccept.run());
        _responders.addEventResponder("<ESC>", () -> _onCancel.run());
    }

    public String getTitle() {
        return "Definitions";
    }

    public void setSession(JavaDefinitionMenuSession session) {
        _session = session;
        syncBounds();
        setNeedsRedraw();
    }

    public JavaDefinitionMenuSession getSession() {
        return _session;
    }

    public void setOnAccept(Runnable onAccept) {
        _onAccept = onAccept == null ? () -> {
        } : onAccept;
    }

    public void setOnCancel(Runnable onCancel) {
        _onCancel = onCancel == null ? () -> {
        } : onCancel;
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
        if (_session == null || _session.isEmpty()) {
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
        if (_session == null || _session.isEmpty()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var session = _session;
        int visibleRows = Math.min(JavaDefinitionMenuSession.DEFAULT_VISIBLE_ROWS, session.size());
        session.ensureSelectionVisible(visibleRows);
        List<JavaDefinitionMenuSession.Entry> visible = session.visibleEntries(visibleRows);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();

        var header = new AttributedString();
        header.append(" Definitions ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + (session.getSelection() + 1) + "/" + session.size() + " ",
                UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        header.append(" enter jump  esc cancel ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int rowY = y + 1;
        int firstIndex = session.getScrollOffset();
        for (int i = 0; i < visible.size(); ++i) {
            boolean selected = firstIndex + i == session.getSelection();
            var entry = visible.get(i);
            var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : (i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED);
            UiTheme.fillRow(graphics, Point.create(x, rowY + i), width, background);
            var line = new AttributedString();
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                    background);
            line.append(clip(entry.label(), Math.max(0, width - 2)),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(x, rowY + i), width, line, UiTheme.TEXT_MUTED, background);
        }

        int footerY = y + rect.getSize().getHeight() - 1;
        var selected = session.getSelectedEntry();
        var footer = new AttributedString();
        footer.append(" target ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        footer.append(selected == null ? "" : " " + clip(selected.detail(), Math.max(0, width - 8)),
                UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(x, footerY), width, footer, UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private void moveSelection(int delta) {
        if (_session == null) {
            return;
        }
        _session.moveSelection(delta);
        setNeedsRedraw();
    }

    private Rect calculateBounds(Size parentSize) {
        if (_session == null || _session.isEmpty()) {
            return Rect.create(0, 0, 0, 0);
        }

        int width = Math.min(parentSize.getWidth(), preferredWidth());
        int visibleRows = Math.min(JavaDefinitionMenuSession.DEFAULT_VISIBLE_ROWS, _session.size());
        int height = Math.min(parentSize.getHeight(), visibleRows + 2);

        var cursor = _session.getBufferContext().getBuffer().getCursor();
        int anchorX = cursor.getXOnScreen();
        int anchorY = cursor.getYOnScreen();

        int x = Math.max(0, Math.min(anchorX, parentSize.getWidth() - width));
        int belowY = anchorY + 1;
        int y = belowY + height <= parentSize.getHeight()
                ? belowY
                : Math.max(0, anchorY - height + 1);
        return Rect.create(x, y, width, height);
    }

    private int preferredWidth() {
        int width = MIN_WIDTH;
        for (var entry : _session.getEntries()) {
            width = Math.max(width, 4 + entry.label().length());
            width = Math.max(width, 8 + entry.detail().length());
        }
        return Math.min(MAX_WIDTH, width);
    }

    private static String clip(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width == 1) {
            return text.substring(0, 1);
        }
        return text.substring(0, width - 1) + ">";
    }
}
