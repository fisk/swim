package org.fisk.swim.fileindex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchTest {
    @TempDir
    Path tempDir;

    @Test
    void searchFindsVisibleMatchesAndSkipsGeneratedAndHiddenFiles() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve(".secret"));
        Files.writeString(root.resolve("notes.txt"), "needle in notes\n");
        Files.writeString(root.resolve("src/App.java"), "class App {}\nString value = \"Needle\";\n");
        Files.writeString(root.resolve("target/generated.txt"), "needle in build output\n");
        Files.writeString(root.resolve(".secret/hidden.txt"), "needle in hidden file\n");

        var search = new ProjectSearch(root.resolve("src/App.java"));
        List<ProjectSearch.Match> matches = search.search("needle");

        assertEquals(
                List.of("notes.txt:1:1  needle in notes", "src/App.java:2:17  String value = \"Needle\";"),
                matches.stream().map(ProjectSearch.Match::displayString).toList());
    }

    @Test
    void uppercaseQueryUsesCaseSensitiveSearch() throws IOException {
        Path root = tempDir.resolve("workspace-case");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "needle\nNeedle\n");

        var search = new ProjectSearch(root.resolve("src/App.java"));

        assertEquals(
                List.of("src/App.java:2:1  Needle"),
                search.search("Needle").stream().map(ProjectSearch.Match::displayString).toList());
    }
}
