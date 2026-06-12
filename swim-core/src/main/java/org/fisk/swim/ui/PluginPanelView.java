package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimTextSpan;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class PluginPanelView extends View implements KeyBindingHintProvider {
    private static final Logger LOG = LoggerFactory.getLogger(PluginPanelView.class);

    private final String _pluginId;
    private final SwimPanel _panel;
    private final boolean _workspaceClose;
    private String _pendingInput;

    public PluginPanelView(Rect bounds, String pluginId, SwimPanel panel) {
        this(bounds, pluginId, panel, false);
    }

    public PluginPanelView(Rect bounds, String pluginId, SwimPanel panel, boolean workspaceClose) {
        super(bounds);
        _pluginId = pluginId;
        _panel = panel;
        _workspaceClose = workspaceClose;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
    }

    public String getPluginId() {
        return _pluginId;
    }

    public String getTitle() {
        return _panel.getTitle();
    }

    @Override
    public String keyHintContext() {
        String title = _panel.getTitle();
        return title == null || title.isBlank() ? "plugin panel" : title;
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        var panelHints = _panel.keyBindingHints().stream()
                .map(hint -> KeyBindingHint.of(hint.key(), hint.group(), hint.summary()))
                .toList();
        if (!panelHints.isEmpty()) {
            return panelHints;
        }
        return List.of(
                KeyBindingHint.of("<UP>", "Plugin", "send up"),
                KeyBindingHint.of("<DOWN>", "Plugin", "send down"),
                KeyBindingHint.of("<ENTER>", "Plugin", "send enter"),
                KeyBindingHint.of("q", "Panel", "close if unhandled"),
                KeyBindingHint.of("<ESC>", "Panel", "close if unhandled"));
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0) {
            return Response.NO;
        }
        String input = normalize(events.current().getKeyType(), events.current().getCharacter(),
                events.current().isCtrlDown(), events.current().isAltDown());
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
        syncToCurrentPath();
        SwimPanelResult result;
        try {
            result = _panel.handleInput(_pendingInput, getBounds().getSize().getWidth(),
                    getBounds().getSize().getHeight());
        } catch (Throwable e) {
            LOG.warn("Plugin panel input failed for {}: {}", _pluginId, errorSummary(e));
            LOG.debug("Plugin panel input failure details", e);
            Window window = Window.getInstance();
            if (window != null) {
                window.getCommandView().setMessage("Plugin input failed: " + errorSummary(e));
            }
            _pendingInput = null;
            setNeedsRedraw();
            return;
        }
        if (!result.handled() && ("esc".equals(_pendingInput) || "q".equals(_pendingInput))) {
            if (_workspaceClose) {
                Window.getInstance().closeCurrentWorkspaceWindow();
            } else {
                Window.getInstance().hidePanel();
            }
            _pendingInput = null;
            return;
        }
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
        List<SwimPanelLine> lines;
        try {
            lines = _panel.renderRich(rect.getSize().getWidth(), rect.getSize().getHeight());
            if (lines == null) {
                lines = List.of();
            }
        } catch (Throwable e) {
            LOG.warn("Plugin panel render failed for {}: {}", _pluginId, errorSummary(e));
            LOG.debug("Plugin panel render failure details", e);
            lines = List.of(
                    SwimPanelLine.plain(errorTitle()),
                    SwimPanelLine.plain("Render failed: " + errorSummary(e)),
                    SwimPanelLine.plain("Press q or <esc> to close this panel."));
        }
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int height = rect.getSize().getHeight();
        for (int row = 0; row < height; ++row) {
            SwimPanelLine line = row < lines.size() ? lines.get(row) : SwimPanelLine.plain("");
            String text = line.text();
            Point point = Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row);
            if (row == 0) {
                UiTheme.drawLine(graphics, point, width,
                        AttributedString.create(" " + text.stripTrailing(), UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT),
                        UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
                continue;
            }
            TextColor background = text.startsWith("> ")
                    ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = text.startsWith("> ")
                    ? UiTheme.PANEL_SELECTION_FOREGROUND
                    : UiTheme.TEXT_PRIMARY;
            UiTheme.drawLine(graphics, point, width, attributed(line, foreground, background), foreground, background);
        }
    }

    private String errorTitle() {
        String title = _panel.getTitle();
        return title == null || title.isBlank() ? "Plugin Error" : title + " Error";
    }

    private static String errorSummary(Throwable e) {
        String name = e == null ? "unknown error" : e.getClass().getSimpleName();
        String message = e == null ? "" : e.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private static AttributedString attributed(SwimPanelLine line, TextColor fallbackForeground,
            TextColor fallbackBackground) {
        var attributed = new AttributedString();
        for (SwimTextSpan span : line.spans()) {
            attributed.append(span.text(), color(span.foreground(), fallbackForeground),
                    color(span.background(), fallbackBackground));
        }
        return attributed;
    }

    private static TextColor color(String value, TextColor fallback) {
        return UiTheme.resolve(value, fallback);
    }

    private void syncToCurrentPath() {
        var window = Window.getInstance();
        if (window != null && window.getBufferContext() != null) {
            _panel.syncToCurrentPath(window.getBufferContext().getBuffer().getPath());
        }
    }

    private static String normalize(KeyType keyType, Character character, boolean ctrlDown, boolean altDown) {
        return switch (keyType) {
        case ArrowUp -> "up";
        case ArrowDown -> "down";
        case ArrowLeft -> "left";
        case ArrowRight -> "right";
        case Tab -> "tab";
        case Backspace -> "backspace";
        case Enter -> "enter";
        case Escape -> "esc";
        case Character -> {
            if (character == null || altDown) {
                yield null;
            }
            if (ctrlDown) {
                yield "ctrl-" + Character.toLowerCase(character);
            }
            yield switch (character) {
            case ' ' -> "space";
            default -> String.valueOf(character);
            };
        }
        default -> null;
        };
    }
}
