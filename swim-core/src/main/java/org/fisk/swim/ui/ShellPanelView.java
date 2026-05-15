package org.fisk.swim.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ShellPanelView extends ChatPanelView {
    private final Process _process;
    private final AtomicBoolean _closed = new AtomicBoolean();
    private final OutputStream _stdin;

    public ShellPanelView(Rect bounds, String title, Consumer<String> onSubmit, String shellCommand)
            throws IOException {
        super(bounds, title, onSubmit, ShellPanelView::handleShellCommandStatic, ignored -> {},
                ShellPanelView::shellCommandMenuState, PromptStyle.shell());
        _process = new ProcessBuilder(shellCommand, "-i")
                .redirectErrorStream(true)
                .start();
        _stdin = _process.getOutputStream();
        startOutputPump();
    }

    public static ShellPanelView createDefault(Window window, Rect bounds) throws IOException {
        AtomicReference<ShellPanelView> ref = new AtomicReference<>();
        ShellPanelView view = new ShellPanelView(bounds, "Shell", command -> ref.get().submitShellCommand(command),
                detectShellCommand());
        ref.set(view);
        return view;
    }

    private static String detectShellCommand() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            return "zsh";
        }
        return shell;
    }

    private void startOutputPump() {
        var thread = new Thread(() -> pumpOutput(_process.getInputStream()), "swim-shell-output");
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpOutput(InputStream stream) {
        try (stream) {
            var buffer = new byte[4096];
            var text = new StringBuilder();
            int read;
            while ((read = stream.read(buffer)) != -1) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                flushCompleteOutput(text, false);
            }
            flushCompleteOutput(text, true);
        } catch (IOException e) {
            if (!_closed.get()) {
                appendMessage("shell", "[shell error: " + e.getMessage() + "]");
            }
        }
    }

    private void flushCompleteOutput(StringBuilder text, boolean flushRemainder) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                emitShellOutput(text.substring(start, i));
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (flushRemainder && start < text.length()) {
            emitShellOutput(text.substring(start));
            text.setLength(0);
        } else if (start > 0) {
            text.delete(0, start);
        }
    }

    private void emitShellOutput(String line) {
        appendMessage("shell", line);
    }

    private void submitShellCommand(String command) {
        appendMessage("me", command);
        try {
            _stdin.write(command.getBytes(StandardCharsets.UTF_8));
            _stdin.write('\n');
            _stdin.flush();
        } catch (IOException e) {
            appendMessage("shell", "[write failed: " + e.getMessage() + "]");
        }
    }

    private static void handleShellCommandStatic(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (":q".equals(trimmed) || ":quit".equals(trimmed)) {
            var window = Window.getInstance();
            if (window != null) {
                window.hidePanel();
            }
        }
    }

    @Override
    public void appendMessage(String speaker, String text) {
        super.appendMessage(normalizeSpeaker(speaker), text);
    }

    private static String normalizeSpeaker(String speaker) {
        if ("shell".equals(speaker)) {
            return "nemo";
        }
        return speaker;
    }

    @Override
    public void removeFromParent() {
        closeProcess();
        super.removeFromParent();
    }

    @Override
    public void respond() {
        super.respond();
        var window = Window.getInstance();
        if (window != null && window.getPanelView() != this) {
            closeProcess();
        }
    }

    private void closeProcess() {
        if (_closed.compareAndSet(false, true)) {
            try {
                _stdin.close();
            } catch (IOException ignored) {
            }
            _process.destroy();
        }
    }

    private static CommandView.CommandMenuState shellCommandMenuState(String text) {
        return CommandView.CommandMenuState.forCommandText(text, 0,
                List.of(new CommandView.CommandSpec("q", List.of("quit"), "", "close shell panel")));
    }
}
