package org.fisk.swim.ui;

import java.util.List;
import java.util.Objects;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;

import com.googlecode.lanterna.TextColor;

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
        var line = new AttributedString();
        if (width <= 0) {
            return line;
        }
        for (int i = 0; i < _tabs.size(); i++) {
            if (line.length() >= width) {
                break;
            }
            Tab tab = _tabs.get(i);
            TextColor nextBackground = i + 1 < _tabs.size() ? tabBackground(_tabs.get(i + 1)) : _backgroundColour;
            appendTab(line, tab, nextBackground);
        }
        return line;
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        UiTheme.drawLine(terminalContext.getGraphics(), rect.getPoint(), rect.getSize().getWidth(),
                buildLine(rect.getSize().getWidth()), UiTheme.TEXT_MUTED, _backgroundColour);
    }

    private void appendTab(AttributedString line, Tab tab, TextColor nextBackground) {
        TextColor background = tabBackground(tab);
        TextColor foreground = tab.active() ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_PRIMARY;
        String text = " " + tab.index() + ":" + tab.label() + " ";
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
