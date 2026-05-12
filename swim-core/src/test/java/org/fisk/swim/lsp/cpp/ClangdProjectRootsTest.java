package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClangdProjectRootsTest {
    @TempDir
    Path tempDir;

    @Test
    void findsCompilationDatabaseInBuildDirectoryAndUsesProjectRootAsWorkspace() throws IOException {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve("build"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("build").resolve("compile_commands.json"), "[]");
        Path file = Files.writeString(project.resolve("src").resolve("main.cpp"), "int main() {}\n");

        assertEquals(project.resolve("build"), ClangdProjectRoots.findCompilationDatabaseRoot(file));
        assertEquals(project, ClangdProjectRoots.findWorkspaceRoot(file));
    }

    @Test
    void prefersNearestCompilationDatabase() throws IOException {
        Path project = tempDir.resolve("demo");
        Path nested = project.resolve("engine");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(nested.resolve("src"));
        Files.writeString(project.resolve("compile_commands.json"), "[]");
        Files.writeString(nested.resolve("compile_commands.json"), "[]");
        Path file = Files.writeString(nested.resolve("src").resolve("math.c"), "int add(int a, int b) { return a + b; }\n");

        assertEquals(nested, ClangdProjectRoots.findCompilationDatabaseRoot(file));
        assertEquals(nested, ClangdProjectRoots.findWorkspaceRoot(file));
    }

    @Test
    void fallsBackToNearestWorkspaceMarkerWithoutCompilationDatabase() throws IOException {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project.resolve("include"));
        Files.writeString(project.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.30)\n");
        Path file = Files.writeString(project.resolve("include").resolve("demo.hpp"), "#pragma once\n");

        assertNull(ClangdProjectRoots.findCompilationDatabaseRoot(file));
        assertEquals(project, ClangdProjectRoots.findWorkspaceRoot(file));
    }
}
