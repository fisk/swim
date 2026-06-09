package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.googlecode.lanterna.TextColor;

import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimTextSpan;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.junit.jupiter.api.Test;

class PluginPanelViewTest {
    @Test
    void drawUsesRichPanelLineStyles() {
        var terminal = TerminalContextTestSupport.install(40, 6);
        var view = new PluginPanelView(Rect.create(0, 0, 40, 4), "test", new RichPanel());

        view.draw(view.getBounds());

        assertEquals(TextColor.Factory.fromString("#173d22"), backgroundAt(terminal.drawCalls(), 0, 1));
        assertEquals(TextColor.Factory.fromString("#4a2020"), backgroundAt(terminal.drawCalls(), 0, 2));
    }

    @Test
    void drawContainsPluginRenderFailures() {
        var terminal = TerminalContextTestSupport.install(60, 6);
        var view = new PluginPanelView(Rect.create(0, 0, 60, 4), "broken", new BrokenPanel());

        assertDoesNotThrow(() -> view.draw(view.getBounds()));

        assertTrue(terminal.drawCalls().stream()
                .anyMatch(call -> call.text().contains("Render failed: StackOverflowError: overflow")));
    }

    private static TextColor backgroundAt(List<TerminalContextTestSupport.DrawCall> drawCalls, int x, int y) {
        TextColor result = null;
        for (var call : drawCalls) {
            if (call.y() != y || x < call.x() || x >= call.x() + call.text().length()) {
                continue;
            }
            result = call.background();
        }
        if (result == null) {
            throw new AssertionError("No draw call at " + x + "," + y);
        }
        return result;
    }

    private static final class RichPanel implements SwimPanel {
        @Override
        public String getId() {
            return "test";
        }

        @Override
        public String getTitle() {
            return "Test";
        }

        @Override
        public List<String> render(int width, int height) {
            return List.of("fallback");
        }

        @Override
        public List<SwimPanelLine> renderRich(int width, int height) {
            return List.of(
                    SwimPanelLine.plain("Test"),
                    SwimPanelLine.of(SwimTextSpan.styled("+added", "#d9ffe2", "#173d22")),
                    SwimPanelLine.of(SwimTextSpan.styled("-removed", "#ffd8d8", "#4a2020")));
        }

        @Override
        public SwimPanelResult handleInput(String input, int width, int height) {
            return SwimPanelResult.ignored();
        }
    }

    private static final class BrokenPanel implements SwimPanel {
        @Override
        public String getId() {
            return "broken";
        }

        @Override
        public String getTitle() {
            return "Broken";
        }

        @Override
        public List<String> render(int width, int height) {
            return List.of();
        }

        @Override
        public List<SwimPanelLine> renderRich(int width, int height) {
            throw new StackOverflowError("overflow");
        }

        @Override
        public SwimPanelResult handleInput(String input, int width, int height) {
            return SwimPanelResult.ignored();
        }
    }
}
