package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Path;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLspPluginSupportTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
        JavaLSPClient.shutdownInstalledInstance();
        SwimPluginPreloadRegistry.clearForTests();
        SwimRuntime.clear();
    }

    @Test
    void normalModeOKeepsDefaultOpenBelowForJavaBuffers() throws IOException {
        var client = new RecordingJavaLspClient();
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(writeFile("Demo.java", "class Demo {}\n"), 40, 12)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('o'));

            assertEquals(0, client.organizeImportsCalls);
            assertEquals("class Demo {}\n\n", buffer.getString());
            assertSame(window.getInputMode(), window.getCurrentMode());
        }
    }

    @Test
    void normalModeLeaderCommaOOrganizesImportsForJavaBuffers() throws IOException {
        var client = new RecordingJavaLspClient();
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(writeFile("Demo.java", "class Demo {}\n"), 40, 12)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatchIncrementally(window.getCurrentMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key(','),
                    HeadlessWindowHarness.key('o'));

            assertEquals(1, client.organizeImportsCalls);
            assertEquals("class Demo {}\n", buffer.getString());
            assertSame(window.getNormalMode(), window.getCurrentMode());
        }
    }

    @Test
    void normalModeLeaderCommaOWorksWhenJavaBindingsRegisterAfterModeConstruction() throws IOException {
        var client = new RecordingJavaLspClient();
        JavaLSPClient.installInstance(client);
        try (var harness = HeadlessWindowHarness.create(writeFile("Demo.java", "class Demo {}\n"), 40, 12)) {
            var window = harness.getWindow();

            JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
            HeadlessWindowHarness.dispatchIncrementally(window.getCurrentMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key(','),
                    HeadlessWindowHarness.key('o'));

            assertEquals(1, client.organizeImportsCalls);
            assertSame(window.getNormalMode(), window.getCurrentMode());
        }
    }

    @Test
    void normalModeGDTriggersDefinitionLookupForJavaBuffers() throws IOException {
        var client = new RecordingJavaLspClient();
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(writeFile("Demo.java", "class Demo {}\n"), 40, 12)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('d'));

            assertEquals(1, client.goToDefinitionCalls);
            assertSame(window.getNormalMode(), window.getCurrentMode());
        }
    }

    @Test
    void normalModeOKeepsDefaultOpenBelowForNonJavaBuffers() throws IOException {
        JavaLSPClient.shutdownInstalledInstance();
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(writeFile("notes.txt", "alpha"), 40, 12)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('o'));

            assertEquals("alpha\n", buffer.getString());
            assertSame(window.getInputMode(), window.getCurrentMode());
        }
    }

    private Path writeFile(String name, String contents) throws IOException {
        Path path = tempDir.resolve(name);
        java.nio.file.Files.writeString(path, contents);
        return path;
    }

    private static final class RecordingJavaLspClient extends JavaLSPClient {
        private int organizeImportsCalls;
        private int goToDefinitionCalls;

        private RecordingJavaLspClient() {
            super(new JavaLspProvider() {
                @Override
                public boolean isAvailable() {
                    return false;
                }

                @Override
                public Session start(
                        Path projectPath,
                        Path workspacePath,
                        org.eclipse.lsp4j.services.LanguageClient client,
                        org.eclipse.lsp4j.ClientCapabilities clientCapabilities,
                        Object initializationOptions,
                        long timeoutSeconds) {
                    throw new UnsupportedOperationException();
                }
            });
        }

        @Override
        public void organizeImports(BufferContext bufferContext) {
            organizeImportsCalls++;
        }

        @Override
        public void goToDefinition(BufferContext bufferContext) {
            goToDefinitionCalls++;
        }
    }
}
