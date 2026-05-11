package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewControllerTest {
    @TempDir
    Path _tempDir;

    @Test
    void expandsDirectoriesAndOpensFiles() throws IOException {
        Path srcDir = Files.createDirectories(_tempDir.resolve("src"));
        Path nestedDir = Files.createDirectories(srcDir.resolve("main"));
        Path javaFile = Files.writeString(nestedDir.resolve("App.java"), "class App {}\n");
        Files.writeString(_tempDir.resolve("README.md"), "docs\n");

        TreeViewController controller = new TreeViewController(_tempDir);
        assertTrue(controller.selectPath(srcDir));

        TreeViewAction directoryAction = controller.activateSelection();
        assertEquals(TreeViewActionType.TOGGLE_DIRECTORY, directoryAction.type());
        assertTrue(controller.selectPath(javaFile));

        AtomicReference<Path> openedPath = new AtomicReference<>();
        controller.setFileOpener(openedPath::set);
        TreeViewAction fileAction = controller.activateSelection();

        assertEquals(TreeViewActionType.OPEN_FILE, fileAction.type());
        assertEquals(javaFile.toAbsolutePath().normalize(), fileAction.path());
        assertEquals(javaFile.toAbsolutePath().normalize(), openedPath.get());
    }

    @Test
    void collapsesSelectedDirectoryOrParent() throws IOException {
        Path docsDir = Files.createDirectories(_tempDir.resolve("docs"));
        Path guideFile = Files.writeString(docsDir.resolve("guide.txt"), "guide\n");

        TreeViewController controller = new TreeViewController(_tempDir);
        assertTrue(controller.selectPath(docsDir));
        controller.activateSelection();
        assertTrue(controller.selectPath(guideFile));

        assertTrue(controller.collapseSelectedDirectoryOrParent());
        assertEquals(docsDir.toAbsolutePath().normalize(), controller.getSelectedPath());
        assertFalse(controller.getRows().stream().anyMatch(row -> row.path().equals(guideFile.toAbsolutePath().normalize())));
    }

    @Test
    void keepsRootVisibleWhenRefreshing() throws IOException {
        Files.createDirectories(_tempDir.resolve("alpha"));
        TreeViewController controller = new TreeViewController(_tempDir);

        TreeViewRow selected = controller.getSelectedRow();
        assertNotNull(selected);
        assertEquals(_tempDir.toAbsolutePath().normalize(), selected.path());
        assertTrue(selected.expanded());

        Files.createDirectories(_tempDir.resolve("beta"));
        controller.refresh();

        List<TreeViewRow> rows = controller.getRows();
        assertTrue(rows.stream().anyMatch(row -> row.label().equals("alpha")));
        assertTrue(rows.stream().anyMatch(row -> row.label().equals("beta")));
    }
}
