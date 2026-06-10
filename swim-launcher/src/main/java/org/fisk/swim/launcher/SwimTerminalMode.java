package org.fisk.swim.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class SwimTerminalMode implements AutoCloseable {
    private final String _state;

    private SwimTerminalMode(String state) {
        _state = state;
    }

    static SwimTerminalMode enterRawMode() {
        String state = runStty("-g");
        if (state == null || state.isBlank()) {
            return new SwimTerminalMode(null);
        }
        runStty("raw", "-echo");
        return new SwimTerminalMode(state);
    }

    @Override
    public void close() {
        if (_state != null && !_state.isBlank()) {
            runStty(_state);
        }
    }

    static TerminalSize currentSize() {
        String envRows = System.getenv("SWIM_TTY_ROWS");
        String envColumns = System.getenv("SWIM_TTY_COLS");
        Integer rows = parsePositiveInt(envRows);
        Integer columns = parsePositiveInt(envColumns);
        if (rows != null && columns != null) {
            return new TerminalSize(rows, columns);
        }
        String output = runStty("size");
        if (output == null) {
            return TerminalSize.defaultSize();
        }
        String[] parts = output.trim().split("\\s+");
        if (parts.length != 2) {
            return TerminalSize.defaultSize();
        }
        rows = parsePositiveInt(parts[0]);
        columns = parsePositiveInt(parts[1]);
        return rows == null || columns == null ? TerminalSize.defaultSize() : new TerminalSize(rows, columns);
    }

    private static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String runStty(String... args) {
        var command = new StringBuilder("stty");
        for (String arg : args) {
            command.append(' ').append(SwimJavaCommand.shellQuote(arg));
        }
        command.append(" < /dev/tty");
        ProcessBuilder builder = new ProcessBuilder(List.of("/bin/sh", "-c", command.toString()))
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (process.waitFor() != 0) {
                return null;
            }
            return output.toString().trim();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    record TerminalSize(int rows, int columns) {
        private static TerminalSize defaultSize() {
            return new TerminalSize(24, 80);
        }
    }
}
