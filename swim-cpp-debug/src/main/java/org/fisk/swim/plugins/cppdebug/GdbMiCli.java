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

final class GdbMiCli implements AutoCloseable {
    private static final String PROMPT = "(gdb)";

    private final Process _process;
    private final BufferedWriter _writer;
    private final BlockingQueue<String> _responses = new LinkedBlockingQueue<>();
    private final Thread _readerThread;

    private GdbMiCli(Process process) {
        _process = process;
        _writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        _readerThread = Thread.ofVirtual().name("swim-gdb-reader").start(this::readLoop);
    }

    static GdbMiCli launch(Path executable, List<String> args) throws Exception {
        var command = new ArrayList<String>();
        command.add("gdb");
        command.add("--quiet");
        command.add("--interpreter=mi2");
        command.add(executable.toString());
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var cli = new GdbMiCli(process);
        cli.awaitResponse(Duration.ofSeconds(5));
        if (!args.isEmpty()) {
            cli.execute("-exec-arguments " + String.join(" ", args));
        }
        return cli;
    }

    String execute(String command) throws Exception {
        _writer.write(command);
        _writer.newLine();
        _writer.flush();
        return awaitResponse(Duration.ofSeconds(30));
    }

    private String awaitResponse(Duration timeout) throws IOException, InterruptedException {
        String response = _responses.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (response == null) {
            throw new IOException("Timed out waiting for gdb response");
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
                while (buffer.indexOf(PROMPT) >= 0) {
                    int promptIndex = buffer.indexOf(PROMPT);
                    String chunk = buffer.substring(0, promptIndex);
                    _responses.offer(chunk);
                    buffer.delete(0, promptIndex + PROMPT.length());
                }
            }
        } catch (IOException e) {
            _responses.offer("^error,msg=\"" + e.getMessage() + "\"");
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (_process.isAlive()) {
                try {
                    execute("-gdb-exit");
                } catch (Exception e) {
                }
            }
        } finally {
            _process.destroyForcibly();
        }
    }
}
