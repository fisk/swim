package org.fisk.swim.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspContextServiceTest {
    private static final String PROVIDER = "lsp-context-test";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        LspContextService.getInstance().clearProvider(PROVIDER);
    }

    @Test
    void contextForCursorChoosesInnermostScope() throws Exception {
        Path file = tempDir.resolve("Main.java");
        Files.writeString(file, """
                class Main {
                  void run() {
                    System.out.println();
                  }
                }
                """);
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        LspContextService.getInstance().publish(
                PROVIDER,
                file.toUri().toString(),
                file,
                1,
                List.of(
                        new LspContextService.Scope("Main", 0, 0, 4, 1),
                        new LspContextService.Scope("Main.run", 1, 2, 3, 3)));

        context.getBuffer().getCursor().setPosition(context.getBuffer().getPositionAtLineColumn(2, 4));

        assertEquals("Main.run", LspContextService.getInstance().contextFor(context));
    }

    @Test
    void contextForCursorFallsBackToContainingClass() throws Exception {
        Path file = tempDir.resolve("Outer.java");
        Files.writeString(file, """
                class Outer {
                  int field;
                }
                """);
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        LspContextService.getInstance().publish(
                PROVIDER,
                file.toUri().toString(),
                file,
                1,
                List.of(new LspContextService.Scope("Outer", 0, 0, 2, 1)));

        context.getBuffer().getCursor().setPosition(context.getBuffer().getPositionAtLineColumn(1, 4));

        assertEquals("Outer", LspContextService.getInstance().contextFor(context));
    }
}
