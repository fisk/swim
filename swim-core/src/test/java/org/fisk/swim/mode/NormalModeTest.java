package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormalModeTest {
    @TempDir
    Path tempDir;

    @Test
    void bangStartsNemoChat() throws Exception {
        Path path = tempDir.resolve("bang-opens-nemo.txt");
        Files.writeString(path, "abc");

        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        Files.createDirectories(tempDir.resolve(".swim"));
        Files.writeString(tempDir.resolve(".swim/nemo.conf"), "");
        resetNemoClientForTests();
        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('!'));

            assertTrue(window.getPanelView() instanceof ChatPanelView);
        } finally {
            resetNemoClientForTests();
            System.setProperty("user.home", originalUserHome);
        }
    }

    private static void resetNemoClientForTests() throws Exception {
        Class<?> nemoClientClass = Class.forName("org.fisk.swim.nemo.NemoClient");
        Object instance = nemoClientClass.getMethod("getInstance").invoke(null);
        Method reset = nemoClientClass.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(instance);
    }
}
