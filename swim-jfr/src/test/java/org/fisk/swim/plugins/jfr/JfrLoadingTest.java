package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JfrLoadingTest {
    @Test
    void failedBackgroundLoadIsReportedAndRenderingIsCached() {
        var panel = new JfrPanel(Path.of("missing-test-recording.jfr"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!String.join("\n", panel.render(80, 30)).contains("Not a .jfr")) {
                Thread.sleep(10);
            }
        });
        List<String> first = panel.render(80, 30);
        assertSame(first, panel.render(80, 30));
        assertNotSame(first, panel.render(100, 30));
    }

    @Test
    void newerRequestSupersedesPendingLoad() {
        var panel = new JfrPanel(Path.of("old-missing.jfr"));
        panel.syncToCurrentPath(Path.of("swim-jfr-recordings:new-missing.jfr"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!String.join("\n", panel.render(80, 30)).contains("new-missing.jfr")) {
                Thread.sleep(10);
            }
        });
        assertFalse(String.join("\n", panel.render(80, 30)).contains("old-missing.jfr"));
    }
}
