package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.DiagnosticEntry;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public class DiagnosticPopupView extends View {
    private static final int MIN_WIDTH = 36;
    private static final int MAX_WIDTH = 104;
    private static final int MAX_VISIBLE_ROWS = 8;

    private final ListEventResponder _responders = new ListEventResponder();

    private List<DiagnosticEntry> _entries = List.of();
    private String _title = "Diagnostics";
    private int _selection;
    private int _scrollOffset;
    private Point _anchor = Point.create(0, 0);
    private boolean _interactive;
    private Runnable _onClose = () -> {
    };
    private Runnable _onActions = () -> {
    };

    public DiagnosticPopupView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        _responders.addEventResponder("j", () -> moveSelection(1));
        _responders.addEventResponder("k", () -> moveSelection(-1));
        _responders.addEventResponder("<DOWN>", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", () -> moveSelection(-1));
        _responders.addEventResponder("a", () -> {
            allowEditorDriveAction("diagnostic actions");
            _onActions.run();
        });
        _responders.addEventResponder("<ENTER>", () -> {
            allowEditorDriveAction("diagnostic actions");
            _onActions.run();
        });
        _responders.addEventResponder("<ESC>", () -> {
            allowEditorDriveAction("close diagnostics");
            _onClose.run();
        });
    }

    public void configure(List<DiagnosticEntry> entries, Point anchor, String title, boolean interactive) {
        _entries = entries == null ? List.of() : List.copyOf(entries);
        _anchor = anchor == null ? Point.create(0, 0) : anchor;
        _title = title == null || title.isBlank() ? "Diagnostics" : title;
        _interactive = interactive;
        _selection = Math.max(0, Math.min(_selection, Math.max(0, _entries.size() - 1)));
        _scrollOffset = 0;
        syncBounds();
        setNeedsRedraw();
    }

    public void setOnClose(Runnable onClose) {
        _onClose = onClose == null ? () -> {
        } : onClose;
    }

    public void setOnActions(Runnable onActions) {
        _onActions = onActions == null ? () -> {
        } : onActions;
    }

    public List<DiagnosticEntry> getEntries() {
        return _entries;
    }

    public DiagnosticEntry getSelectedEntry() {
        if (_entries.isEmpty() || _selection < 0 || _selection >= _entries.size()) {
            return null;
        }
        return _entries.get(_selection);
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
        if (!_interactive || _entries.isEmpty()) {
            return Response.NO;
        }
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        if (_interactive) {
            _responders.respond();
        }
    }

    @Override
    public void draw(Rect rect) {
        if (_entries.isEmpty()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();

        int visibleRows = Math.min(MAX_VISIBLE_ROWS, _entries.size());
        ensureSelectionVisible(visibleRows);

        var header = new AttributedString();
        header.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + (_selection + 1) + "/" + _entries.size() + " ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        if (_interactive) {
            header.append(" a actions  esc close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        }
        UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        List<DiagnosticEntry> visible = new ArrayList<>();
        for (int i = 0; i < visibleRows; ++i) {
            int index = _scrollOffset + i;
            if (index >= _entries.size()) {
                break;
            }
            visible.add(_entries.get(index));
        }

        int rowY = y + 1;
        for (int i = 0; i < visible.size(); ++i) {
            int index = _scrollOffset + i;
            boolean selected = index == _selection;
            var entry = visible.get(i);
            var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : (i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED);
            UiTheme.fillRow(graphics, Point.create(x, rowY + i), width, background);
            var line = new AttributedString();
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE, background);
            line.append(" " + severityLabel(entry) + " ", UiTheme.TEXT_ON_ACCENT, severityColor(entry));
            line.append(" " + clip(entry.message().replace('\n', ' '), Math.max(0, width - 8)),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(x, rowY + i), width, line, UiTheme.TEXT_MUTED, background);
        }

        var footer = new AttributedString();
        footer.append(" detail ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        var selected = getSelectedEntry();
        footer.append(selected == null ? "" : " " + clip(selected.detail(), Math.max(0, width - 10)),
                UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(x, y + rect.getSize().getHeight() - 1), width, footer,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private void moveSelection(int delta) {
        allowEditorDriveAction("diagnostic selection");
        if (_entries.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_entries.size() - 1, _selection + delta));
        setNeedsRedraw();
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
        if (_entries.isEmpty()) {
            return Rect.create(0, 0, 0, 0);
        }
        int width = Math.min(parentSize.getWidth(), preferredWidth());
        int height = Math.min(parentSize.getHeight(), Math.min(MAX_VISIBLE_ROWS, _entries.size()) + 2);
        int x = Math.max(0, Math.min(_anchor.getX(), parentSize.getWidth() - width));
        int belowY = _anchor.getY() + 1;
        int y = belowY + height <= parentSize.getHeight()
                ? belowY
                : Math.max(0, _anchor.getY() - height + 1);
        return Rect.create(x, y, width, height);
    }

    private int preferredWidth() {
        int width = MIN_WIDTH;
        for (var entry : _entries) {
            width = Math.max(width, 10 + entry.message().length());
            width = Math.max(width, 10 + entry.detail().length());
        }
        return Math.min(MAX_WIDTH, width);
    }

    private static String severityLabel(DiagnosticEntry entry) {
        if (DiagnosticSeverity.Error.equals(entry.severity())) {
            return "ERR";
        }
        if (DiagnosticSeverity.Warning.equals(entry.severity())) {
            return "WRN";
        }
        return "INF";
    }

    private static com.googlecode.lanterna.TextColor severityColor(DiagnosticEntry entry) {
        if (DiagnosticSeverity.Error.equals(entry.severity())) {
            return UiTheme.ACCENT_RED;
        }
        if (DiagnosticSeverity.Warning.equals(entry.severity())) {
            return UiTheme.ACCENT_GOLD;
        }
        return UiTheme.ACCENT_BLUE;
    }

    private static String clip(String text, int width) {
        if (text == null || width <= 0) {
            return "";
        }
        return UiTheme.fit(text, width);
    }
}
