package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.lsp.LanguageModeProvider;
import org.junit.jupiter.api.Test;

class ClangdLspPluginSupportTest {
    @Test
    void registryMapsCppExtensionsToClangdPlugin() {
        LanguageModeProvider.getInstance();

        for (String extension : new String[] {"c", "h", "cpp", "hpp"}) {
            var registration = LanguagePluginRegistry.find(Path.of("demo." + extension));
            assertNotNull(registration);
            assertEquals(ClangdLspPluginSupport.PLUGIN_ID, registration.pluginId());
        }
    }

    @Test
    void languageModeProviderMapsCppExtensionsToClangdMode() {
        for (String extension : new String[] {"c", "h", "cpp", "hpp"}) {
            var mode = LanguageModeProvider.getInstance().getLanguageMode(Path.of("demo." + extension));
            assertNotNull(mode);
            assertInstanceOf(ClangdLspClient.class, mode);
        }
    }
}
