package org.fisk.swim.fileindex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.fisk.swim.ui.ListView.ListItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void fileIndexIncludesVisibleFilesAndSortsThem() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".swim"), """
                docs/
                *.log
                !keep.log
                """);
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("zeta.txt"), "z");
        Files.writeString(root.resolve("alpha.txt"), "a");
        Files.writeString(root.resolve("debug.log"), "l");
        Files.writeString(root.resolve("keep.log"), "k");
        Files.writeString(root.resolve("src").resolve("beta.txt"), "b");
        Files.writeString(root.resolve("docs").resolve("manual.txt"), "d");
        Files.writeString(root.resolve(".hidden.txt"), "h");
        Files.createDirectories(root.resolve(".secret"));
        Files.writeString(root.resolve(".secret").resolve("ignored.txt"), "x");

        var index = new FileIndex();
        String original = System.getProperty("user.dir");
        System.setProperty("user.dir", root.toString());
        List<? extends ListItem> items;
        try {
            @SuppressWarnings("unchecked")
            List<? extends ListItem> raw = (List<? extends ListItem>) (List<?>) index.createFileIndex();
            items = raw;
        } finally {
            System.setProperty("user.dir", original);
        }

        assertEquals(
                java.util.List.of("alpha.txt", "keep.log", "src/beta.txt", "zeta.txt"),
                items.stream().map(item -> item.displayString()).collect(Collectors.toList()));
    }
}
