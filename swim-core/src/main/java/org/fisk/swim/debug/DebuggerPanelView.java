package org.fisk.swim.debug;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.ui.Point;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.View;

import com.googlecode.lanterna.TextColor;

public final class DebuggerPanelView extends View {
    private sealed interface Row permits HeaderRow, ThreadRow, FrameRow, VariableRow, BreakpointRow {
    }

    private record HeaderRow(String label) implements Row {
    }

    private record ThreadRow(int index, DebugThreadInfo thread) implements Row {
    }

    private record FrameRow(int index, DebugFrameInfo frame) implements Row {
    }

    private record VariableRow(DebugVariable variable) implements Row {
    }

    private record BreakpointRow(DebugBreakpoint breakpoint) implements Row {
    }

    private int _selection;
    private int _scroll;
    private Runnable _pendingAction;

    public DebuggerPanelView(Rect bounds) {
        super(bounds);
        setBackgroundColour(TextColor.ANSI.DEFAULT);
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0) {
            return Response.NO;
        }
        var window = org.fisk.swim.ui.Window.getInstance();
        if (window != null && window.isEditorDriveSandboxActive()) {
            _pendingAction = () -> window.blockEditorDriveAction("debugger",
                    "debugger actions are outside the editor-control sandbox");
            return Response.YES;
        }
        String input = normalize(events.current().getKeyType(), events.current().getCharacter(),
                events.current().isCtrlDown(), events.current().isAltDown());
        if (input == null) {
            return Response.NO;
        }
        var rows = rows();
        _pendingAction = switch (input) {
        case "j", "down" -> () -> _selection = Math.min(Math.max(0, rows.size() - 1), _selection + 1);
        case "k", "up" -> () -> _selection = Math.max(0, _selection - 1);
        case "c" -> runAction(DebuggerManager::resume);
        case "n" -> runAction(DebuggerManager::stepOver);
        case "i" -> runAction(DebuggerManager::stepInto);
        case "o" -> runAction(DebuggerManager::stepOut);
        case "s" -> runAction(DebuggerManager::stop);
        case "b", "B" -> runAction(DebuggerManager::toggleBreakpointAtCursor);
        case "enter" -> actionForRow(rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(_selection, rows.size() - 1))));
        case "q", "esc" -> () -> org.fisk.swim.ui.Window.getInstance().hidePanel();
        default -> null;
        };
        return _pendingAction == null ? Response.NO : Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingAction != null) {
            _pendingAction.run();
            _pendingAction = null;
            setNeedsRedraw();
        }
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        var snapshot = DebuggerManager.snapshot();
        AttributedString.create(" " + snapshot.title(), TextColor.ANSI.WHITE, TextColor.ANSI.BLUE)
                .drawAt(rect.getPoint(), graphics);

        var rows = rows();
        int bodyHeight = Math.max(0, rect.getSize().getHeight() - 1);
        clampSelection(rows.size());
        adjustScroll(bodyHeight, rows.size());
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            Point point = Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i);
            if (index >= rows.size()) {
                continue;
            }
            String line = renderRow(rows.get(index));
            boolean selected = index == _selection;
            AttributedString.create((selected ? "> " : "  ") + line,
                    selected ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE,
                    selected ? TextColor.ANSI.YELLOW : TextColor.ANSI.DEFAULT)
                    .drawAt(point, graphics);
        }
    }

    private List<Row> rows() {
        var snapshot = DebuggerManager.snapshot();
        var rows = new ArrayList<Row>();
        rows.add(new HeaderRow("State: " + snapshot.state() + " — "
                + (snapshot.message() == null ? "" : snapshot.message())));
        rows.add(new HeaderRow("Breakpoints"));
        snapshot.breakpoints().forEach(breakpoint -> rows.add(new BreakpointRow(breakpoint)));
        rows.add(new HeaderRow("Threads"));
        for (int i = 0; i < snapshot.threads().size(); i++) {
            rows.add(new ThreadRow(i, snapshot.threads().get(i)));
        }
        rows.add(new HeaderRow("Frames"));
        for (int i = 0; i < snapshot.frames().size(); i++) {
            rows.add(new FrameRow(i, snapshot.frames().get(i)));
        }
        rows.add(new HeaderRow("Variables"));
        snapshot.variables().forEach(variable -> rows.add(new VariableRow(variable)));
        if (rows.size() == 4) {
            rows.add(new HeaderRow("No debugger session. Use :debug providers or :debug <provider> ..."));
        }
        return rows;
    }

    private String renderRow(Row row) {
        return switch (row) {
        case HeaderRow header -> header.label();
        case BreakpointRow breakpoint -> breakpoint.breakpoint().displayLabel();
        case ThreadRow thread -> thread.thread().displayLabel();
        case FrameRow frame -> frame.frame().displayLabel();
        case VariableRow variable -> variable.variable().displayLabel();
        };
    }

    private Runnable actionForRow(Row row) {
        if (row instanceof ThreadRow threadRow) {
            return runAction(() -> DebuggerManager.selectThread(threadRow.index()));
        }
        if (row instanceof FrameRow frameRow) {
            return runAction(() -> DebuggerManager.selectFrame(frameRow.index()));
        }
        return null;
    }

    private Runnable runAction(ThrowingRunnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Exception e) {
                var window = org.fisk.swim.ui.Window.getInstance();
                if (window != null && window.getCommandView() != null) {
                    window.getCommandView().setMessage(e.getMessage());
                }
            }
        };
    }

    private void clampSelection(int size) {
        _selection = Math.max(0, Math.min(_selection, Math.max(0, size - 1)));
    }

    private void adjustScroll(int bodyHeight, int rowCount) {
        if (_selection < _scroll) {
            _scroll = _selection;
        } else if (_selection >= _scroll + bodyHeight) {
            _scroll = _selection - bodyHeight + 1;
        }
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, rowCount - Math.max(1, bodyHeight))));
    }

    private static String normalize(com.googlecode.lanterna.input.KeyType keyType, Character character, boolean ctrlDown,
            boolean altDown) {
        return switch (keyType) {
        case ArrowUp -> "up";
        case ArrowDown -> "down";
        case Enter -> "enter";
        case Escape -> "esc";
        case Character -> {
            if (character == null || altDown) {
                yield null;
            }
            if (ctrlDown) {
                yield "ctrl-" + Character.toLowerCase(character);
            }
            yield String.valueOf(character);
        }
        default -> null;
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
