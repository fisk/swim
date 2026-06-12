package org.fisk.swim.ui;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

final class WindowChromeLayout {
    enum FooterBar {
        MODE_LINE(1, 0),
        COMMAND(0, 1),
        TAB_BAR(2, 2);

        private final int _priority;
        private final int _displayOrder;

        FooterBar(int priority, int displayOrder) {
            _priority = priority;
            _displayOrder = displayOrder;
        }
    }

    private final Rect _root;
    private final Rect _topMenu;
    private final Rect _workspace;
    private final EnumMap<FooterBar, Rect> _footerBounds;
    private final int _footerInsetRows;

    private WindowChromeLayout(
            Rect root,
            Rect topMenu,
            Rect workspace,
            EnumMap<FooterBar, Rect> footerBounds,
            int footerInsetRows) {
        _root = root;
        _topMenu = topMenu;
        _workspace = workspace;
        _footerBounds = footerBounds;
        _footerInsetRows = footerInsetRows;
    }

    static WindowChromeLayout compute(Size size, int topMenuHeight, Set<FooterBar> requestedFooterBars) {
        int width = size == null ? 0 : Math.max(0, size.getWidth());
        int height = size == null ? 0 : Math.max(0, size.getHeight());
        int menuHeight = Math.min(Math.max(0, topMenuHeight), height);
        Set<FooterBar> requested = requestedFooterBars == null || requestedFooterBars.isEmpty()
                ? EnumSet.noneOf(FooterBar.class)
                : EnumSet.copyOf(requestedFooterBars);
        int availableFooterRows = Math.min(requested.size(), Math.max(0, height - menuHeight));
        Set<FooterBar> visibleFooterBars = visibleFooterBars(requested, availableFooterRows);
        int contentTop = menuHeight;
        int contentHeight = Math.max(0, height - menuHeight - visibleFooterBars.size());
        var footerBounds = new EnumMap<FooterBar, Rect>(FooterBar.class);
        for (var bar : FooterBar.values()) {
            footerBounds.put(bar, Rect.create(0, contentTop + contentHeight, width, 0));
        }

        int y = contentTop + contentHeight;
        for (var bar : FooterBar.values()) {
            if (visibleFooterBars.contains(bar)) {
                footerBounds.put(bar, Rect.create(0, y, width, 1));
                y++;
            } else {
                footerBounds.put(bar, Rect.create(0, y, width, 0));
            }
        }

        return new WindowChromeLayout(
                Rect.create(0, 0, width, height),
                Rect.create(0, 0, width, menuHeight),
                Rect.create(0, contentTop, width, contentHeight),
                footerBounds,
                visibleFooterBars.size());
    }

    static Set<FooterBar> standardFooterBars(boolean hasTabBar) {
        var bars = EnumSet.of(FooterBar.MODE_LINE, FooterBar.COMMAND);
        if (hasTabBar) {
            bars.add(FooterBar.TAB_BAR);
        }
        return bars;
    }

    Rect root() {
        return _root;
    }

    Rect topMenu() {
        return _topMenu;
    }

    Rect workspace() {
        return _workspace;
    }

    Rect modeLine() {
        return _footerBounds.get(FooterBar.MODE_LINE);
    }

    Rect commandLine() {
        return _footerBounds.get(FooterBar.COMMAND);
    }

    Rect tabBar() {
        return _footerBounds.get(FooterBar.TAB_BAR);
    }

    int footerInsetRows() {
        return _footerInsetRows;
    }

    private static Set<FooterBar> visibleFooterBars(Set<FooterBar> requested, int availableRows) {
        var visible = EnumSet.noneOf(FooterBar.class);
        requested.stream()
                .sorted((left, right) -> Integer.compare(left._priority, right._priority))
                .limit(Math.max(0, availableRows))
                .forEach(visible::add);
        return visible;
    }
}
