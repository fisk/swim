package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

import com.googlecode.lanterna.TextColor;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeLineViewTest {
    @TempDir
    Path tempDir;

    @Test
    void statusStringIncludesModeRelativePathAndCursorPosition() throws Exception {
        Path project = tempDir.resolve("project");
        Path nested = project.resolve("src");
        Path file = nested.resolve("Main.txt");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("pom.xml"), "<project />");
        Files.writeString(file, "hello");

        withUserDir(nested, () -> {
            try (var harness = HeadlessWindowHarness.create(file, 40, 8)) {
                var window = harness.getWindow();
                window.getBufferContext().getBuffer().getCursor().setPosition(2);

                var rendered = invoke(window.getModeLineView(), "getString", AttributedString.class).toString();

                assertTrue(rendered.contains("NORMAL"));
                assertTrue(rendered.contains("src/Main.txt"));
                assertTrue(rendered.contains("3: 1, 3"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void modeColorTracksCurrentMode() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("mode-line.txt", "abc"), 40, 8)) {
            var window = harness.getWindow();

            window.switchToMode(window.getInputMode());

            assertEquals(UiTheme.MODE_INPUT, invoke(window.getModeLineView(), "getModeColor", TextColor.class));
        }
    }

    @Test
    void heapBarUsesCommittedCapacityAndThresholdColors() {
        long mib = 1024L * 1024L;
        var green = new MemoryUsage(0, 50 * mib, 100 * mib, 200 * mib);
        var yellow = new MemoryUsage(0, 70 * mib, 100 * mib, 200 * mib);
        var red = new MemoryUsage(0, 90 * mib, 100 * mib, 200 * mib);

        assertEquals(100 * mib, ModeLineView.heapCapacityBytes(green));
        assertEquals(5, ModeLineView.heapBarFilledColumns(green, 10));
        assertEquals("50/100M", ModeLineView.heapLabel(green));
        assertEquals(UiTheme.ACCENT_GREEN, ModeLineView.heapBarColor(green));
        assertEquals(UiTheme.ACCENT_GOLD, ModeLineView.heapBarColor(yellow));
        assertEquals(UiTheme.ACCENT_RED, ModeLineView.heapBarColor(red));
    }

    @Test
    void leftSegmentsUsePowerlineTransitionsBetweenBackgroundBlocks() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("mode-line-powerline.txt", "abc"), 50, 8)) {
            var window = harness.getWindow();

            AttributedString rendered = invoke(window.getModeLineView(), "getLeftString", AttributedString.class);

            assertEquals(" NORMAL ", fragmentText(rendered, 0));
            assertEquals(UiTheme.MODE_NORMAL, background(rendered, 0));
            assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(rendered, 1));
            assertEquals(UiTheme.SURFACE_ACCENT, background(rendered, 1));
            assertEquals(UiTheme.MODE_NORMAL, foreground(rendered, 1));
            assertTrue(fragmentText(rendered, 2).contains("mode-line-powerline.txt"));
            assertEquals(UiTheme.SURFACE_ACCENT, background(rendered, 2));
            assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(rendered, 3));
            assertEquals(UiTheme.MODELINE_BACKGROUND, background(rendered, 3));
            assertTrue(fragmentText(rendered, 4).contains(Powerline.SYMBOL_LN));
            assertEquals(UiTheme.MODELINE_BACKGROUND, background(rendered, 4));
        }
    }

    @Test
    void rightSegmentsUseMirroredPowerlineTransitionsAroundGitBranch() throws Exception {
        Path project = tempDir.resolve("repo");
        Path nested = project.resolve("src");
        Path file = nested.resolve("Main.txt");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("pom.xml"), "<project />");
        Files.writeString(file, "hello");
        runGit(project, "git init -b feature/mode-line");

        withUserDir(nested, () -> {
            try (var harness = HeadlessWindowHarness.create(file, 80, 8)) {
                AttributedString rendered = invoke(harness.getWindow().getModeLineView(), "getRightString", AttributedString.class);

                assertEquals(Powerline.SYMBOL_FILLED_LEFT_ARROW, fragmentText(rendered, 0));
                assertEquals(UiTheme.MODELINE_BACKGROUND, background(rendered, 0));
                assertEquals(UiTheme.SURFACE_ACCENT, foreground(rendered, 0));
                assertEquals(" " + Powerline.SYMBOL_BRANCH + " feature/mode-line ", fragmentText(rendered, 1));
                assertEquals(UiTheme.SURFACE_ACCENT, background(rendered, 1));
                assertEquals(Powerline.SYMBOL_FILLED_LEFT_ARROW, fragmentText(rendered, 2));
                assertEquals(UiTheme.SURFACE_ACCENT, background(rendered, 2));
                assertEquals(UiTheme.SURFACE_MUTED, foreground(rendered, 2));
                assertTrue(fragmentText(rendered, 3).startsWith(" "));
                assertEquals(UiTheme.SURFACE_MUTED, background(rendered, 3));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static void withUserDir(Path path, Runnable runnable) {
        String original = System.getProperty("user.dir");
        System.setProperty("user.dir", path.toString());
        try {
            runnable.run();
        } finally {
            System.setProperty("user.dir", original);
        }
    }

    private static <T> T invoke(Object target, String name, Class<T> type) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return type.cast(method.invoke(target));
    }

    private static String fragmentText(AttributedString line, int fragmentIndex) {
        return line.getFragments().get(fragmentIndex).toString();
    }

    private static TextColor foreground(AttributedString line, int fragmentIndex) throws Exception {
        var attributes = line.getFragments().get(fragmentIndex).getAttributes();
        var field = attributes.getClass().getDeclaredField("_foregroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }

    private static TextColor background(AttributedString line, int fragmentIndex) throws Exception {
        var attributes = line.getFragments().get(fragmentIndex).getAttributes();
        var field = attributes.getClass().getDeclaredField("_backgroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }

    private static void runGit(Path cwd, String command) throws IOException, InterruptedException {
        var process = new ProcessBuilder("zsh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException(new String(process.getInputStream().readAllBytes()));
        }
    }
}
