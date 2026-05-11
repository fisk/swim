package org.fisk.swim.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class HelpIndexTest {
    @Test
    void helpListContainsCoreTutorialEntries() {
        var rendered = HelpIndex.createHelpList().stream()
                .map(item -> item.displayString())
                .collect(Collectors.toList());

        assertFalse(rendered.isEmpty());
        assertTrue(rendered.contains("SWIM tutorial"));
        assertTrue(rendered.contains("  :help shows this tutorial."));
        assertTrue(rendered.contains("  :vsplit opens another view to the right of the active buffer."));
        assertTrue(rendered.contains("Pane shortcuts"));
        assertTrue(rendered.contains("  Press i to enter INSERT mode and type text."));
        assertTrue(rendered.contains("  / starts forward search, ? starts backward search, n/N repeat it."));
    }

    @Test
    void helpItemsAreSafeToSelect() {
        for (var item : HelpIndex.createHelpList()) {
            item.onClick();
        }
    }
}
