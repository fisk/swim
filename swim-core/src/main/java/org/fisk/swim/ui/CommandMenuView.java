package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public class CommandMenuView extends View {
    private static final int MIN_WIDTH = 28;
    private static final int MIN_BODY_ROWS = 1;
    private static final int MIN_DETAIL_WIDTH = 16;

    private CommandView.CommandMenuState _state = CommandView.CommandMenuState.hidden();

    public CommandMenuView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
    }

    public void setState(CommandView.CommandMenuState state) {
        _state = state == null ? CommandView.CommandMenuState.hidden() : state;
        syncBounds();
        setNeedsRedraw();
    }

    CommandView.CommandMenuState getState() {
        return _state;
    }

    void syncBounds() {
        Size parentSize = getParent() == null ? getBounds().getSize() : getParent().getBounds().getSize();
        setBounds(calculateBounds(parentSize));
    }

    @Override
    public void resize(Size newParentSize) {
        setBounds(calculateBounds(newParentSize));
    }

    @Override
    public void draw(Rect rect) {
        if (!_state.visible()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        UiTheme.drawLine(graphics, rect.getPoint(), rect.getSize().getWidth(), headerLine(),
                UiTheme.TEXT_MUTED, UiTheme.COMMAND_PROMPT);
        if (rect.getSize().getHeight() <= 1) {
            return;
        }

        List<CommandView.CommandSpec> visibleMatches = visibleMatches();
        if (visibleMatches.isEmpty()) {
            UiTheme.drawLine(graphics,
                    Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1),
                    rect.getSize().getWidth(),
                    AttributedString.create(" no matching commands", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ELEVATED),
                    UiTheme.TEXT_MUTED,
                    UiTheme.SURFACE_ELEVATED);
            return;
        }

        int startIndex = visibleStartIndex();
        int contentWidth = rect.getSize().getWidth();
        int labelWidth = preferredLabelWidth(contentWidth, visibleMatches);
        for (int i = 0; i < visibleMatches.size(); ++i) {
            boolean selected = startIndex + i == _state.selection();
            drawMatchRow(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i),
                    contentWidth, labelWidth, visibleMatches.get(i), selected);
        }
    }

    private AttributedString headerLine() {
        String menuTitle = _state.title() == null || _state.title().isBlank() ? "command matches" : _state.title();
        String title = _state.matches().isEmpty()
                ? " " + menuTitle + " 0"
                : " " + menuTitle + " " + (_state.selection() + 1) + "/" + _state.matches().size();
        if (!_state.prefix().isBlank()) {
            title += "  for " + _state.prefix();
        }
        return AttributedString.create(title, UiTheme.TEXT_ON_ACCENT, UiTheme.COMMAND_PROMPT);
    }

    private void drawMatchRow(
            Point point,
            int width,
            int labelWidth,
            CommandView.CommandSpec match,
            boolean selected) {
        var row = new AttributedString();
        var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.SURFACE_ELEVATED;
        var foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
        var detailColor = selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_MUTED;

        row.append(" " + UiTheme.padRight(match.label(), labelWidth), foreground, background);
        row.append("  " + UiTheme.fit(match.detail(), Math.max(0, width - labelWidth - 3)), detailColor, background);
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(), point, width, row, foreground, background);
    }

    private List<CommandView.CommandSpec> visibleMatches() {
        int availableRows = Math.max(0, getBounds().getSize().getHeight() - 1);
        if (availableRows <= 0 || _state.matches().isEmpty()) {
            return List.of();
        }
        int start = visibleStartIndex();
        int end = Math.min(_state.matches().size(), start + availableRows);
        return _state.matches().subList(start, end);
    }

    private int visibleStartIndex() {
        if (_state.matches().isEmpty()) {
            return 0;
        }
        int availableRows = Math.max(1, getBounds().getSize().getHeight() - 1);
        int selection = Math.max(0, Math.min(_state.selection(), _state.matches().size() - 1));
        int maxStart = Math.max(0, _state.matches().size() - availableRows);
        return Math.max(0, Math.min(selection - availableRows + 1, maxStart));
    }

    private Rect calculateBounds(Size parentSize) {
        if (!_state.visible()) {
            return Rect.create(0, 0, 0, 0);
        }

        int width = Math.max(MIN_WIDTH, parentSize.getWidth());
        int maxBodyRows = Math.max(0, parentSize.getHeight() - 1);
        int desiredBodyRows = preferredBodyRows(width, maxBodyRows);
        int contentRows = Math.min(desiredBodyRows, maxBodyRows);
        int height = Math.min(parentSize.getHeight(), 1 + contentRows);
        int x = 0;
        int y = Math.max(0, parentSize.getHeight() - 1 - height);
        return Rect.create(x, y, width, height);
    }

    private int preferredBodyRows(int width, int maxBodyRows) {
        if (_state.matches().isEmpty()) {
            return MIN_BODY_ROWS;
        }
        int desiredBodyRows = Math.min(CommandView.MAX_VISIBLE_COMMANDS, _state.matches().size());
        if (maxBodyRows <= desiredBodyRows) {
            return desiredBodyRows;
        }

        int labelWidth = preferredLabelWidth(width, _state.matches());
        int detailWidth = Math.max(1, width - labelWidth - 3);
        if (detailWidth >= MIN_DETAIL_WIDTH) {
            return desiredBodyRows;
        }

        int totalRows = 0;
        for (var match : _state.matches()) {
            totalRows += rowsNeeded(width, labelWidth, match);
            if (totalRows >= maxBodyRows) {
                return maxBodyRows;
            }
        }
        return Math.max(desiredBodyRows, totalRows);
    }

    private int rowsNeeded(int width, int labelWidth, CommandView.CommandSpec match) {
        int detailWidth = Math.max(1, width - labelWidth - 3);
        int detailLength = match.detail().length();
        int wrappedDetailRows = Math.max(1, (detailLength + detailWidth - 1) / detailWidth);
        return Math.max(1, wrappedDetailRows);
    }

    private int preferredLabelWidth(int contentWidth, List<CommandView.CommandSpec> visibleMatches) {
        int labelWidth = 0;
        for (var match : visibleMatches) {
            labelWidth = Math.max(labelWidth, match.label().length());
        }
        return Math.min(Math.max(12, labelWidth), Math.max(12, contentWidth / 3));
    }
}
