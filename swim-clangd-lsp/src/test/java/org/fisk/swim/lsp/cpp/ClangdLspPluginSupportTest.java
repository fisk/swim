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
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.lsp.LanguageModeProvider;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Rect;
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
        SwimPluginPreloadRegistry.clearForTests();
        SwimRuntime.clear();
        EventThread.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

    @Test
    void registryMapsCppExtensionsToClangdPlugin() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        LanguageModeProvider.getInstance();

        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx"}) {
            var registration = LanguagePluginRegistry.find(Path.of("demo." + extension));
            assertNotNull(registration);
            assertEquals(ClangdLspPluginSupport.PLUGIN_ID, registration.pluginId());
        }
    }

    @Test
    void languageModeProviderMapsCppExtensionsToClangdMode() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx"}) {
            var mode = LanguageModeProvider.getInstance().getLanguageMode(Path.of("demo." + extension));
            assertNotNull(mode);
            assertInstanceOf(ClangdLspClient.class, mode);
        }
    }

    @Test
    void newlineInsideCppBlockUsesTwoSpaceIndentation() throws Exception {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        Path file = tempDir.resolve("indent.cpp");
        Files.writeString(file, """
                int main() {}
                """);
        var context = new BufferContext(Rect.create(0, 0, 120, 20), file);
        var buffer = context.getBuffer();
        int insertPosition = buffer.getString().indexOf('{') + 1;
        buffer.getCursor().setPosition(insertPosition);

        buffer.insert("\n");
        buffer.insert("return 0;");

        assertEquals("""
                int main() {
                  return 0;
                }
                """, buffer.getString());
    }

    @Test
    void findsCounterpartInSameDirectoryUsingCppExtensions() throws Exception {
        Path directory = tempDir.resolve("same-dir");
        Files.createDirectories(directory);
        Path source = Files.writeString(directory.resolve("demo.cpp"), "int demo() { return 0; }\n");
        Path header = Files.writeString(directory.resolve("demo.hpp"), "#pragma once\n");

        assertEquals(header, ClangdLspPluginSupport.findHeaderImplementationCounterpart(source));
        assertEquals(source, ClangdLspPluginSupport.findHeaderImplementationCounterpart(header));
    }

    @Test
    void findsCounterpartInProjectRootWithRelatedSourceAndIncludeDirectories() throws Exception {
        Path project = tempDir.resolve("project-pair");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("include"));
        Path source = Files.writeString(project.resolve("src").resolve("engine.cxx"), "int engine() { return 0; }\n");
        Path header = Files.writeString(project.resolve("include").resolve("engine.hxx"), "#pragma once\n");

        assertEquals(header, ClangdLspPluginSupport.findHeaderImplementationCounterpart(source));
        assertEquals(source, ClangdLspPluginSupport.findHeaderImplementationCounterpart(header));
    }

    @Test
    void normalModeGMSwitchesCppBufferToCounterpart() throws Exception {
        Path project = tempDir.resolve("normal-pair");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("include"));
        Path source = Files.writeString(project.resolve("src").resolve("demo.cpp"), "int demo() { return 0; }\n");
        Path header = Files.writeString(project.resolve("include").resolve("demo.hpp"), "#pragma once\n");

        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(source, 60, 10)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('g'),
                    HeadlessWindowHarness.key('m'));

            assertEquals(header.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void createWindowStartsClangdForCppFileWhenProjectHasCompilationDatabase() throws Exception {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project.resolve("build"));
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");
        Path file = Files.writeString(project.resolve("demo.cpp"), "int main() { return 0; }\n");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, file);

        assertEquals(1, client.startCalls);
        assertEquals(file.toAbsolutePath().normalize(), client.startedPath);
    }

    @Test
    void switchingToCppBufferStartsClangdWhenCompilationDatabaseIsDiscovered() throws Exception {
        Path initialFile = Files.writeString(tempDir.resolve("notes.txt"), "plain\n");
        Path project = tempDir.resolve("engine");
        Files.createDirectories(project.resolve("build"));
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");
        Path discoveredFile = Files.writeString(project.resolve("engine.cpp"), "int engine() { return 0; }\n");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
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
