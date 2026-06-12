package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TreeViewPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
    }

    @Test
    void preloadRegistersTreeWorkspaceKeyBindingAndHelp() {
        new TreeViewPlugin().preload(() -> TreeViewPluginSupport.PLUGIN_ID);

        assertTrue(SwimPluginKeyBindingRegistry.listBindings().stream()
                .anyMatch(binding -> TreeViewPluginSupport.PLUGIN_ID.equals(binding.pluginId())
                        && "<SPACE> T".equals(binding.key())
                        && "tree".equals(binding.command())));
        assertTrue(SwimHelpRegistry.chapters().stream()
                .anyMatch(chapter -> "tree".equals(chapter.id())
                        && chapter.sections().stream()
                                .flatMap(section -> section.paragraphs().stream())
                                .anyMatch(paragraph -> paragraph.contains("current project root"))));
    }
}
