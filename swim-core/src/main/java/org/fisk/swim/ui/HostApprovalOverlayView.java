package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.terminal.TerminalContext;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class HostApprovalOverlayView extends View {
    public record Entry(String id, String title, String toolName, String reason, String summary, boolean persistable) {
    }

    public record Decision(String id, boolean approved, boolean persist) {
    }

    private final List<Entry> _entries = new ArrayList<>();
    private Consumer<Decision> _onDecision = ignored -> {};
    private int _selectedIndex;
    private int _scrollIndex;

    public HostApprovalOverlayView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
    }

    public void setEntries(List<Entry> entries) {
        _entries.clear();
        if (entries != null) {
            _entries.addAll(entries);
        }
        _selectedIndex = Math.max(0, Math.min(_selectedIndex, Math.max(0, _entries.size() - 1)));
        ensureSelectionVisible();
        syncBounds();
        setNeedsRedraw();
    }

    public void setOnDecision(Consumer<Decision> onDecision) {
        _onDecision = onDecision == null ? ignored -> {} : onDecision;
    }

    public boolean hasEntries() {
        return !_entries.isEmpty();
    }

    public void syncBounds() {
        View parent = getParent();
        if (parent == null) {
            return;
        }
        Size size = parent.getBounds().getSize();
        int width = Math.min(Math.max(52, size.getWidth() * 2 / 3), Math.max(1, size.getWidth() - 4));
        int rowsPerEntry = 3;
        int height = Math.min(Math.max(7, 4 + _entries.size() * rowsPerEntry), Math.max(1, size.getHeight() - 4));
        int x = Math.max(0, (size.getWidth() - width) / 2);
        int y = Math.max(0, (size.getHeight() - height) / 2);
        setBounds(Rect.create(x, y, width, height));
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0 || _entries.isEmpty()) {
            return Response.NO;
        }
        KeyStroke key = events.current();
        if (key.getKeyType() == KeyType.ArrowUp) {
            _selectedIndex = Math.max(0, _selectedIndex - 1);
            ensureSelectionVisible();
            setNeedsRedraw();
            return Response.YES;
        }
        if (key.getKeyType() == KeyType.ArrowDown) {
            _selectedIndex = Math.min(_entries.size() - 1, _selectedIndex + 1);
            ensureSelectionVisible();
            setNeedsRedraw();
            return Response.YES;
        }
        if (key.getKeyType() == KeyType.Enter) {
            return decide(true, false);
        }
        if (key.getKeyType() == KeyType.Escape) {
            return decide(false, false);
        }
        if (key.getKeyType() == KeyType.Character) {
            char c = Character.toLowerCase(key.getCharacter());
            if (c == 'a') {
                Entry entry = selectedEntry();
                return decide(true, entry != null && entry.persistable());
            }
            if (c == 'd' || c == 'n') {
                return decide(false, false);
            }
        }
        return Response.NO;
    }

    private Response decide(boolean approved, boolean persist) {
        Entry entry = selectedEntry();
        if (entry == null) {
            return Response.NO;
        }
        _onDecision.accept(new Decision(entry.id(), approved, persist && entry.persistable()));
        return Response.YES;
    }

    private Entry selectedEntry() {
        if (_entries.isEmpty()) {
            return null;
        }
        return _entries.get(Math.max(0, Math.min(_selectedIndex, _entries.size() - 1)));
    }

    private void ensureSelectionVisible() {
        int visibleEntries = visibleEntryCount();
        if (_selectedIndex < _scrollIndex) {
            _scrollIndex = _selectedIndex;
        } else if (_selectedIndex >= _scrollIndex + visibleEntries) {
            _scrollIndex = Math.max(0, _selectedIndex - visibleEntries + 1);
        }
        int maxScroll = Math.max(0, _entries.size() - visibleEntries);
        _scrollIndex = Math.max(0, Math.min(_scrollIndex, maxScroll));
    }

    private int visibleEntryCount() {
        int height = getBounds() == null ? 7 : getBounds().getSize().getHeight();
        return Math.max(1, Math.max(0, height - 4) / 3);
    }

    @Override
    public void respond() {
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        if (width <= 0 || rect.getSize().getHeight() <= 0) {
            return;
        }
        UiTheme.drawLine(graphics, rect.getPoint(), width,
                AttributedString.create(" Host approval ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width,
                AttributedString.create(" Nemo cannot see or control this overlay.", UiTheme.TEXT_MUTED,
                        UiTheme.SURFACE_ELEVATED),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ELEVATED);
        if (_entries.isEmpty()) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 3), width,
                    AttributedString.create(" No pending approvals.", UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ELEVATED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_ELEVATED);
            return;
        }
        int row = 3;
        ensureSelectionVisible();
        for (int i = _scrollIndex; i < _entries.size() && row < rect.getSize().getHeight() - 1; i++) {
            Entry entry = _entries.get(i);
            boolean selected = i == _selectedIndex;
            TextColor foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row++), width,
                    AttributedString.create(" " + UiTheme.fit(entry.id() + " | " + entry.title(), Math.max(1, width - 2)),
                            foreground, background),
                    foreground, background);
            if (row >= rect.getSize().getHeight() - 1) {
                break;
            }
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row++), width,
                    AttributedString.create(" " + UiTheme.fit(entry.toolName() + " | " + entry.reason(), Math.max(1, width - 2)),
                            UiTheme.TEXT_MUTED, background),
                    UiTheme.TEXT_MUTED, background);
            if (row >= rect.getSize().getHeight() - 1) {
                break;
            }
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row++), width,
                    AttributedString.create(" " + UiTheme.fit(entry.summary(), Math.max(1, width - 2)),
                            UiTheme.TEXT_MUTED, background),
                    UiTheme.TEXT_MUTED, background);
        }
        Entry selected = selectedEntry();
        String footer = selected != null && selected.persistable()
                ? " Enter approve once  A approve always  D/Esc stop "
                : " Enter approve once  D/Esc stop ";
        UiTheme.drawLine(graphics,
                Point.create(rect.getPoint().getX(), rect.getPoint().getY() + rect.getSize().getHeight() - 1),
                width,
                AttributedString.create(footer, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
    }
}
