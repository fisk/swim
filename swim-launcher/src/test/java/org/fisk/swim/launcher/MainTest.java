package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path tempDir;

    @Test
    void findsBuildRootFromNestedLauncherPath() throws IOException {
        Path root = tempDir.resolve("swim");
        Path nested = root.resolve("target").resolve("swim-0.0.1-SNAPSHOT.jar");
        Files.createDirectories(root.resolve("swim-core"));
        Files.createDirectories(nested.getParent());
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.writeString(nested, "jar");

        assertEquals(root, Main.findBuildRoot(nested));
    }

    @Test
    void returnsNullWhenNoBuildRootMarkersExist() throws IOException {
        Path path = tempDir.resolve("elsewhere").resolve("target").resolve("swim.jar");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "jar");

        assertNull(Main.findBuildRoot(path));
    }
}
