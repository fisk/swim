package org.fisk.swim.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticServiceTest {
    private static final String PROVIDER = "diagnostic-service-test";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        DiagnosticService.getInstance().clearProvider(PROVIDER);
    }

    @Test
    void countsAggregatePerBufferAndProjectAndNavigateAcrossFiles() throws Exception {
        Path project = tempDir.resolve("project");
        Path first = project.resolve("src/First.java");
        Path second = project.resolve("src/Second.java");
        Files.createDirectories(first.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(first, "class First {}\n");
        Files.writeString(second, "class Second {}\n");

        DiagnosticService.getInstance().publish(PROVIDER, first.toUri().toString(), first,
                List.of(diagnostic(0, 1, DiagnosticSeverity.Warning, "warn first"),
                        diagnostic(2, 4, DiagnosticSeverity.Error, "error first")));
        DiagnosticService.getInstance().publish(PROVIDER, second.toUri().toString(), second,
                List.of(diagnostic(1, 0, DiagnosticSeverity.Error, "error second")));

        assertEquals(new DiagnosticCounts(1, 1), DiagnosticService.getInstance().countsForBuffer(first));
        assertEquals(new DiagnosticCounts(2, 1), DiagnosticService.getInstance().countsForProject(first));

        var next = DiagnosticService.getInstance().findNext(first, 0, 0, true, false);
        assertNotNull(next);
        assertEquals(first, next.path());
        assertEquals(0, next.startLine());

        var wrapped = DiagnosticService.getInstance().findNext(second, 10, 0, true, false);
        assertNotNull(wrapped);
        assertEquals(first, wrapped.path());
        assertEquals(0, wrapped.startLine());

        var errorOnly = DiagnosticService.getInstance().findNext(first, 0, 0, true, true);
        assertNotNull(errorOnly);
        assertEquals(first, errorOnly.path());
        assertEquals(2, errorOnly.startLine());
        assertEquals(DiagnosticSeverity.Error, errorOnly.severity());
    }

    @Test
    void diagnosticsForBufferMatchesEquivalentFileUrisByPath() throws Exception {
        Path file = tempDir.resolve("uri-spelling.txt");
        Files.writeString(file, "one\ntwo\n");
        var context = new BufferContext(Rect.create(0, 0, 40, 8), file);

        DiagnosticService.getInstance().publish(PROVIDER, file.toUri().toString(), file,
                List.of(diagnostic(1, 0, DiagnosticSeverity.Warning, "path matched warning")));

        assertEquals(1, DiagnosticService.getInstance().diagnosticsFor(context).size());
        assertEquals(DiagnosticSeverity.Warning, DiagnosticService.getInstance().lineSeverity(context, 1));

        DiagnosticService.getInstance().clear(PROVIDER, context.getBuffer().getURI().toString());
        assertEquals(0, DiagnosticService.getInstance().diagnosticsFor(context).size());
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
