package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchPanelViewTest {
    @TempDir
    Path tempDir;

    @Test
    void typingQueryPopulatesResultsAndEnterOpensSelectedMatch() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Path current = root.resolve("src/current.txt");
        Path alpha = root.resolve("src/Alpha.java");
        Path beta = root.resolve("src/Beta.java");
        Files.writeString(current, "current\n");
        Files.writeString(alpha, "first line\nneedle in alpha\n");
        Files.writeString(beta, "first line\nneedle in beta\n");

        try (var harness = HeadlessWindowHarness.create(current, 72, 16)) {
            var window = harness.getWindow();
            var panel = ProjectSearchPanelView.create(Rect.create(0, 0, 0, 0), current);

            assertTrue(window.showPanel(panel));

            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('n'));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('d'));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('l'));
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.key('e'));

            assertEquals(2, panel.getResults().size());

            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(panel, HeadlessWindowHarness.enter());

            assertFalse(window.isShowingPanel());
            assertEquals(beta, window.getBufferContext().getBuffer().getPath());
            assertTrue(window.getBufferContext().getBuffer().getCurrentLineText().contains("needle in beta"));
        }
    }

    @Test
    void resultRowsShowLineNumbersOnTheLeftAndHighlightMatches() throws IOException {
        Path root = tempDir.resolve("styled-workspace");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Path current = root.resolve("src/current.txt");
        Path alpha = root.resolve("src/Alpha.java");
        Files.writeString(current, "current\n");
        Files.writeString(alpha, "first line\nneedle in alpha\n");

        var panel = ProjectSearchPanelView.create(Rect.create(0, 0, 0, 0), current);
        panel.setQuery("needle");

        var row = panel.buildResultRowForTest(80, 0, false);

        assertTrue(row.toString().startsWith("    2  src/Alpha.java:2:1  "));
        assertTrue(row.toString().contains("needle in alpha"));
        assertTrue(row.getFragments().stream()
                .anyMatch(fragment -> fragment.toString().contains("  2 ")
                        && UiTheme.ACCENT_GOLD.equals(fragment.getAttributes().foregroundColour())));
        assertTrue(row.getFragments().stream()
                .anyMatch(fragment -> fragment.toString().equals("needle")
                        && UiTheme.ACCENT_GREEN.equals(fragment.getAttributes().foregroundColour())));
    }
}
