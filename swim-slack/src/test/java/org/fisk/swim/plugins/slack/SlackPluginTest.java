package org.fisk.swim.plugins.slack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SlackPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
    }

    @Test
    void preloadRegistersSlackWorkspaceKeyBinding() {
        new SlackPlugin().preload(() -> "swim-slack");

        assertTrue(SwimPluginKeyBindingRegistry.listBindings().stream()
                .anyMatch(binding -> "swim-slack".equals(binding.pluginId())
                        && "<SPACE> s".equals(binding.key())
                        && "slack".equals(binding.command())));
    }
}
