package org.fisk.swim.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

public final class TmuxSession implements AutoCloseable {
    // Never connect tests to the user's server.
    private static final String TEST_SERVER = "swim-tests-" + ProcessHandle.current().pid()
            + "-" + Long.toUnsignedString(System.nanoTime(), 36);
    private static final Path TEST_CONFIG = testConfig();
    private final String _session;
    private final String _pane;
    private final String _server;

    static String executable() {
        return System.getProperty("swim.test.tmux", "tmux");
    }

    private static ProcessBuilder tmuxCommand(String server, String... arguments) {
        var command = new ArrayList<String>();
        command.add(executable());
        command.add("-L");
        command.add(server);
        command.add("-f");
        command.add(TEST_CONFIG.toString());
        command.addAll(java.util.List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        return builder;
    }

    private static Path testConfig() {
        try {
            Path config = Files.createTempFile("swim-tmux-test-", ".conf");
            Files.writeString(config, "set -g remain-on-exit on\nset -g default-shell /bin/sh\n");
            config.toFile().deleteOnExit();
            return config;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ProcessBuilder tmux(String... arguments) {
        return tmuxCommand(_server, arguments);
    }

    private TmuxSession(String session, String pane, String server) {
        _session = session;
        _pane = pane;
        _server = server;
    }

    public static TmuxSession start(Path workdir, Map<String, String> environment, String... command) throws Exception {
        return start(workdir, environment, 187, 51, command);
    }

    public static TmuxSession start(Path workdir, Map<String, String> environment, int columns, int rows,
            String... command) throws Exception {
        String session = "swim-it-" + System.nanoTime();
        String server = TEST_SERVER + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        var tmuxCommand = new ArrayList<String>();
        tmuxCommand.add("new-session");
        tmuxCommand.add("-d");
        tmuxCommand.add("-P");
        tmuxCommand.add("-F");
        tmuxCommand.add("#{pane_id}");
        tmuxCommand.add("-s");
        tmuxCommand.add(session);
        tmuxCommand.add("-x");
        tmuxCommand.add(Integer.toString(columns));
        tmuxCommand.add("-y");
        tmuxCommand.add(Integer.toString(rows));
        tmuxCommand.add("cd " + shellQuote(workdir.toString()) + " && " + environmentPrefix(environment)
                + joinShellCommand(command));
        var process = tmuxCommand(server, tmuxCommand.toArray(String[]::new)).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IOException("tmux new-session failed for " + session + ": " + output);
        }
        if (!output.matches("%[0-9]+")) {
            throw new IOException("tmux did not return a pane ID: " + output);
        }
        return new TmuxSession(session, output, server);
    }

    public void runCommand(String command) throws Exception {
        sendLiteral(":");
        sendLiteral(command);
        sendEnter();
    }

    public void sendLiteral(String text) throws Exception {
        var process = tmux("send-keys", "-t", _pane, "-l", text)
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException("tmux send-keys -l failed");
        }
        Thread.sleep(120);
    }

    public void sendLiteralKeyStrokes(String text) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                sendKey("Space");
            } else {
                sendLiteral(Character.toString(ch));
            }
        }
    }

    public void sendEnter() throws Exception {
        sendKey("Enter");
    }

    public void sendEscape() throws Exception {
        sendKey("Escape");
    }

    public void sendKey(String key) throws Exception {
        var process = tmux("send-keys", "-t", _pane, key)
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

    public void waitForEscapedText(Iterable<String> alternatives, Duration timeout) throws Exception {
        assertTrue(waitForPaneEscapedText(alternatives, timeout),
                "Expected escaped pane to contain one of " + alternatives + " within " + timeout + ".\nPane:\n"
                        + capturePaneWithEscapes());
    }

    public void waitForExit(Duration timeout) throws Exception {
        assertTrue(waitForSessionExit(timeout),
                "Expected editor session to exit within " + timeout + ".\nPane:\n" + capturePane());
    }

    public String capturePane() throws Exception {
        return capturePane("-S", "-200");
    }

    public String captureVisiblePane() throws Exception {
        return capturePane(new String[0]);
    }

    public String capturePaneWithEscapes() throws Exception {
        return capturePane("-e");
    }

    private String capturePane(String... options) throws Exception {
        var arguments = new ArrayList<String>(java.util.List.of(
                "capture-pane", "-p", "-t", _pane));
        arguments.addAll(java.util.List.of(options));
        var process = tmux(arguments.toArray(String[]::new)).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            return "tmux capture-pane failed: " + output;
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

    private boolean waitForPaneEscapedText(Iterable<String> alternatives, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (containsAny(capturePaneWithEscapes(), alternatives)) {
                return true;
            }
            Thread.sleep(100);
        }
        return containsAny(capturePaneWithEscapes(), alternatives);
    }

    private static boolean containsAny(String text, Iterable<String> alternatives) {
        if (text == null || alternatives == null) {
            return false;
        }
        for (String alternative : alternatives) {
            if (alternative != null && !alternative.isEmpty() && text.contains(alternative)) {
                return true;
            }
        }
        return false;
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
        var process = tmux("display-message", "-p", "-t", _pane, "#{pane_dead}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return process.waitFor() == 0 && "0".equals(output);
    }

    @Override
    public void close() throws Exception {
        var process = tmux("kill-session", "-t", "=" + _session)
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
