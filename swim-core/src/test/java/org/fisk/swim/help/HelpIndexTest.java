package org.fisk.swim.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimHelpSection;
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
        assertTrue(index.contains("workspaces - Todo, Shell, Sessions, and Plugin Panels"));
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

        String workspaces = HelpDocument.renderForNemo("workspaces");
        assertTrue(workspaces.contains("Plugin panels"));
        assertTrue(workspaces.contains("plugin-specific topics in :help"));
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
        assertTrue(rendered.contains("Todo, Shell, Sessions, and Plugin Panels"));
        assertTrue(rendered.stream().anyMatch(line -> line.contains("Plugins can add full-screen workspaces")));
        assertTrue(rendered.contains("Nemo Assistant"));
        assertTrue(rendered.stream().anyMatch(line -> line.contains(":swim-help reads this editor help")));
    }

    @Test
    void registeredPluginChaptersAppearInHelpAndNemoHelp() throws Exception {
        try (var ignored = SwimHelpRegistry.register("plugin-docs", new SwimHelpChapter(
                "plugin-docs",
                "Plugin Docs",
                "Plugin-provided help appears in the shared help tree.",
                List.of(new SwimHelpSection("Plugin section",
                        List.of("This paragraph is only present in plugin registered help."),
                        ":plugin-docs"))))) {
            assertNotNull(HelpDocument.findChapter("Plugin Docs"));
            assertTrue(HelpDocument.renderIndex().contains("plugin-docs - Plugin Docs"));
            assertTrue(HelpDocument.renderForNemo("plugin-docs").contains("Plugin section"));
            assertTrue(HelpDocument.search("plugin registered").stream()
                    .anyMatch(chapter -> "plugin-docs".equals(chapter.id())));

            var rendered = HelpIndex.createHelpList().stream()
                    .map(item -> item.displayString())
                    .collect(Collectors.toList());
            assertTrue(rendered.contains("Plugin Docs"));
            assertTrue(rendered.contains("  Plugin section"));
        }
    }

    @Test
    void helpItemsAreSafeToSelect() {
        for (var item : HelpIndex.createHelpList()) {
            item.onClick();
        }
    }
}
