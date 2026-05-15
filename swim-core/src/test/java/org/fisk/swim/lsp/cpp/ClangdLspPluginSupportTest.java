package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.lsp.LanguageModeProvider;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClangdLspPluginSupportTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
        ClangdLspClient.shutdownInstalledInstance();
        SwimRuntime.clear();
        EventThread.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

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

    @Test
    void createWindowStartsClangdWhenProjectHasCompilationDatabase() throws Exception {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project.resolve("build"));
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");
        Path file = Files.writeString(project.resolve("README.txt"), "notes\n");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, file);

        assertEquals(1, client.startCalls);
        assertEquals(file.toAbsolutePath().normalize(), client.startedPath);
    }

    @Test
    void switchingBuffersStartsClangdWhenCompilationDatabaseIsDiscovered() throws Exception {
        Path initialFile = Files.writeString(tempDir.resolve("notes.txt"), "plain\n");
        Path project = tempDir.resolve("engine");
        Files.createDirectories(project.resolve("build"));
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");
        Path discoveredFile = Files.writeString(project.resolve("scratch.txt"), "inside project\n");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, initialFile);
        assertEquals(0, client.startCalls);

        assertTrue(Window.getInstance().setBufferPath(discoveredFile));
        assertEquals(1, client.startCalls);
        assertEquals(discoveredFile.toAbsolutePath().normalize(), client.startedPath);
    }

    private static Object createDefaultBindings() throws Exception {
        Class<?> type = Class.forName("org.fisk.swim.SwimAppImpl$DefaultRuntimeBindings");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class RecordingClangdLspClient extends ClangdLspClient {
        private int startCalls;
        private Path startedPath;
        private boolean started;

        private RecordingClangdLspClient() {
            super(new ClangdLspProvider(Path.of("/tmp/clangd")));
        }

        @Override
        public boolean hasStarted() {
            return started;
        }

        @Override
        public synchronized void startServer(Path filePath) {
            startCalls++;
            started = true;
            startedPath = filePath.toAbsolutePath().normalize();
        }

        @Override
        public void ensureInit() {
        }
    }
}
