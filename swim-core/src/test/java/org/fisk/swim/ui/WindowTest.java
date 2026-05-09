package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowTest {
    @TempDir
    Path tempDir;

    @Test
    void showAndHideListResizeBufferViewAndResponder() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            int originalHeight = window.getBufferContext().getBufferView().getBounds().getSize().getHeight();

            window.showList(List.of(item("alpha")), "Files");

            assertTrue(window.isShowingList());
            assertEquals(6, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(HeadlessWindowHarness.getField(window, "_listView"), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));

            window.hideList();

            assertFalse(window.isShowingList());
            assertEquals(originalHeight, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(window.getBufferContext().getBufferView(), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void setBufferPathReplacesBufferAndResetsToNormalMode() throws IOException {
        Path first = writeFile("first.txt", "one");
        Path second = writeFile("second.txt", "two");

        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            var window = harness.getWindow();
            window.switchToMode(window.getInputMode());

            window.setBufferPath(second);

            assertSame(window.getNormalMode(), window.getCurrentMode());
            assertEquals("two", window.getBufferContext().getBuffer().getString());
            assertSame(window.getCurrentMode(), HeadlessWindowHarness.getField(window.getBufferContext().getBufferView(), "_firstResponder"));
        }
    }

    private ListView.ListItem item(String name) {
        return new ListView.ListItem() {
            @Override
            public void onClick() {
                new AtomicInteger().incrementAndGet();
            }

            @Override
            public String displayString() {
                return name;
            }
        };
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
