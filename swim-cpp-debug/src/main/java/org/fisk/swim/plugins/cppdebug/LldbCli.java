package org.fisk.swim.plugins.cppdebug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class LldbCli implements AutoCloseable {
    private static final String INITIAL_PROMPT = "(lldb)";
    private static final String PROMPT = "__SWIM_LLDB__>";

    private final Process _process;
    private final BufferedWriter _writer;
    private final BlockingQueue<String> _responses = new LinkedBlockingQueue<>();
    private final Thread _readerThread;

    private LldbCli(Process process) {
        _process = process;
        _writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        _readerThread = Thread.ofVirtual().name("swim-lldb-reader").start(this::readLoop);
    }

    static LldbCli launch(Path executable, List<String> args) throws Exception {
        var command = new ArrayList<String>();
        if (java.nio.file.Files.isExecutable(Path.of("/usr/bin/script"))) {
            command.add("/usr/bin/script");
            command.add("-q");
            command.add("/dev/null");
        }
        command.add("lldb");
        command.add("--no-lldbinit");
        command.add(executable.toString());
        if (!args.isEmpty()) {
            command.add("--");
            command.addAll(args);
        }
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var cli = new LldbCli(process);
        cli.awaitResponse(Duration.ofSeconds(5), INITIAL_PROMPT);
        cli.execute("settings set prompt " + PROMPT);
        cli.execute("settings set auto-confirm true");
        cli.execute("settings set stop-line-count-before 0");
        cli.execute("settings set stop-line-count-after 0");
        return cli;
    }

    String execute(String command) throws Exception {
        _writer.write(command);
        _writer.newLine();
        _writer.flush();
        return awaitResponse(Duration.ofSeconds(30), PROMPT);
    }

    private String awaitResponse(Duration timeout, String prompt) throws InterruptedException, IOException {
        String response = _responses.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (response == null) {
            throw new IOException("Timed out waiting for lldb response");
        }
        return response;
    }

    private void readLoop() {
        try (var reader = new InputStreamReader(_process.getInputStream(), StandardCharsets.UTF_8)) {
            var buffer = new StringBuilder();
            char[] chars = new char[256];
            for (;;) {
                int count = reader.read(chars);
                if (count < 0) {
                    if (!buffer.isEmpty()) {
                        _responses.offer(buffer.toString());
                    }
                    return;
                }
                buffer.append(chars, 0, count);
                int promptIndex;
                while ((promptIndex = nextPromptIndex(buffer)) >= 0) {
                    int promptLength = promptLengthAt(buffer, promptIndex);
                    String chunk = buffer.substring(0, promptIndex);
                    _responses.offer(chunk);
                    buffer.delete(0, promptIndex + promptLength);
                }
            }
        } catch (IOException e) {
            _responses.offer("error: " + e.getMessage());
        }
    }

    private static int nextPromptIndex(StringBuilder buffer) {
        int configured = buffer.indexOf(PROMPT);
        int initial = buffer.indexOf(INITIAL_PROMPT);
        if (configured < 0) {
            return initial;
        }
        if (initial < 0) {
            return configured;
        }
        return Math.min(configured, initial);
    }

    private static int promptLengthAt(StringBuilder buffer, int index) {
        if (index >= 0 && buffer.indexOf(PROMPT) == index) {
            return PROMPT.length();
        }
        return INITIAL_PROMPT.length();
    }

    @Override
    public void close() throws Exception {
        try {
            if (_process.isAlive()) {
                try {
                    execute("quit");
                } catch (Exception e) {
                }
            }
        } finally {
            _process.destroyForcibly();
        }
    }
}
