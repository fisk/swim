package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EmailPluginTest {
    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
    }

    @Test
    void preloadRegistersMailWorkspaceKeyBinding() {
        new EmailPlugin().preload(() -> "swim-email");

        assertTrue(SwimPluginKeyBindingRegistry.listBindings().stream()
                .anyMatch(binding -> "swim-email".equals(binding.pluginId())
                        && "<SPACE> m".equals(binding.key())
                        && "mail".equals(binding.command())));
    }
}
