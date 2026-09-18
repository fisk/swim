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
        return compute(size, topMenuHeight, requestedFooterBars, 1);
    }

    static WindowChromeLayout compute(
            Size size, int topMenuHeight, Set<FooterBar> requestedFooterBars, int commandRows) {
        int width = size == null ? 0 : Math.max(0, size.getWidth());
        int height = size == null ? 0 : Math.max(0, size.getHeight());
        int menuHeight = Math.min(Math.max(0, topMenuHeight), height);
        Set<FooterBar> requested = requestedFooterBars == null || requestedFooterBars.isEmpty()
                ? EnumSet.noneOf(FooterBar.class)
                : EnumSet.copyOf(requestedFooterBars);
        int remainingFooterRows = Math.max(0, height - menuHeight);
        int requestedCommandRows = Math.max(1, commandRows);
        var footerHeights = new EnumMap<FooterBar, Integer>(FooterBar.class);
        for (var bar : FooterBar.values()) {
            footerHeights.put(bar, 0);
        }
        FooterBar[] priorityOrder = { FooterBar.COMMAND, FooterBar.MODE_LINE, FooterBar.TAB_BAR };
        for (int barIndex = 0; barIndex < priorityOrder.length; barIndex++) {
            FooterBar bar = priorityOrder[barIndex];
            if (!requested.contains(bar) || remainingFooterRows == 0) {
                continue;
            }
            int rowsReservedForOtherBars = 0;
            for (int laterIndex = barIndex + 1; laterIndex < priorityOrder.length; laterIndex++) {
                if (requested.contains(priorityOrder[laterIndex])) {
                    rowsReservedForOtherBars++;
                }
            }
            int desiredHeight = bar == FooterBar.COMMAND ? requestedCommandRows : 1;
            int availableForBar = Math.max(1, remainingFooterRows - rowsReservedForOtherBars);
            int actualHeight = Math.min(desiredHeight, availableForBar);
            footerHeights.put(bar, actualHeight);
            remainingFooterRows -= actualHeight;
        }
        int contentTop = menuHeight;
        int footerInsetRows = height - menuHeight - remainingFooterRows;
        int contentHeight = Math.max(0, height - menuHeight - footerInsetRows);
        var footerBounds = new EnumMap<FooterBar, Rect>(FooterBar.class);
        for (var bar : FooterBar.values()) {
            footerBounds.put(bar, Rect.create(0, contentTop + contentHeight, width, 0));
        }

        int y = contentTop + contentHeight;
        for (var bar : FooterBar.values()) {
            int barHeight = footerHeights.get(bar);
            footerBounds.put(bar, Rect.create(0, y, width, barHeight));
            y += barHeight;
        }

        return new WindowChromeLayout(
                Rect.create(0, 0, width, height),
                Rect.create(0, 0, width, menuHeight),
                Rect.create(0, contentTop, width, contentHeight),
                footerBounds,
                footerInsetRows);
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

}
