package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryLocatorTest {
    @TempDir
    Path tempDir;

    @Test
    void findsRepositoryRootForNestedFile() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("src"));
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            Path file = Files.writeString(repo.resolve("src/Main.java"), "class Main {}\n");

            assertEquals(repo.toAbsolutePath().normalize(), GitRepositoryLocator.findRepositoryRoot(file));
        }
    }

    @Test
    void returnsNullOutsideRepository() throws IOException {
        Path file = Files.writeString(tempDir.resolve("plain.txt"), "hello\n");

        assertNull(GitRepositoryLocator.findRepositoryRoot(file));
    }
}
