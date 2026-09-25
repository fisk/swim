package org.fisk.swim.ui;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;

import org.fisk.swim.terminal.TextColor;

public class TabBarView extends View {
    public record Tab(int index, String label, boolean active, Runnable onClick) {
        public Tab {
            label = label == null || label.isBlank() ? "(untitled)" : label;
        }
    }

    private List<Tab> _tabs = List.of();

    public TabBarView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.MODELINE_BACKGROUND);
    }

    public void setTabs(List<Tab> tabs) {
        var next = tabs == null ? List.<Tab>of() : List.copyOf(tabs);
        if (sameTabs(_tabs, next)) {
            return;
        }
        _tabs = next;
        setNeedsRedraw();
    }

    AttributedString buildLine(int width) {
        var lines = buildLines(width);
        return lines.isEmpty() ? new AttributedString() : lines.getFirst();
    }

    int preferredHeight(int width) {
        return Math.max(1, buildLines(width).size());
    }

    List<AttributedString> buildLines(int width) {
        var lines = new ArrayList<AttributedString>();
        if (width <= 0) {
            return lines;
        }
        var row = new ArrayList<Tab>();
        int used = 0;
        for (Tab tab : _tabs) {
            int length = Math.min(width, tabText(tab, width).length() + 1);
            if (!row.isEmpty() && used + length > width) {
                lines.add(buildRow(row, width));
                row.clear();
                used = 0;
            }
            row.add(tab);
            used += length;
        }
        if (!row.isEmpty()) {
            lines.add(buildRow(row, width));
        }
        return lines;
    }

    private AttributedString buildRow(List<Tab> tabs, int width) {
        var line = new AttributedString();
        for (int i = 0; i < tabs.size(); i++) {
            TextColor next = i + 1 < tabs.size() ? tabBackground(tabs.get(i + 1)) : _backgroundColour;
            appendTab(line, tabs.get(i), next, width);
        }
        return line;
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var lines = buildLines(rect.getSize().getWidth());
        int first = Math.max(0, activeRow(rect.getSize().getWidth()) - rect.getSize().getHeight() + 1);
        for (int row = 0; row < rect.getSize().getHeight(); row++) {
            UiTheme.drawLine(terminalContext.getTerminalGraphics(),
                    Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row), rect.getSize().getWidth(),
                    first + row < lines.size() ? lines.get(first + row) : new AttributedString(), UiTheme.TEXT_MUTED, _backgroundColour);
        }
    }

    private int activeRow(int width) {
        int row = 0;
        int used = 0;
        for (Tab tab : _tabs) {
            int length = Math.min(width, tabText(tab, width).length() + 1);
            if (used > 0 && used + length > width) {
                row++;
                used = 0;
            }
            if (tab.active()) {
                return row;
            }
            used += length;
        }
        return 0;
    }

    private static String tabText(Tab tab, int width) {
        String text = " " + tab.index() + ":" + tab.label() + " ";
        int available = Math.max(0, width - 1);
        return text.length() <= available ? text
                : available <= 1 ? "…".substring(0, available) : text.substring(0, available - 1) + "…";
    }

    private void appendTab(AttributedString line, Tab tab, TextColor nextBackground, int width) {
        TextColor background = tabBackground(tab);
        TextColor foreground = tab.active() ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_PRIMARY;
        String text = tabText(tab, width);
        int start = line.length();
        line.append(text, foreground, background);
        int end = line.length();
        if (tab.onClick() != null) {
            line.onClick(start, end, ignored -> tab.onClick().run());
        }
        line.append(Powerline.SYMBOL_FILLED_RIGHT_ARROW, background, nextBackground);
    }

    private static TextColor tabBackground(Tab tab) {
        return tab.active() ? UiTheme.ACCENT_BLUE : UiTheme.SURFACE_ACCENT;
    }

    private static boolean sameTabs(List<Tab> left, List<Tab> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Tab a = left.get(i);
            Tab b = right.get(i);
            if (a.index() != b.index()
                    || a.active() != b.active()
                    || !Objects.equals(a.label(), b.label())) {
                return false;
            }
        }
        return true;
    }
}
