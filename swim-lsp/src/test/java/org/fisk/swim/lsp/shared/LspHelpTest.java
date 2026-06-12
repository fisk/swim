package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LspHelpTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
    }

    @Test
    void sharedChapterIsReferenceCountedForMultipleLanguagePlugins() throws Exception {
        AutoCloseable first = LspHelp.registerSharedChapter(() -> "java-lsp");
        AutoCloseable second = LspHelp.registerSharedChapter(() -> "clangd-lsp");
        try {
            assertEquals(1, SwimHelpRegistry.chapters().size());
            assertEquals("lsp", SwimHelpRegistry.chapters().getFirst().id());

            first.close();
            assertEquals(1, SwimHelpRegistry.chapters().size());

            second.close();
            assertTrue(SwimHelpRegistry.chapters().isEmpty());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void sharedChapterExplainsWhatCommandsDoAndWhenToUseThem() {
        String text = LspHelp.chapter().sections().stream()
                .flatMap(section -> section.paragraphs().stream())
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(text.contains("when you want documentation"));
        assertTrue(text.contains("For example"));
        assertTrue(text.contains("interface method"));
        assertTrue(text.contains("stale answers are ignored"));
        assertTrue(text.contains("normal undo path"));
    }
}
