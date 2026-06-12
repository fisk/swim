package org.fisk.swim.plugins.javadebug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JavaDebugPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
    }

    @Test
    void preloadRegistersJavaDebuggerHelp() {
        new JavaDebugPlugin().preload(() -> JavaDebugPluginSupport.PLUGIN_ID);

        assertTrue(SwimHelpRegistry.chapters().stream()
                .anyMatch(chapter -> "java-debug".equals(chapter.id())
                        && chapter.sections().stream()
                                .flatMap(section -> section.paragraphs().stream())
                                .anyMatch(paragraph -> paragraph.contains("main class")
                                        && paragraph.contains("debugger panel"))));
    }
}
