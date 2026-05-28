package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DesktopSupportTest {
    @Test
    void browserCommandUsesAbsoluteOpenPathOnMac() {
        assertEquals(List.of("/usr/bin/open", "https://example.com"),
                DesktopSupport.browserCommand("Mac OS X", "https://example.com"));
    }

    @Test
    void browserCommandUsesXdgOpenOnLinux() {
        String executable = DesktopSupport.browserCommand("Linux", "https://example.com").getFirst();
        assertEquals("xdg-open", java.nio.file.Path.of(executable).getFileName().toString());
    }
}
