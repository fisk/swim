package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
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
        SwimHelpRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
        SwimRuntime.clear();
        EventThread.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

    @Test
    void preloadRegistersStandardLspBindings() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);

        var keys = SwimPluginKeyBindingRegistry.listBindings().stream()
                .filter(binding -> ClangdLspPluginSupport.PLUGIN_ID.equals(binding.pluginId()))
                .map(binding -> binding.key())
                .toList();

        assertTrue(keys.containsAll(List.of(
                "<SPACE> , h",
                "<SPACE> , p",
                "<SPACE> , d",
                "g D",
                "g y",
                "g i",
                "<SPACE> , u",
                "<SPACE> , H",
                "g s",
                "g S",
                "<SPACE> , a",
                "<SPACE> , l",
                "<SPACE> , f",
                "<SPACE> , F",
                "<SPACE> , t",
                "<SPACE> , R",
                "<SPACE> , n",
                "<SPACE> , z",
                "<SPACE> , v",
                "<SPACE> , c",
                "g T",
                "<SPACE> , m",
                "<SPACE> , k",
                "<SPACE> , C")));
    }

    @Test
    void preloadRegistersSharedAndClangdSpecificHelp() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);

        var ids = SwimHelpRegistry.chapters().stream()
                .map(chapter -> chapter.id())
                .toList();

        assertTrue(ids.contains("lsp"));
        assertTrue(ids.contains("clangd-lsp"));
        assertTrue(SwimHelpRegistry.chapters().stream()
                .flatMap(chapter -> chapter.sections().stream())
                .flatMap(section -> section.paragraphs().stream())
                .anyMatch(paragraph -> paragraph.contains("compile_commands.json")));
    }

    @Test
    void registryMapsCppExtensionsToClangdPlugin() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        LanguageModeProvider.getInstance();

        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "hhp", "hp", "h++", "ipp", "tpp", "inl", "inc"}) {
            var registration = LanguagePluginRegistry.find(Path.of("demo." + extension));
            assertNotNull(registration);
            assertEquals(ClangdLspPluginSupport.PLUGIN_ID, registration.pluginId());
        }
    }

    @Test
    void languageModeProviderMapsCppExtensionsToClangdMode() {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "hhp", "hp", "h++", "ipp", "tpp", "inl", "inc"}) {
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
    void savingCppHeadersSortsIncludeBlocksLikeHotspotSortIncludes() throws Exception {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        Path file = Files.writeString(tempDir.resolve("sort-me.hxx"), """
                #include <vector>
                #include "zeta.hpp"

                #include <algorithm>
                #include "Alpha.hpp"

                int value = 0;
                """);
        var context = new BufferContext(Rect.create(0, 0, 120, 20), file);

        context.getBuffer().writeOrThrow();

        String expected = """
                #include "Alpha.hpp"
                #include "zeta.hpp"

                #include <algorithm>
                #include <vector>

                int value = 0;
                """;
        assertEquals(expected, context.getBuffer().getString());
        assertEquals(expected, Files.readString(file));
    }

    @Test
    void savingInlineHeaderKeepsCorrespondingHeaderBeforeOtherIncludes() throws Exception {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        Path file = Files.writeString(tempDir.resolve("widget.inline.hpp"), """
                #include <vector>
                #include "zeta.hpp"
                #include "widget.hpp"
                #include "alpha.hpp"
                """);
        var context = new BufferContext(Rect.create(0, 0, 120, 20), file);

        context.getBuffer().writeOrThrow();

        assertEquals("""
                #include "widget.hpp"

                #include "alpha.hpp"
                #include "zeta.hpp"

                #include <vector>
                """, Files.readString(file));
    }

    @Test
    void savingInlineHhpKeepsItsCorrespondingHeaderBeforeOtherIncludes() throws Exception {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        Path file = Files.writeString(tempDir.resolve("widget.inline.hhp"), """
                #include <vector>
                #include "widget.hhp"
                #include "alpha.hhp"
                """);
        var context = new BufferContext(Rect.create(0, 0, 120, 20), file);

        context.getBuffer().writeOrThrow();

        assertEquals("""
                #include "widget.hhp"

                #include "alpha.hhp"

                #include <vector>
                """, Files.readString(file));
    }

    @Test
    void siblingOfCppFileUsesCppLanguageModeRegardlessOfItsOwnExtension() throws Exception {
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        Files.writeString(tempDir.resolve("widget.cpp"), "int widget() { return 0; }\n");
        Path companion = Files.writeString(tempDir.resolve("widget.generated"), "#include <vector>\n#include \"alpha.hpp\"\n");

        var mode = LanguageModeProvider.getInstance().getLanguageMode(companion);
        assertInstanceOf(ClangdLspClient.class, mode);
        assertTrue(ClangdLspPluginSupport.isCppPath(companion));
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
    void cyclesAcrossSameBaseCppFilesIncludingInlineHeaders() throws Exception {
        Path directory = tempDir.resolve("inline-pair");
        Files.createDirectories(directory);
        Path source = Files.writeString(directory.resolve("widget.cpp"), "int widget() { return 0; }\n");
        Path header = Files.writeString(directory.resolve("widget.hpp"), "#pragma once\n");
        Path inlineHeader = Files.writeString(directory.resolve("widget.inline.hpp"), "#pragma once\n");

        assertEquals(header, ClangdLspPluginSupport.findHeaderImplementationCounterpart(source));
        assertEquals(inlineHeader, ClangdLspPluginSupport.findHeaderImplementationCounterpart(header));
        assertEquals(source, ClangdLspPluginSupport.findHeaderImplementationCounterpart(inlineHeader));
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
        Path source = Files.writeString(project.resolve("src").resolve("demo.cpp"), "int demo() { return 0; }\n");
        Path header = Files.writeString(project.resolve("src").resolve("demo.hpp"), "#pragma once\n");
        Path inlineHeader = Files.writeString(project.resolve("src").resolve("demo.inline.hpp"), "#pragma once\n");

        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        try (var harness = HeadlessWindowHarness.create(source, 60, 10)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('g'),
                    HeadlessWindowHarness.key('m'));

            assertEquals(header.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('g'),
                    HeadlessWindowHarness.key('m'));
            assertEquals(inlineHeader.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void normalModeLeaderCommaHShowsHoverForCppBuffers() throws Exception {
        Path file = Files.writeString(tempDir.resolve("hover.cpp"), "int main() { return 0; }\n");
        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(file, 60, 12)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatchIncrementally(window.getCurrentMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key(','),
                    HeadlessWindowHarness.key('h'));

            assertEquals(1, client.hoverCalls);
        }
    }

    @Test
    void createWindowStartsClangdForCppFileWhenProjectHasCompilationDatabase() throws Exception {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project.resolve("build"));
        Path file = Files.writeString(project.resolve("demo.cpp"), "int main() { return 0; }\n");
        Files.writeString(project.resolve("build").resolve("compile_commands.json"),
                "[{\"directory\":\"" + project + "\",\"file\":\"demo.cpp\",\"command\":\"clang++ -c demo.cpp\"}]");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, file);

        assertEquals(1, client.startCalls);
        assertEquals(0, client.ensureInitCalls);
        assertEquals(file.toAbsolutePath().normalize(), client.startedPath);
    }

    @Test
    void createWindowStartsClangdForHeaderWithCompiledSibling() throws Exception {
        Path project = tempDir.resolve("header-sibling");
        Files.createDirectories(project.resolve("build"));
        // g m groups these by the prefix before the first dot. Keep LSP
        // eligibility aligned, including a compound source suffix.
        Path source = Files.writeString(project.resolve("engine.inline.cpp"), "int engine() { return 0; }\n");
        Path header = Files.writeString(project.resolve("engine.hpp"), "int engine();\n");
        Files.writeString(project.resolve("build").resolve("compile_commands.json"),
                "[{\"directory\":\"" + project + "\",\"file\":\"" + source.getFileName()
                        + "\",\"command\":\"clang++ -c engine.inline.cpp\"}]");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, header);

        assertEquals(1, client.startCalls);
        assertEquals(header.toAbsolutePath().normalize(), client.startedPath);
    }

    @Test
    void loneHeaderUsesNearestCompilationCommand() throws Exception {
        Path project = tempDir.resolve("lone-header");
        Path sourceDirectory = project.resolve("share/gc/z");
        Path build = project.resolve("build");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(build);
        Path source = Files.writeString(sourceDirectory.resolve("z_driver.cpp"), "void z_driver() {}\n");
        Path header = Files.writeString(sourceDirectory.resolve("z_globals.hpp"), "#pragma once\n");
        Files.writeString(build.resolve("compile_commands.json"),
                "[{\"directory\":\"" + project + "\",\"file\":\"" + source
                        + "\",\"arguments\":[\"clang++\",\"-c\",\"" + source + "\"]}]");

        assertTrue(ClangdCompilationDatabase.containsCommandFor(build, header));
        Path workspace = tempDir.resolve("workspace");
        Path filtered = ClangdCompilationDatabase.filteredRoot(build, workspace, List.of(), header);

        assertEquals(workspace, filtered);
        assertTrue(JsonParser.parseString(Files.readString(workspace.resolve("compile_commands.json")))
                .getAsJsonArray().asList().stream()
                .filter(entry -> entry.isJsonObject())
                .map(entry -> entry.getAsJsonObject().get("file").getAsString())
                .anyMatch(file -> header.toAbsolutePath().normalize().toString().equals(file)));
    }

    @Test
    void switchingToCppBufferStartsClangdWhenCompilationDatabaseIsDiscovered() throws Exception {
        Path initialFile = Files.writeString(tempDir.resolve("notes.txt"), "plain\n");
        Path project = tempDir.resolve("engine");
        Files.createDirectories(project.resolve("build"));
        Path discoveredFile = Files.writeString(project.resolve("engine.cpp"), "int engine() { return 0; }\n");
        Files.writeString(project.resolve("build").resolve("compile_commands.json"),
                "[{\"directory\":\"" + project + "\",\"file\":\"engine.cpp\",\"command\":\"clang++ -c engine.cpp\"}]");

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

    @Test
    void cppFileWithoutCompileCommandUsesPlainEditingMode() throws Exception {
        Path project = tempDir.resolve("partial-database");
        Files.createDirectories(project.resolve("build"));
        Path missingFile = Files.writeString(project.resolve("not-built.cpp"), "int helper() { return 1; }\n");
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");

        var client = new RecordingClangdLspClient();
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        TerminalContextTestSupport.install(80, 24);

        invoke(createDefaultBindings(), "createWindow", new Class<?>[] { Path.class }, missingFile);

        assertEquals(0, client.startCalls);
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
        private int ensureInitCalls;
        private int hoverCalls;
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
        public boolean isReady() {
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
            ensureInitCalls++;
        }

        @Override
        public void showHover(BufferContext bufferContext) {
            hoverCalls++;
        }
    }
}
