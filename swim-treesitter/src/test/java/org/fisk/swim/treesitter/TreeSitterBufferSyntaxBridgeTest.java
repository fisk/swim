package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class TreeSitterBufferSyntaxBridgeTest {
    @Test
    void appliesOnlyTheCurrentBufferRevisionOnTheUiDispatcher() {
        var workers = new ControlledWorkers();
        var context = new BufferContext(Rect.create(0, 0, 80, 20), "static_cast<int>(value)", false);
        var buffer = context.getBuffer();
        var queuedUiWork = new ArrayList<Runnable>();
        try (var bridge = new TreeSitterBufferSyntaxBridge(workers, queuedUiWork::add,
                (snapshot, span) -> "STRING".equals(span.type()) ? TextColor.ANSI.RED : null)) {
            bridge.update(buffer, TreeSitterSubsetRuntimeTest.castGrammar(), "source_file");
            buffer.getCursor().setPosition(buffer.getLength() - 6);
            buffer.insert("new_");
            bridge.update(buffer, TreeSitterSubsetRuntimeTest.castGrammar(), "source_file");

            workers.runAll();
            queuedUiWork.forEach(Runnable::run);
        }

        assertTrue(buffer.getSyntaxFormatOverlays().stream()
                .anyMatch(range -> range.start() == 0 && range.end() == "static_cast".length()
                        && TextColor.ANSI.RED.equals(range.foregroundColour())));
    }

    @Test
    void bufferRejectsADelayedSyntaxOverlayForAnOlderRevision() {
        var context = new BufferContext(Rect.create(0, 0, 80, 20), "new", false);
        var buffer = context.getBuffer();
        int oldVersion = buffer.getVersion();
        buffer.insert("er");

        assertEquals(false, buffer.setSyntaxFormatOverlays(oldVersion, List.of()));
        assertTrue(buffer.getSyntaxFormatOverlays().isEmpty());
    }

    @Test
    void attachmentSubmitsTheInitialAndEditedBufferSnapshots() {
        var workers = new ControlledWorkers();
        var context = new BufferContext(Rect.create(0, 0, 80, 20), "static_cast<int>(value)", false);
        var buffer = context.getBuffer();
        var queuedUiWork = new ArrayList<Runnable>();
        try (var bridge = new TreeSitterBufferSyntaxBridge(workers, queuedUiWork::add,
                (snapshot, span) -> "STRING".equals(span.type()) ? TextColor.ANSI.GREEN : null);
                var ignored = bridge.attach(buffer, TreeSitterSubsetRuntimeTest::castGrammar, () -> "source_file")) {
            buffer.getCursor().setPosition(buffer.getLength() - 6);
            buffer.insert("latest_");
            workers.runAll();
            queuedUiWork.forEach(Runnable::run);
        }

        assertTrue(buffer.getSyntaxFormatOverlays().stream()
                .anyMatch(range -> TextColor.ANSI.GREEN.equals(range.foregroundColour())));
    }

    private static final class ControlledWorkers implements SwimPluginWorkers {
        private final List<Runnable> _tasks = new ArrayList<>();
        @Override public Thread start(Runnable task) { _tasks.add(task); return new Thread(task); }
        @Override public boolean isClosed() { return false; }
        @Override public void close() { }
        private void runAll() { for (Runnable task : _tasks) task.run(); }
    }
}
