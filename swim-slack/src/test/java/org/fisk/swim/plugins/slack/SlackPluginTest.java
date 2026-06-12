package org.fisk.swim.plugins.slack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SlackPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
    }

    @Test
    void preloadRegistersSlackWorkspaceKeyBindingAndHelp() {
        new SlackPlugin().preload(() -> "swim-slack");

        assertTrue(SwimPluginKeyBindingRegistry.listBindings().stream()
                .anyMatch(binding -> "swim-slack".equals(binding.pluginId())
                        && "<SPACE> s".equals(binding.key())
                        && "slack".equals(binding.command())));
        assertTrue(SwimHelpRegistry.chapters().stream()
                .anyMatch(chapter -> "slack".equals(chapter.id())
                        && chapter.sections().stream()
                                .flatMap(section -> section.paragraphs().stream())
                                .anyMatch(paragraph -> paragraph.contains("full workspace"))));
    }
}
