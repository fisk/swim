package org.fisk.swim.terminal;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.fisk.swim.event.KeyStroke;

/** Shared, recording implementation of the SWIM terminal boundary for UI tests. */
public final class TerminalContextTestSupport {
    private TerminalContextTestSupport() { }

    public static InstalledTerminalContext install(int columns, int rows) {
        return install(columns, rows, null, () -> new TerminalDimensions(columns, rows));
    }

    public static InstalledTerminalContext install(int columns, int rows, Throwable ignored) {
        return install(columns, rows, ignored, () -> new TerminalDimensions(columns, rows));
    }

    public static InstalledTerminalContext install(int columns, int rows, Throwable ignored,
            Supplier<TerminalDimensions> dimensionsSupplier) {
        var backend = new RecordingBackend(columns, rows, dimensionsSupplier);
        var context = new TerminalContext(backend);
        try {
            Field field = TerminalContext.class.getDeclaredField("_instance");
            field.setAccessible(true);
            field.set(null, context);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return new InstalledTerminalContext(context, backend.stopCalls, backend.clearCalls, backend.resizeCalls,
                backend.dimensions, backend.drawCalls, backend.refreshCalls, backend.cursorPosition, backend.cursorVisible,
                backend.terminalWrites);
    }

    public record InstalledTerminalContext(
            TerminalContext context,
            AtomicInteger stopCalls,
            AtomicInteger clearCalls,
            AtomicInteger resizeCalls,
            AtomicReference<TerminalDimensions> screenSize,
            List<DrawCall> drawCalls,
            List<Boolean> refreshCalls,
            AtomicReference<CursorPosition> cursorPosition,
            AtomicReference<Boolean> cursorVisible,
            List<String> terminalWrites) { }

    public record CursorPosition(int column, int row) {
        public int getColumn() { return column; }
        public int getRow() { return row; }
    }

    public record DrawCall(int x, int y, String text, TextColor foreground, TextColor background) { }

    private static final class RecordingBackend implements TerminalBackend {
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger clearCalls = new AtomicInteger();
        private final AtomicInteger resizeCalls = new AtomicInteger();
        private final AtomicReference<TerminalDimensions> dimensions;
        private final Supplier<TerminalDimensions> dimensionsSupplier;
        private final List<DrawCall> drawCalls = new CopyOnWriteArrayList<>();
        private final List<Boolean> refreshCalls = new CopyOnWriteArrayList<>();
        private final AtomicReference<CursorPosition> cursorPosition = new AtomicReference<>(new CursorPosition(0, 0));
        private final AtomicReference<Boolean> cursorVisible = new AtomicReference<>(false);
        private final List<String> terminalWrites = new CopyOnWriteArrayList<>();
        private final TerminalGraphics graphics = (column, row, text, style) ->
                drawCalls.add(new DrawCall(column, row, text, toTextColor(style.foreground()), toTextColor(style.background())));

        private RecordingBackend(int columns, int rows, Supplier<TerminalDimensions> dimensionsSupplier) {
            dimensions = new AtomicReference<>(new TerminalDimensions(columns, rows));
            this.dimensionsSupplier = dimensionsSupplier;
        }

        @Override public void start() { }
        @Override public void stop() { stopCalls.incrementAndGet(); }
        @Override public void clear() { clearCalls.incrementAndGet(); }
        @Override public void refresh() { refreshCalls.add(Boolean.TRUE); }
        @Override public TerminalDimensions dimensions() { return dimensions.get(); }
        @Override public TerminalDimensions resizeIfNeeded() {
            TerminalDimensions next = dimensionsSupplier.get();
            if (next == null || next.equals(dimensions.get())) return null;
            dimensions.set(next);
            resizeCalls.incrementAndGet();
            return next;
        }
        @Override public TerminalGraphics graphics() { return graphics; }
        @Override public KeyStroke pollInput() { return null; }
        @Override public void setCursorPosition(int column, int row) { cursorPosition.set(new CursorPosition(column, row)); }
        @Override public void setCursorVisible(boolean visible) { cursorVisible.set(visible); }
        @Override public void setCursorShape(TerminalCursorShape shape) { terminalWrites.add(shape.escapeSequence()); }

        private static TextColor toTextColor(AnsiColour colour) {
            return colour.defaultColour() ? TextColor.ANSI.DEFAULT
                    : new TextColor.RGB(colour.red(), colour.green(), colour.blue());
        }
    }
}
