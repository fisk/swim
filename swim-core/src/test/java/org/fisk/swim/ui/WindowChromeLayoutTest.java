package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WindowChromeLayoutTest {
    @Test
    void standardLayoutStacksModeCommandAndTabsBelowWorkspace() {
        var layout = WindowChromeLayout.compute(Size.create(60, 16), 2,
                WindowChromeLayout.standardFooterBars(true));

        assertEquals("{0, 0, 60, 16}", layout.root().toString());
        assertEquals("{0, 0, 60, 2}", layout.topMenu().toString());
        assertEquals("{0, 2, 60, 11}", layout.workspace().toString());
        assertEquals("{0, 13, 60, 1}", layout.modeLine().toString());
        assertEquals("{0, 14, 60, 1}", layout.commandLine().toString());
        assertEquals("{0, 15, 60, 1}", layout.tabBar().toString());
        assertEquals(3, layout.footerInsetRows());
    }

    @Test
    void commandLineWinsFirstFooterRowInTinyTerminal() {
        var layout = WindowChromeLayout.compute(Size.create(40, 3), 2,
                WindowChromeLayout.standardFooterBars(true));

        assertEquals("{0, 0, 40, 2}", layout.topMenu().toString());
        assertEquals("{0, 2, 40, 0}", layout.workspace().toString());
        assertEquals("{0, 2, 40, 0}", layout.modeLine().toString());
        assertEquals("{0, 2, 40, 1}", layout.commandLine().toString());
        assertEquals("{0, 3, 40, 0}", layout.tabBar().toString());
        assertEquals(1, layout.footerInsetRows());
    }

    @Test
    void layoutWithoutTabsReservesOnlyModeAndCommandRows() {
        var layout = WindowChromeLayout.compute(Size.create(40, 8), 2,
                WindowChromeLayout.standardFooterBars(false));

        assertEquals("{0, 2, 40, 4}", layout.workspace().toString());
        assertEquals("{0, 6, 40, 1}", layout.modeLine().toString());
        assertEquals("{0, 7, 40, 1}", layout.commandLine().toString());
        assertEquals("{0, 8, 40, 0}", layout.tabBar().toString());
        assertEquals(2, layout.footerInsetRows());
    }
}
