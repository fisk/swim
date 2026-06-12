package org.fisk.swim.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsConfigAndRoundTripsSession() throws Exception {
        EditorPaths paths = new EditorPaths(tempDir, tempDir.resolve("config.json"), tempDir.resolve("session.json"));
        Files.writeString(paths.configPath(), """
                {
                  "normalModeRemaps": [
                    {
                      "lhs": "Q",
                      "rhs": "x"
                    }
                  ],
                  "startupCommands": [
                    "help"
                  ],
                  "options": {
                    "indent.java.size": "2"
                  },
                  "theme": {
                    "name": "night-test",
                    "colors": {
                      "mode.normal": "#ff00ff",
                      "git.commit.hash": "accent.gold"
                    }
                  },
                  "restoreLastSession": true
                }
                """);

        EditorConfig config = EditorConfigStore.load(paths);
        assertEquals(List.of(new NormalModeRemap("Q", "x")), config.normalModeRemaps());
        assertEquals(List.of("help"), config.startupCommands());
        assertEquals(Map.of("indent.java.size", "2"), config.options());
        assertEquals("night-test", config.theme().name());
        assertEquals("#ff00ff", config.theme().colors().get("mode.normal"));
        assertEquals("accent.gold", config.theme().colors().get("git.commit.hash"));
        assertTrue(config.restoreLastSession());

        EditorConfigStore.saveSession(paths, new EditorSession(List.of("/tmp/a.txt", "/tmp/b.txt"), "/tmp/b.txt", List.of(), 0));
        EditorSession restored = EditorConfigStore.loadSession(paths);
        assertEquals(List.of("/tmp/a.txt", "/tmp/b.txt"), restored.openBuffers());
        assertEquals("/tmp/b.txt", restored.activeBuffer());
    }
}
