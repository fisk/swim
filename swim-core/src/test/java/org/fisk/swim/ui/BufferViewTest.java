package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.mode.VisualMode;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.TextLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

class BufferViewTest {
    private static final String PROVIDER = "buffer-view-test";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        DiagnosticService.getInstance().clearProvider(PROVIDER);
    }

    @Test
    void diagnosticBackgroundUsesSeverityColorForAffectedLine() throws Exception {
        Path path = tempDir.resolve("highlight.txt");
        Files.writeString(path, "alpha\nbeta\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 10), path);
        DiagnosticService.getInstance().publish(PROVIDER, context.getBuffer().getURI().toString(), path,
                List.of(diagnostic(1, 0, DiagnosticSeverity.Error, "bad line")));

        Method apply = BufferView.class.getDeclaredMethod("applyDiagnosticBackground", TextLayout.Glyph.class, AttributedString.class);
        apply.setAccessible(true);

        var glyph = context.getTextLayout().getGlyphs()
                .filter(candidate -> candidate.getPosition() == "alpha\n".length())
                .findFirst()
                .orElseThrow();
        var input = AttributedString.create("b", UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_BACKGROUND);
        var output = (AttributedString) apply.invoke(context.getBufferView(), glyph, input);
        var attributes = output.getFragments().get(0).getAttributes();

        assertEquals(UiTheme.DIAGNOSTIC_ERROR_BACKGROUND, attributes.backgroundColour());
        assertEquals(UiTheme.TEXT_PRIMARY, attributes.foregroundColour());
    }

    @Test
    void mouseHoverShowsDiagnosticPopupForAffectedLine() throws Exception {
        Path path = tempDir.resolve("hover.txt");
        Files.writeString(path, "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(1, 0, DiagnosticSeverity.Warning, "hover warning")));
            Method handle = BufferView.class.getDeclaredMethod("handleMouseAction", MouseAction.class);
            handle.setAccessible(true);
            handle.invoke(window.getBufferContext().getBufferView(),
                    new MouseAction(MouseActionType.MOVE, 0, new TerminalPosition(3, 3)));
            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertEquals("Line Diagnostics", popup.getTitle());
        }
    }

    @Test
    void mouseHoverShowsDiagnosticPopupFromLineNumberGutter() throws Exception {
        Path path = tempDir.resolve("hover-gutter.txt");
        Files.writeString(path, "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = window.getBufferContext().getBufferView();
            Point origin = absoluteScreenOrigin(view);
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(1, 0, DiagnosticSeverity.Error, "gutter hover error")));

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.MOVE, 0, new TerminalPosition(origin.getX(), origin.getY() + 1)));

            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertNotNull(popup);
            assertEquals("Line Diagnostics", popup.getTitle());
        }
    }

    @Test
    void mouseClickShowsDiagnosticPopupForAffectedLine() throws Exception {
        Path path = tempDir.resolve("click-diagnostic.txt");
        Files.writeString(path, "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = window.getBufferContext().getBufferView();
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY() + 1;
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(1, 0, DiagnosticSeverity.Warning, "click warning")));

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(textX + 1, textY)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_RELEASE, 0, new TerminalPosition(textX + 1, textY)));

            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertNotNull(popup);
            assertEquals("Line Diagnostics", popup.getTitle());
            assertEquals("click warning", popup.getEntries().get(0).message());
        }
    }

    @Test
    void mouseClickShowsDiagnosticPopupFromLineNumberGutter() throws Exception {
        Path path = tempDir.resolve("click-gutter-diagnostic.txt");
        Files.writeString(path, "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = window.getBufferContext().getBufferView();
            Point origin = absoluteScreenOrigin(view);
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(1, 0, DiagnosticSeverity.Warning, "gutter click warning")));

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(origin.getX(), origin.getY() + 1)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_RELEASE, 0, new TerminalPosition(origin.getX(), origin.getY() + 1)));

            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertNotNull(popup);
            assertEquals("Line Diagnostics", popup.getTitle());
            assertEquals("gutter click warning", popup.getEntries().get(0).message());
        }
    }

    @Test
    void currentLineDiagnosticsUsePhysicalLineForWrappedText() throws Exception {
        Path path = tempDir.resolve("wrapped-current-diagnostics.txt");
        Files.writeString(path, "abcdefghijklmnop\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 16, 10)) {
            var window = harness.getWindow();
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(0, 0, DiagnosticSeverity.Warning, "wrapped warning")));
            window.getBufferContext().getBuffer().getCursor().setPosition(14);

            assertTrue(window.showDiagnosticsForCurrentLine(true));

            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertNotNull(popup);
            assertEquals("wrapped warning", popup.getEntries().get(0).message());
        }
    }

    @Test
    void singletonMouseClickMovesCursorToClickedTextPosition() throws Exception {
        Path path = tempDir.resolve("click-cursor.txt");
        Files.writeString(path, "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var view = windowBufferView(harness);

            var response = view.processEvent(new KeyStrokes(List.of(
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(4, 2)))));

            assertEquals(Response.YES, response);
            assertEquals(2, harness.getWindow().getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    @Test
    void normalModeMouseDragEntersVisualModeAndSelectsDraggedText() throws Exception {
        Path path = tempDir.resolve("drag-visual.txt");
        Files.writeString(path, "abcdef\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = windowBufferView(harness);
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY();

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(textX + 1, textY)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.DRAG, 1, new TerminalPosition(textX + 3, textY)));

            assertEquals(window.getVisualMode(), window.getCurrentMode());
            assertEquals(2, window.getBufferContext().getBuffer().getCursors().size());
            assertEquals(3, window.getBufferContext().getBuffer().getCursor().getPosition());
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(1));
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(2));
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(3));
        }
    }

    @Test
    void visualModeMouseDragStartsNewSelectionFromClickedPosition() throws Exception {
        Path path = tempDir.resolve("drag-existing-visual.txt");
        Files.writeString(path, "abcdef\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = windowBufferView(harness);
            var buffer = window.getBufferContext().getBuffer();
            buffer.getCursor().setPosition(1);
            window.switchToMode(window.getVisualMode());
            buffer.getCursor().setPosition(4);
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY();

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(textX + 2, textY)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.DRAG, 1, new TerminalPosition(textX + 5, textY)));

            assertEquals(window.getVisualMode(), window.getCurrentMode());
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(2));
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(5));
            assertEquals(5, buffer.getCursor().getPosition());
        }
    }

    @Test
    void visualModeFillCoversSelectionThroughLineEndWithoutScreenCoordinateGaps() throws Exception {
        Path path = tempDir.resolve("visual-fill.txt");
        Files.writeString(path, "ab\ncd\n");

        var terminal = TerminalContextTestSupport.install(80, 10);
        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = windowBufferView(harness);
            var buffer = window.getBufferContext().getBuffer();
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY();
            buffer.getCursor().setPosition(1);
            window.switchToMode(window.getVisualMode());
            buffer.getCursor().setPosition(3);

            window.update(true);

            assertEquals(UiTheme.VISUAL_SELECTION_BACKGROUND, backgroundAt(terminal.drawCalls(), textX + 2, textY));
            assertEquals(UiTheme.VISUAL_SELECTION_BACKGROUND, backgroundAt(terminal.drawCalls(), textX, textY + 1));
        }
    }

    @Test
    void mouseReleaseCreatesVisualSelectionWhenTerminalDoesNotSendDragEvents() throws Exception {
        Path path = tempDir.resolve("release-visual.txt");
        Files.writeString(path, "abcdef\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = windowBufferView(harness);
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY();

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(textX + 1, textY)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_RELEASE, 0, new TerminalPosition(textX + 4, textY)));

            assertEquals(window.getVisualMode(), window.getCurrentMode());
            assertEquals(4, window.getBufferContext().getBuffer().getCursor().getPosition());
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(1));
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(4));
        }
    }

    @Test
    void mouseWheelScrollsBufferView() throws Exception {
        Path path = tempDir.resolve("mouse-wheel-scroll.txt");
        var builder = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            if (i > 1) {
                builder.append('\n');
            }
            builder.append("line ").append(i);
        }
        Files.writeString(path, builder.toString());

        try (var harness = HeadlessWindowHarness.create(path, 30, 10)) {
            var view = windowBufferView(harness);
            Point origin = absoluteScreenOrigin(view);

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.SCROLL_DOWN, 5, new TerminalPosition(origin.getX(), origin.getY())));

            assertEquals(1, view.getStartLine());
        }
    }

    @Test
    void simpleMouseClickReleaseKeepsNormalMode() throws Exception {
        Path path = tempDir.resolve("release-click.txt");
        Files.writeString(path, "abcdef\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var window = harness.getWindow();
            var view = windowBufferView(harness);
            Point origin = absoluteScreenOrigin(view);
            int textX = origin.getX() + view.getTextColumnStart();
            int textY = origin.getY();

            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(textX + 2, textY)));
            HeadlessWindowHarness.dispatch(view,
                    new MouseAction(MouseActionType.CLICK_RELEASE, 0, new TerminalPosition(textX + 2, textY)));

            assertEquals(window.getNormalMode(), window.getCurrentMode());
            assertEquals(2, window.getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    @Test
    void drawsLineNumbersAndLeavesWrappedContinuationGutterBlank() throws Exception {
        Path path = tempDir.resolve("gutter.txt");
        Files.writeString(path, "abcdefghijklmnop\nz");

        var terminal = TerminalContextTestSupport.install(16, 10);
        try (var harness = HeadlessWindowHarness.create(path, 16, 10)) {
            var window = harness.getWindow();
            window.update(true);

            char[][] cells = renderCells(terminal.drawCalls(), 16, 10);
            assertTrue(row(cells, 2).startsWith("1│abcdefghijklm"));
            assertTrue(row(cells, 3).startsWith(" │nop"));
            assertEquals('█', cells[2][15]);
        }
    }

    @Test
    void wrappedTextStopsBeforeScrollbarColumn() throws Exception {
        Path path = tempDir.resolve("wrap-scrollbar-boundary.txt");
        Files.writeString(path, "abcdefghijklmnop");

        var terminal = TerminalContextTestSupport.install(16, 10);
        try (var harness = HeadlessWindowHarness.create(path, 16, 10)) {
            var window = harness.getWindow();
            window.update(true);

            char[][] cells = renderCells(terminal.drawCalls(), 16, 10);
            assertEquals('m', cells[2][14]);
            assertEquals('█', cells[2][15]);
            assertEquals('o', cells[3][3]);
            assertEquals('█', cells[3][15]);
        }
    }

    @Test
    void diagnosticSeverityColorsLineNumbers() throws Exception {
        Path path = tempDir.resolve("gutter-diagnostics.txt");
        Files.writeString(path, "alpha\nbeta\ngamma\n");

        var terminal = TerminalContextTestSupport.install(20, 10);
        try (var harness = HeadlessWindowHarness.create(path, 20, 10)) {
            var window = harness.getWindow();
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(
                            diagnostic(0, 0, DiagnosticSeverity.Error, "bad line"),
                            diagnostic(1, 0, DiagnosticSeverity.Warning, "warn line")));
            window.update(true);

            assertEquals(UiTheme.DIAGNOSTIC_ERROR_FOREGROUND, foregroundAt(terminal.drawCalls(), 0, 2));
            assertEquals(UiTheme.DIAGNOSTIC_WARNING_FOREGROUND, foregroundAt(terminal.drawCalls(), 0, 3));
            assertEquals(UiTheme.TEXT_MUTED, foregroundAt(terminal.drawCalls(), 0, 4));
        }
    }

    @Test
    void wrappedErrorLineStillColorsSourceLineNumber() throws Exception {
        Path path = tempDir.resolve("wrapped-gutter-diagnostics.txt");
        Files.writeString(path, "abcdefghijklmnop\nbeta\n");

        var terminal = TerminalContextTestSupport.install(16, 10);
        try (var harness = HeadlessWindowHarness.create(path, 16, 10)) {
            var window = harness.getWindow();
            DiagnosticService.getInstance().publish(PROVIDER, window.getBufferContext().getBuffer().getURI().toString(), path,
                    List.of(diagnostic(0, 0, DiagnosticSeverity.Error, "wrapped error")));
            window.update(true);

            assertEquals(UiTheme.DIAGNOSTIC_ERROR_FOREGROUND, foregroundAt(terminal.drawCalls(), 0, 2));
            assertEquals(' ', renderCells(terminal.drawCalls(), 16, 10)[3][0]);
        }
    }

    @Test
    void scrollbarThumbMovesWhenViewportScrolls() throws Exception {
        Path path = tempDir.resolve("scrollbar.txt");
        var builder = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            if (i > 1) {
                builder.append('\n');
            }
            builder.append("line ").append(i);
        }
        Files.writeString(path, builder.toString());

        var terminal = TerminalContextTestSupport.install(20, 10);
        try (var harness = HeadlessWindowHarness.create(path, 20, 10)) {
            var window = harness.getWindow();
            var view = window.getBufferContext().getBufferView();

            window.update(true);
            char[][] initial = renderCells(terminal.drawCalls(), 20, 10);
            assertEquals('█', initial[2][19]);

            view.scrollPageDown();
            view.scrollPageDown();
            view.scrollPageDown();
            window.update(true);
            char[][] scrolled = renderCells(terminal.drawCalls(), 20, 10);
            assertEquals('│', scrolled[2][19]);
            assertTrue(thumbRow(scrolled, 19, 2, 8) > 2);
        }
    }

    private static Diagnostic diagnostic(int line, int character, DiagnosticSeverity severity, String message) {
        var diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(line, character), new Position(line, character + 1)));
        diagnostic.setSeverity(severity);
        diagnostic.setSource("test");
        diagnostic.setMessage(message);
        return diagnostic;
    }

    private static BufferView windowBufferView(HeadlessWindowHarness harness) {
        return harness.getWindow().getBufferContext().getBufferView();
    }

    private static Point absoluteScreenOrigin(View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        for (var parent = view.getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    private static char[][] renderCells(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls, int width, int height) {
        char[][] cells = new char[height][width];
        for (int y = 0; y < height; y++) {
            java.util.Arrays.fill(cells[y], ' ');
        }
        for (var call : drawCalls) {
            for (int index = 0; index < call.text().length(); index++) {
                int x = call.x() + index;
                int y = call.y();
                if (y < 0 || y >= height || x < 0 || x >= width) {
                    continue;
                }
                cells[y][x] = call.text().charAt(index);
            }
        }
        return cells;
    }

    private static String row(char[][] cells, int row) {
        return new String(cells[row]);
    }

    private static int thumbRow(char[][] cells, int column, int startRow, int endRowExclusive) {
        for (int row = startRow; row < endRowExclusive; row++) {
            if (cells[row][column] == '█') {
                return row;
            }
        }
        return -1;
    }

    private static TextColor foregroundAt(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls, int x, int y) {
        for (var call : drawCalls) {
            if (call.y() != y) {
                continue;
            }
            if (x < call.x() || x >= call.x() + call.text().length()) {
                continue;
            }
            return call.foreground();
        }
        throw new AssertionError("No draw call at " + x + "," + y + " in " + Arrays.toString(drawCalls.toArray()));
    }

    private static TextColor backgroundAt(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls, int x, int y) {
        TextColor result = null;
        for (var call : drawCalls) {
            if (call.y() != y) {
                continue;
            }
            if (x < call.x() || x >= call.x() + call.text().length()) {
                continue;
            }
            result = call.background();
        }
        if (result != null) {
            return result;
        }
        throw new AssertionError("No draw call at " + x + "," + y + " in " + Arrays.toString(drawCalls.toArray()));
    }
}
