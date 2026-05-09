package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandViewTest {
    @TempDir
    Path tempDir;

    @Test
    void deactivateIsSafeWhenWindowHasBeenDisposed() {
        var view = new CommandView(Rect.create(0, 0, 10, 1));

        assertDoesNotThrow(view::deactivate);
    }

    @Test
    void searchNextAndPreviousTreatSearchStringLiterally() throws IOException {
        Path path = tempDir.resolve("search.txt");
        Files.writeString(path, "x [ y [ z");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();
            var cursor = window.getBufferContext().getBuffer().getCursor();

            commandView.activate("/");
            commandView.runSearch("[");
            commandView.deactivate();
            assertEquals(2, cursor.getPosition());

            assertDoesNotThrow(commandView::searchNext);
            assertEquals(6, cursor.getPosition());

            assertDoesNotThrow(commandView::searchPrevious);
            assertEquals(2, cursor.getPosition());
        }
    }
}
