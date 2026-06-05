package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.TextLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalPosition;
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
                    new MouseAction(MouseActionType.MOVE, 0, new TerminalPosition(1, 3)));
            var popup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertEquals("Line Diagnostics", popup.getTitle());
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
}
