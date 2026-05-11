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
}
