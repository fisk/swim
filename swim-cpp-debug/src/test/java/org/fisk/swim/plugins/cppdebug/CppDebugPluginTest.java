package org.fisk.swim.plugins.cppdebug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CppDebugPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
    }

    @Test
    void preloadRegistersCppDebuggerHelp() {
        new CppDebugPlugin().preload(() -> CppDebugPluginSupport.PLUGIN_ID);

        assertTrue(SwimHelpRegistry.chapters().stream()
                .anyMatch(chapter -> "cpp-debug".equals(chapter.id())
                        && chapter.sections().stream()
                                .flatMap(section -> section.paragraphs().stream())
                                .anyMatch(paragraph -> paragraph.contains("gdb or lldb")
                                        && paragraph.contains("Build the executable first"))));
    }
}
