package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public class CommandMenuView extends View {
    private static final int MIN_WIDTH = 28;
    private static final int MIN_BODY_ROWS = 1;
    private static final int MIN_DETAIL_WIDTH = 16;

    private CommandView.CommandMenuState _state = CommandView.CommandMenuState.hidden();
    private int _bottomInsetRows;

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

    void setBottomInsetRows(int bottomInsetRows) {
        int next = Math.max(0, bottomInsetRows);
        if (_bottomInsetRows == next) {
            return;
        }
        _bottomInsetRows = next;
        syncBounds();
        setNeedsRedraw();
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
        if (rect.getSize().getWidth() <= 0 || rect.getSize().getHeight() <= 0) {
            return;
        }
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        List<AttributedString> headerLines = headerLines(rect.getSize().getWidth());
        for (int i = 0; i < headerLines.size() && i < rect.getSize().getHeight(); i++) {
            UiTheme.drawLine(graphics,
                    Point.create(rect.getPoint().getX(), rect.getPoint().getY() + i),
                    rect.getSize().getWidth(),
                    headerLines.get(i),
                    UiTheme.TEXT_MUTED,
                    UiTheme.COMMAND_PROMPT);
        }
        int bodyStart = headerLines.size();
        if (rect.getSize().getHeight() <= bodyStart) {
            return;
        }

        List<CommandView.CommandSpec> visibleMatches = visibleMatches();
        if (visibleMatches.isEmpty()) {
            UiTheme.drawLine(graphics,
                    Point.create(rect.getPoint().getX(), rect.getPoint().getY() + bodyStart),
                    rect.getSize().getWidth(),
                    AttributedString.create(" no matching commands", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ELEVATED),
                    UiTheme.TEXT_MUTED,
                    UiTheme.SURFACE_ELEVATED);
            return;
        }

        int startIndex = visibleStartIndex();
        int contentWidth = rect.getSize().getWidth();
        int labelWidth = preferredLabelWidth(contentWidth, visibleMatches);
        int shortcutWidth = preferredShortcutWidth(visibleMatches);
        for (int i = 0; i < visibleMatches.size(); ++i) {
            boolean selected = startIndex + i == _state.selection();
            drawMatchRow(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + bodyStart + i),
                    contentWidth, labelWidth, shortcutWidth, visibleMatches.get(i), selected);
        }
    }

    private List<AttributedString> headerLines(int width) {
        var lines = new ArrayList<AttributedString>();
        for (String line : headerTextLines(width)) {
            lines.add(AttributedString.create(line, UiTheme.TEXT_ON_ACCENT, UiTheme.COMMAND_PROMPT));
        }
        return lines;
    }

    private List<String> headerTextLines(int width) {
        String menuTitle = _state.title() == null || _state.title().isBlank() ? "command matches" : _state.title();
        String[] titleLines = menuTitle.split("\\R", -1);
        String firstTitle = titleLines.length == 0 || titleLines[0].isBlank() ? "command matches" : titleLines[0];
        String title = _state.matches().isEmpty()
                ? " " + firstTitle + " 0"
                : " " + firstTitle + " " + (_state.selection() + 1) + "/" + _state.matches().size();
        if (!_state.prefix().isBlank()) {
            title += "  for " + _state.prefix();
        }
        var lines = new ArrayList<String>();
        addWrappedHeaderLine(lines, title, width);
        for (int i = 1; i < titleLines.length; i++) {
            addWrappedHeaderLine(lines, " " + titleLines[i], width);
        }
        return lines.isEmpty() ? List.of(" command matches") : lines;
    }

    private static void addWrappedHeaderLine(List<String> lines, String line, int width) {
        int maxWidth = Math.max(1, width);
        String remaining = line == null ? "" : line;
        while (remaining.length() > maxWidth) {
            int split = remaining.lastIndexOf(' ', maxWidth);
            if (split <= 0) {
                split = maxWidth;
            }
            lines.add(remaining.substring(0, split));
            remaining = " " + remaining.substring(Math.min(split + 1, remaining.length())).stripLeading();
        }
        lines.add(remaining);
    }

    private void drawMatchRow(
            Point point,
            int width,
            int labelWidth,
            int shortcutWidth,
            CommandView.CommandSpec match,
            boolean selected) {
        AttributedString row = matchRow(width, labelWidth, shortcutWidth, match, selected);
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(), point, width, row,
                selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                selected ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.SURFACE_ELEVATED);
    }

    AttributedString buildMatchRowForTest(int width, CommandView.CommandSpec match, boolean selected) {
        return matchRow(width, preferredLabelWidth(width, List.of(match)), preferredShortcutWidth(List.of(match)),
                match, selected);
    }

    private AttributedString matchRow(
            int width,
            int labelWidth,
            int shortcutWidth,
            CommandView.CommandSpec match,
            boolean selected) {
        var row = new AttributedString();
        var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.SURFACE_ELEVATED;
        var foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
        var detailColor = selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_MUTED;
        var shortcutColor = selected ? UiTheme.TEXT_ON_ACCENT : UiTheme.ACCENT_BLUE;

        row.append(" " + UiTheme.padRight(match.label(), labelWidth), foreground, background);
        int shortcutSegmentWidth = shortcutWidth > 0 ? shortcutWidth + 2 : 0;
        int detailWidth = Math.max(0, width - row.length() - shortcutSegmentWidth);
        if (detailWidth > 0) {
            row.append(UiTheme.padRight("  " + match.detail(), detailWidth), detailColor, background);
        }
        if (shortcutWidth > 0) {
            row.append("  " + leftPad(match.shortcutLabel(), shortcutWidth), shortcutColor, background);
        }
        return row;
    }

    private List<CommandView.CommandSpec> visibleMatches() {
        int availableRows = Math.max(0, getBounds().getSize().getHeight()
                - headerTextLines(getBounds().getSize().getWidth()).size());
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
        int availableRows = Math.max(1, getBounds().getSize().getHeight()
                - headerTextLines(getBounds().getSize().getWidth()).size());
        int selection = Math.max(0, Math.min(_state.selection(), _state.matches().size() - 1));
        int maxStart = Math.max(0, _state.matches().size() - availableRows);
        return Math.max(0, Math.min(selection - availableRows + 1, maxStart));
    }

    private Rect calculateBounds(Size parentSize) {
        if (!_state.visible()) {
            return Rect.create(0, 0, 0, 0);
        }

        int width = Math.max(MIN_WIDTH, parentSize.getWidth());
        int availableHeight = Math.max(0, parentSize.getHeight() - _bottomInsetRows);
        if (availableHeight == 0) {
            return Rect.create(0, 0, width, 0);
        }
        int headerRows = headerTextLines(width).size();
        int maxBodyRows = Math.max(0, availableHeight - headerRows);
        int desiredBodyRows = preferredBodyRows(width, maxBodyRows);
        int contentRows = Math.min(desiredBodyRows, maxBodyRows);
        int height = Math.min(availableHeight, headerRows + contentRows);
        int x = 0;
        int y = Math.max(0, availableHeight - height);
        return Rect.create(x, y, width, height);
    }

    private int preferredBodyRows(int width, int maxBodyRows) {
        if (_state.matches().isEmpty()) {
            return MIN_BODY_ROWS;
        }
        int desiredBodyRows = Math.min(CommandView.MAX_VISIBLE_COMMANDS, _state.matches().size());
        if (_state.matches().size() > CommandView.MAX_VISIBLE_COMMANDS || maxBodyRows <= desiredBodyRows) {
            return desiredBodyRows;
        }

        int labelWidth = preferredLabelWidth(width, _state.matches());
        int shortcutWidth = preferredShortcutWidth(_state.matches());
        int detailWidth = Math.max(1, width - labelWidth - 3 - (shortcutWidth > 0 ? shortcutWidth + 2 : 0));
        if (detailWidth >= MIN_DETAIL_WIDTH) {
            return desiredBodyRows;
        }

        int totalRows = 0;
        for (var match : _state.matches()) {
            totalRows += rowsNeeded(width, labelWidth, shortcutWidth, match);
            if (totalRows >= maxBodyRows) {
                return maxBodyRows;
            }
        }
        return Math.max(desiredBodyRows, totalRows);
    }

    private int rowsNeeded(int width, int labelWidth, int shortcutWidth, CommandView.CommandSpec match) {
        int detailWidth = Math.max(1, width - labelWidth - 3 - (shortcutWidth > 0 ? shortcutWidth + 2 : 0));
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

    private int preferredShortcutWidth(List<CommandView.CommandSpec> visibleMatches) {
        int shortcutWidth = 0;
        for (var match : visibleMatches) {
            String shortcut = match.shortcutLabel();
            if (shortcut != null && !shortcut.isBlank()) {
                shortcutWidth = Math.max(shortcutWidth, shortcut.length());
            }
        }
        return shortcutWidth;
    }

    private static String leftPad(String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() >= width) {
            return UiTheme.fit(value, width);
        }
        return " ".repeat(width - value.length()) + value;
    }
}
