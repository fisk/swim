package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClangdLspProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void buildCommandStartsClangdWithBackgroundIndex() {
        Path clangd = Path.of("/opt/homebrew/bin/clangd");

        assertEquals(List.of("/opt/homebrew/bin/clangd", "--background-index"), ClangdLspProvider.buildCommand(clangd));
    }

    @Test
    void findsExecutableOnPath() throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Path clangd = Files.writeString(binDir.resolve("clangd"), "#!/bin/sh\nexit 0\n");
        assertTrue(clangd.toFile().setExecutable(true));

        assertEquals(clangd.toAbsolutePath().normalize(),
                ClangdLspProvider.findExecutableOnPath(binDir.toString(), "Mac OS X"));
    }

    @Test
    void returnsNullWhenExecutableIsMissing() {
        assertNull(ClangdLspProvider.findExecutableOnPath(tempDir.toString(), "Mac OS X"));
    }

    @Test
    void initializationOptionsExposeCompilationDatabasePath() {
        Path buildDir = tempDir.resolve("build");

        assertEquals(Map.of("compilationDatabasePath", buildDir.toString()), ClangdLspClient.createInitializationOptions(buildDir));
        assertTrue(ClangdLspClient.createInitializationOptions(null).isEmpty());
    }
}
