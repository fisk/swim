package org.fisk.swim.fileindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwimProjectConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void savesCompileCommandWithoutDiscardingProjectSettings() throws Exception {
        Path marker = tempDir.resolve(".swim");
        Files.writeString(marker, "compile_commands = build/compile_commands.json\nclangd.remove_compile_arguments = -bad\n");

        SwimProjectConfig.saveCompileCommand(tempDir, "ninja -C build");

        assertEquals("ninja -C build", SwimProjectConfig.load(tempDir).compileCommand());
        assertEquals("compile_commands = build/compile_commands.json\nclangd.remove_compile_arguments = -bad\n"
                + "compile.command = ninja -C build\n", Files.readString(marker));
    }

    @Test
    void doesNotCreateProjectMarkerJustToSaveCompileCommand() throws Exception {
        SwimProjectConfig.saveCompileCommand(tempDir, "make");

        assertNull(SwimProjectConfig.load(tempDir));
    }

    @Test
    void savesDebugCommandWithoutDiscardingProjectSettings() throws Exception {
        Path marker = tempDir.resolve(".swim");
        Files.writeString(marker, "compile_commands = build/compile_commands.json\n");

        SwimProjectConfig.saveDebugCommand(tempDir, "cpp gdb build/bin/app --cwd ~/benchmarks");

        assertEquals("cpp gdb build/bin/app --cwd ~/benchmarks", SwimProjectConfig.load(tempDir).debugCommand());
        assertEquals("compile_commands = build/compile_commands.json\n"
                + "debug.command = cpp gdb build/bin/app --cwd ~/benchmarks\n", Files.readString(marker));
    }
}
