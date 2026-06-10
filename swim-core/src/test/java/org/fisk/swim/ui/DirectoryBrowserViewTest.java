package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryBrowserViewTest {
    @TempDir
    Path tempDir;

    @Test
    void enterOnDirectoryDescendsIntoIt() throws IOException {
        Path parent = tempDir.resolve("browse");
        Path child = parent.resolve("src");
        Files.createDirectories(child);
        Files.writeString(parent.resolve("README.txt"), "hello");

        var view = new DirectoryBrowserView(Rect.create(0, 0, 40, 10), parent);

        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.down());
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals(child, view.getDirectory());
    }

    @Test
    void backspaceMovesToParentDirectory() throws IOException {
        Path parent = tempDir.resolve("browse-parent");
        Path child = parent.resolve("src");
        Files.createDirectories(child);

        var view = new DirectoryBrowserView(Rect.create(0, 0, 40, 10), child);

        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.backspace());

        assertEquals(parent, view.getDirectory());
    }

    @Test
    void enterOnFileOpensItInWindowBuffer() throws Exception {
        Path dir = tempDir.resolve("browse-open");
        Files.createDirectories(dir);
        Path file = Files.writeString(dir.resolve("note.txt"), "opened\n");

        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("scratch.txt"), 60, 14)) {
            var window = harness.getWindow();
            assertTrue(window.showDirectoryBrowser(dir));
            var view = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertInstanceOf(BufferView.class, window.getActiveView());
            assertTrue(tabLabels(window).contains("Browse: browse-open"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tabLabels(Window window) throws Exception {
        Method method = Window.class.getDeclaredMethod("tabEntries");
        method.setAccessible(true);
        return ((List<TabBarView.Tab>) method.invoke(window)).stream()
                .map(TabBarView.Tab::label)
                .toList();
    }
}
