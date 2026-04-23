package org.fisk.swim.fileindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPathsTest {
    @TempDir
    Path tempDir;

    @Test
    void findsNearestProjectRootFromUserDir() throws IOException {
        Path project = tempDir.resolve("project");
        Path nested = project.resolve("src/main");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("pom.xml"), "<project />");

        withUserDir(nested, () -> assertEquals(project, ProjectPaths.getProjectRootPath()));
    }

    @Test
    void findsNearestSourceRootUsingSwimMarker() throws IOException {
        Path sourceRoot = tempDir.resolve("workspace");
        Path nested = sourceRoot.resolve("deep/path");
        Files.createDirectories(nested);
        Files.createDirectory(sourceRoot.resolve(".swim"));

        withUserDir(nested, () -> assertEquals(sourceRoot, ProjectPaths.getSourceRootPath()));
    }

    @Test
    void hasRepositoryReflectsGitDirectoryAtProjectRoot() throws IOException {
        Path project = tempDir.resolve("repo");
        Path nested = project.resolve("module");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("pom.xml"), "<project />");

        withUserDir(nested, () -> assertFalse(ProjectPaths.hasRepository()));

        Files.createDirectory(project.resolve(".git"));

        withUserDir(nested, () -> assertTrue(ProjectPaths.hasRepository()));
    }

    @Test
    void findsProjectRootFromArbitraryStartPath() throws IOException {
        Path project = tempDir.resolve("standalone");
        Path javaFile = project.resolve("src/main/java/App.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />");
        Files.writeString(javaFile, "class App {}");

        assertEquals(project, ProjectPaths.getProjectRootPath(javaFile));
    }

    private static void withUserDir(Path path, Runnable assertion) {
        String original = System.getProperty("user.dir");
        System.setProperty("user.dir", path.toString());
        try {
            assertion.run();
        } finally {
            System.setProperty("user.dir", original);
        }
    }
}
