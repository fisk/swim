package org.fisk.swim.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

public final class TmuxSession implements AutoCloseable {
    private final String _session;

    private TmuxSession(String session) {
        _session = session;
    }

    public static TmuxSession start(Path workdir, Map<String, String> environment, String... command) throws Exception {
        String session = "swim-it-" + System.nanoTime();
        var tmuxCommand = new ArrayList<String>();
        tmuxCommand.add("tmux");
        tmuxCommand.add("new-session");
        tmuxCommand.add("-d");
        tmuxCommand.add("-s");
        tmuxCommand.add(session);
        tmuxCommand.add("-x");
        tmuxCommand.add("187");
        tmuxCommand.add("-y");
        tmuxCommand.add("51");
        tmuxCommand.add("cd " + shellQuote(workdir.toString()) + " && " + environmentPrefix(environment)
                + joinShellCommand(command));
        var process = new ProcessBuilder(tmuxCommand)
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException("tmux new-session failed for " + session);
        }
        return new TmuxSession(session);
    }

    public void runCommand(String command) throws Exception {
        sendLiteral(":");
        sendLiteral(command);
        sendEnter();
    }

    public void sendLiteral(String text) throws Exception {
        var process = new ProcessBuilder("tmux", "send-keys", "-t", _session, "-l", text)
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException("tmux send-keys -l failed");
        }
        Thread.sleep(120);
    }

    public void sendEnter() throws Exception {
        sendKey("Enter");
    }

    public void sendEscape() throws Exception {
        sendKey("Escape");
    }

    public void sendKey(String key) throws Exception {
        var process = new ProcessBuilder("tmux", "send-keys", "-t", _session, key)
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException("tmux send-keys failed for key " + key);
        }
        Thread.sleep(150);
    }

    public void waitForText(String text, Duration timeout) throws Exception {
        assertTrue(waitForPaneText(text, timeout),
                "Expected pane to contain [" + text + "] within " + timeout + ".\nPane:\n" + capturePane());
    }

    public void waitForExit(Duration timeout) throws Exception {
        assertTrue(waitForSessionExit(timeout),
                "Expected editor session to exit within " + timeout + ".\nPane:\n" + capturePane());
    }

    public String capturePane() throws Exception {
        var process = new ProcessBuilder("tmux", "capture-pane", "-pt", _session, "-S", "-200")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            return "";
        }
        return output;
    }

    private boolean waitForPaneText(String text, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (capturePane().contains(text)) {
                return true;
            }
            Thread.sleep(100);
        }
        return capturePane().contains(text);
    }

    private boolean waitForSessionExit(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!sessionExists()) {
                return true;
            }
            Thread.sleep(100);
        }
        return !sessionExists();
    }

    private boolean sessionExists() throws Exception {
        var process = new ProcessBuilder("tmux", "has-session", "-t", _session)
                .redirectErrorStream(true)
                .start();
        return process.waitFor() == 0;
    }

    @Override
    public void close() throws Exception {
        var process = new ProcessBuilder("tmux", "kill-session", "-t", _session)
                .redirectErrorStream(true)
                .start();
        process.waitFor();
    }

    private static String environmentPrefix(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder("env");
        for (var entry : environment.entrySet()) {
            builder.append(' ')
                    .append(shellQuote(entry.getKey() + "=" + entry.getValue()));
        }
        builder.append(' ');
        return builder.toString();
    }

    private static String joinShellCommand(String... command) {
        var builder = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(shellQuote(command[i]));
        }
        return builder.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
