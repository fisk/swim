package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class PluginPanelView extends View {
    private final String _pluginId;
    private final SwimPanel _panel;
    private String _pendingInput;

    public PluginPanelView(Rect bounds, String pluginId, SwimPanel panel) {
        super(bounds);
        _pluginId = pluginId;
        _panel = panel;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
    }

    public String getPluginId() {
        return _pluginId;
    }

    public String getTitle() {
        return _panel.getTitle();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0) {
            return Response.NO;
        }
        String input = normalize(events.current().getKeyType(), events.current().getCharacter());
        if (input == null) {
            return Response.NO;
        }
        _pendingInput = input;
        return Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingInput == null) {
            return;
        }
        if ("esc".equals(_pendingInput) || "q".equals(_pendingInput)) {
            Window.getInstance().hidePanel();
            _pendingInput = null;
            return;
        }

        syncToCurrentPath();
        SwimPanelResult result = _panel.handleInput(_pendingInput, getBounds().getSize().getWidth(),
                getBounds().getSize().getHeight());
        if (result.openFile() != null) {
            if (Window.getInstance().setBufferPath(result.openFile())) {
                Window.getInstance().focusActiveBuffer();
            } else {
                Window.getInstance().getCommandView().setMessage("Failed to open file");
            }
        } else if (result.message() != null && !result.message().isBlank()) {
            Window.getInstance().getCommandView().setMessage(result.message());
        }
        if (result.handled()) {
            setNeedsRedraw();
        }
        _pendingInput = null;
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        syncToCurrentPath();
        List<String> lines = _panel.render(rect.getSize().getWidth(), rect.getSize().getHeight());
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int height = rect.getSize().getHeight();
        for (int row = 0; row < height; ++row) {
            String line = row < lines.size() ? lines.get(row) : "";
            Point point = Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row);
            if (row == 0) {
                UiTheme.drawLine(graphics, point, width,
                        AttributedString.create(" " + line.stripTrailing(), UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT),
                        UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
                continue;
            }
            TextColor background = line.startsWith("> ")
                    ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = line.startsWith("> ")
                    ? UiTheme.PANEL_SELECTION_FOREGROUND
                    : UiTheme.TEXT_PRIMARY;
            UiTheme.drawLine(graphics, point, width,
                    AttributedString.create(line, foreground, background),
                    foreground, background);
        }
    }

    private void syncToCurrentPath() {
        var window = Window.getInstance();
        if (window != null && window.getBufferContext() != null) {
            _panel.syncToCurrentPath(window.getBufferContext().getBuffer().getPath());
        }
    }

    private static String normalize(KeyType keyType, Character character) {
        return switch (keyType) {
        case ArrowUp -> "up";
        case ArrowDown -> "down";
        case ArrowLeft -> "left";
        case ArrowRight -> "right";
        case Enter -> "enter";
        case Escape -> "esc";
        case Character -> character == null ? null : switch (character) {
        case ' ' -> "space";
        default -> String.valueOf(character);
        };
        default -> null;
        };
    }
}
