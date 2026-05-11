package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewPanelTest {
    @TempDir
    Path _tempDir;

    @Test
    void rendersTitleAndSelectionMarkers() throws IOException {
        Path dir = Files.createDirectories(_tempDir.resolve("src"));
        Files.writeString(dir.resolve("Main.java"), "class Main {}\n");

        TreeViewController controller = new TreeViewController(_tempDir);
        TreeViewPanel panel = new TreeViewPanel(controller, new TreeViewRenderer(), "Project Tree");

        List<String> lines = panel.render(24, 4);
        assertEquals("Project Tree            ", lines.get(0));
        assertTrue(lines.get(1).startsWith("> v "));
        assertTrue(lines.get(2).contains("> src"));
    }

    @Test
    void scrollsToKeepSelectionVisible() throws IOException {
        for (int i = 0; i < 8; ++i) {
            Files.writeString(_tempDir.resolve("file-" + i + ".txt"), "x\n");
        }

        TreeViewController controller = new TreeViewController(_tempDir);
        TreeViewPanel panel = new TreeViewPanel(controller);
        for (int i = 0; i < 6; ++i) {
            panel.moveDown();
        }

        List<String> lines = panel.render(30, 4);
        assertEquals(4, lines.size());
        assertTrue(lines.get(3).startsWith(">   - file-5.txt"));
    }

    @Test
    void handlesNavigationCommands() throws IOException {
        Files.writeString(_tempDir.resolve("a.txt"), "a\n");
        Files.writeString(_tempDir.resolve("b.txt"), "b\n");

        TreeViewController controller = new TreeViewController(_tempDir);
        TreeViewPanel panel = new TreeViewPanel(controller);
        panel.handleCommand(TreeViewCommand.MOVE_DOWN);

        List<String> lines = panel.render(30, 4);
        assertTrue(lines.get(2).startsWith(">   - a.txt") || lines.get(2).startsWith(">   - b.txt"));
    }

    @Test
    void producesRenderSnapshot() throws IOException {
        Files.writeString(_tempDir.resolve("a.txt"), "a\n");

        TreeViewController controller = new TreeViewController(_tempDir);
        TreeViewPanel panel = new TreeViewPanel(controller, new TreeViewRenderer(), "Snapshot");

        TreeViewRenderSnapshot snapshot = panel.snapshot(20, 3);
        assertEquals(_tempDir.toAbsolutePath().normalize(), snapshot.rootPath());
        assertEquals(_tempDir.toAbsolutePath().normalize(), snapshot.selectedPath());
        assertEquals(0, snapshot.scrollOffset());
        assertEquals("Snapshot            ", snapshot.lines().get(0));
    }
}
