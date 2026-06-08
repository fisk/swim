package org.fisk.swim.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class HelpIndexTest {
    @Test
    void helpDocumentContainsChapteredTutorialEntries() {
        assertFalse(HelpDocument.chapters().isEmpty());
        assertNotNull(HelpDocument.findChapter("start"));
        assertNotNull(HelpDocument.findChapter("Movement"));

        String index = HelpDocument.renderIndex();
        assertTrue(index.contains("SWIM Help Index"));
        assertTrue(index.contains("start - Start Here"));
        assertTrue(index.contains("nemo - Nemo Assistant"));

        String start = HelpDocument.renderForNemo("start");
        assertTrue(start.contains("Normal mode and Insert mode"));
        assertTrue(start.contains("SWIM starts in NORMAL mode"));
        assertTrue(start.contains("Example:"));
        assertTrue(start.contains("ihello world<ESC>"));

        String files = HelpDocument.renderForNemo("files");
        assertTrue(files.contains(":e path opens an existing file"));
        assertTrue(files.contains(":bnext and :bprev cycle through buffers"));

        String diagnostics = HelpDocument.renderForNemo("diagnostic");
        assertTrue(diagnostics.contains("Diagnostics and Code Intelligence"));
        assertTrue(diagnostics.contains("g x opens diagnostics for the current line"));
    }

    @Test
    void flatHelpListUsesChapteredDocumentForCompatibility() {
        var rendered = HelpIndex.createHelpList().stream()
                .map(item -> item.displayString())
                .collect(Collectors.toList());

        assertFalse(rendered.isEmpty());
        assertTrue(rendered.contains("SWIM Help"));
        assertTrue(rendered.contains("Start Here"));
        assertTrue(rendered.contains("  Normal mode and Insert mode"));
        assertTrue(rendered.stream().anyMatch(line -> line.contains("SWIM starts in NORMAL mode")));
        assertTrue(rendered.contains("Files, Buffers, and Panes"));
        assertTrue(rendered.contains("Nemo Assistant"));
        assertTrue(rendered.stream().anyMatch(line -> line.contains(":swim-help reads this editor help")));
    }

    @Test
    void helpItemsAreSafeToSelect() {
        for (var item : HelpIndex.createHelpList()) {
            item.onClick();
        }
    }
}
