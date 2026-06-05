package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.fisk.swim.EventThread;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class NemoChatIT {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        NemoClient.getInstance().resetForTests();
        EventThread.shutdownInstance();
    }

    @Test
    @Timeout(15)
    void chatsAcrossMultipleTurnsAndCompletesToolRoundWithoutErrors() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startServer(requestCount, List.of(
                functionCallResponse(),
                textResponse("Tool-assisted answer"),
                textResponse("Follow-up answer")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Which files are not committed?");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Tool-assisted answer");

                submit(panel, "And what file am I in?");
                waitForLine(panel, "nemo> Follow-up answer");

                var transcript = displayLines(panel);
                assertTrue(transcript.stream().anyMatch(line -> line.contains("me> Which files are not committed?")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("nemo> Tool-assisted answer")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("me> And what file am I in?")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("nemo> Follow-up answer")));
                assertFalse(transcript.stream().anyMatch(line -> line.contains("Nemo failed")));
                assertTrue(requestCount.get() >= 3);
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void appliesWriteFileToolToCurrentBufferAndDisk() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startServer(requestCount, List.of(
                writeFileResponse("chat.txt", "class Updated {}\n"),
                textResponse("Updated file")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Update this file");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Updated file");

                assertTrue(requestCount.get() >= 2);
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("me> Update this file")));
                assertEquals("class Updated {}\n", Files.readString(tempDir.resolve("chat.txt")));
                assertEquals("class Updated {}\n", window.getBufferContext().getBuffer().getString());
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void supportsMultipleSessionsWorkersAndHistoryPersistence() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startServer(requestCount, 2000, List.of(
                textResponse("First answer"),
                textResponse("Second answer")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            Path file = writeFile("chat.txt", "class Demo {}\n");
            try {
                try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                    EventThread.getInstance().start();
                    var window = harness.getWindow();

                    NemoClient.getInstance().run(window.getBufferContext(), "First question");
                    var panel = waitForPanel(window);
                    waitForThinking(panel);

                    submit(panel, ":new Review");
                    panel = waitForPanel(window);
                    waitForLine(panel, "Created session-2 (Review).");

                    submit(panel, "Second question");
                    waitForThinking(panel);

                    submit(panel, ":workers");
                    waitForLine(panel, "Workers:");
                    assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-1")));
                    assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-2")));

                    String session2Answer = waitForAnyAnswer(panel);
                    submit(panel, ":switch session-1");
                    panel = waitForPanel(window);
                    waitForAnyAnswer(panel);
                    String session1Answer = findAnswer(panel);
                    assertTrue(session1Answer != null && session2Answer != null);
                    assertFalse(session1Answer.equals(session2Answer));
                    assertTrue(List.of(session1Answer, session2Answer).contains("First answer"));
                    assertTrue(List.of(session1Answer, session2Answer).contains("Second answer"));
                }

                assertTrue(Files.isRegularFile(tempDir.resolve(".swim/nemo/sessions.json")));

                NemoClient.getInstance().resetForTests();
                EventThread.shutdownInstance();

                try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                    EventThread.getInstance().start();
                    var window = harness.getWindow();

                    NemoClient.getInstance().run(window.getBufferContext(), "");
                    var panel = waitForPanel(window);
                    String restoredSession1Answer = waitForAnyAnswer(panel);

                    submit(panel, ":sessions");
                    waitForLine(panel, "Sessions:");
                    assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-1")));
                    assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-2")));
                    assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("me> :sessions")));

                    submit(panel, ":switch session-2");
                    panel = waitForPanel(window);
                    String restoredSession2Answer = waitForAnyAnswer(panel);
                    assertFalse(restoredSession1Answer.equals(restoredSession2Answer));
                    assertTrue(List.of(restoredSession1Answer, restoredSession2Answer).contains("First answer"));
                    assertTrue(List.of(restoredSession1Answer, restoredSession2Answer).contains("Second answer"));
                }
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }


    @Test
    @Timeout(15)
    void restoresPersistedHistoryImmediatelyWhenOpeningPane() throws Exception {
        String originalUserHome = switchToTempUserHome();
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), "");
        Files.writeString(configDir.resolve("nemo/sessions.json"),
                """
                        {
                          "next_session_number": 2,
                          "active_session_id": "session-1",
                          "workspace_sessions": {
                            "%s": "session-1"
                          },
                          "sessions": [
                            {
                              "id": "session-1",
                              "title": "Session 1",
                              "workspace_root": "%s",
                              "created_at_millis": 1,
                              "updated_at_millis": 2,
                              "turns": [
                                { "speaker": "me", "text": "Earlier question", "include_in_prompt": true },
                                { "speaker": "nemo", "text": "Earlier answer", "include_in_prompt": true }
                              ]
                            }
                          ]
                        }
                        """.formatted(tempDir.toAbsolutePath(), tempDir.toAbsolutePath()));
        Path file = writeFile("chat.txt", "class Demo {}\n");
        try {
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);
                var transcript = displayLines(panel);
                assertTrue(transcript.stream().anyMatch(line -> line.contains("me> Earlier question")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("nemo> Earlier answer")));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @Timeout(20)
    void listsAndAbortsWorkersAcrossSessions() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startServer(requestCount, 3000, List.of(
                textResponse("First answer"),
                textResponse("Second answer")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "First question");
                var panel = waitForPanel(window);
                waitForThinking(panel);

                submit(panel, ":new Review");
                panel = waitForPanel(window);
                waitForLine(panel, "Created session-2 (Review).");

                submit(panel, "Second question");
                waitForThinking(panel);

                submit(panel, ":workers");
                waitForLine(panel, "Workers:");
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-1")));
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-2")));

                submit(panel, ":abort all");
                waitForLine(panel, "Aborted 2 workers.");
                submit(panel, ":workers");
                waitForLine(panel, "No Nemo workers running.");

                Thread.sleep(3500);
                var transcript = displayLines(panel);
                assertFalse(transcript.stream().anyMatch(line -> line.contains("First answer")));
                assertFalse(transcript.stream().anyMatch(line -> line.contains("Second answer")));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void renamesAndDeletesSessionsAndPersistsMetadata() throws Exception {
        String originalUserHome = switchToTempUserHome();
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), "");
        Path file = writeFile("chat.txt", "class Demo {}\n");
        try {
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);

                submit(panel, ":new Review");
                panel = waitForPanel(window);
                waitForLine(panel, "Created session-2 (Review).");

                submit(panel, ":rename Review Session");
                panel = waitForPanel(window);
                waitForLine(panel, "Renamed session-2 to Review Session.");

                submit(panel, ":delete session-1");
                waitForLine(panel, "Deleted session-1.");

                submit(panel, ":sessions");
                waitForLine(panel, "Sessions:");
                var transcript = displayLines(panel);
                assertFalse(transcript.stream().anyMatch(line -> line.contains("session-1 |")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("session-2 | Review Session")));
            }

            NemoClient.getInstance().resetForTests();
            EventThread.shutdownInstance();

            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);

                submit(panel, ":sessions");
                waitForLine(panel, "Sessions:");
                var transcript = displayLines(panel);
                assertFalse(transcript.stream().anyMatch(line -> line.contains("session-1 |")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("session-2 | Review Session")));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @Timeout(15)
    void resetClearsCurrentSessionWithoutDeletingIt() throws Exception {
        String originalUserHome = switchToTempUserHome();
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), """
                {
                  "provider": "openai",
                  "apiKey": ""
                }
                """);
        Path file = writeFile("reset.txt", "class Demo {}\n");
        try {
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);

                submit(panel, "hello nemo");
                waitForLine(panel, "Set api_key in");

                submit(panel, ":reset");
                waitForNoLine(panel, "hello nemo");
                waitForNoLine(panel, "Set api_key in");

                submit(panel, ":sessions");
                waitForLine(panel, "Sessions:");
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("session-1")));
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private HttpServer startServer(AtomicInteger requestCount, List<JsonObject> responses) throws IOException {
        return startServer(requestCount, 0, responses);
    }

    private HttpServer startServer(AtomicInteger requestCount, long responseDelayMillis, List<JsonObject> responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> handleResponse(exchange, requestCount, responseDelayMillis, responses));
        server.start();
        return server;
    }

    private void handleResponse(HttpExchange exchange, AtomicInteger requestCount, long responseDelayMillis, List<JsonObject> responses) throws IOException {
        int requestIndex = requestCount.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        JsonObject response = responses.get(Math.min(requestIndex - 1, responses.size() - 1));
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static JsonObject functionCallResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("id", "chatcmpl-tool");
        response.addProperty("object", "chat.completion");
        response.addProperty("created", 1);
        response.addProperty("model", "gpt-5.4");
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("content", com.google.gson.JsonNull.INSTANCE);
        JsonArray toolCalls = new JsonArray();
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", "call_1");
        toolCall.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", "list_files");
        function.addProperty("arguments", "{\"path\":\".\",\"max_results\":5}");
        toolCall.add("function", function);
        toolCalls.add(toolCall);
        message.add("tool_calls", toolCalls);
        choice.add("message", message);
        choice.addProperty("finish_reason", "tool_calls");
        choices.add(choice);
        response.add("choices", choices);
        response.add("usage", usage(5000, 100));
        return response;
    }

    private static JsonObject writeFileResponse(String path, String content) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "chatcmpl-write");
        response.addProperty("object", "chat.completion");
        response.addProperty("created", 1);
        response.addProperty("model", "gpt-5.4");
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("content", com.google.gson.JsonNull.INSTANCE);
        JsonArray toolCalls = new JsonArray();
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", "call_write");
        toolCall.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", "write_file");
        function.addProperty("arguments", "{\"path\":\"" + path + "\",\"content\":\"" + content.replace("\n", "\\n").replace("\"", "\\\"") + "\"}");
        toolCall.add("function", function);
        toolCalls.add(toolCall);
        message.add("tool_calls", toolCalls);
        choice.add("message", message);
        choice.addProperty("finish_reason", "tool_calls");
        choices.add(choice);
        response.add("choices", choices);
        response.add("usage", usage(5000, 100));
        return response;
    }

    private static JsonObject textResponse(String text) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "chatcmpl-text");
        response.addProperty("object", "chat.completion");
        response.addProperty("created", 1);
        response.addProperty("model", "gpt-5.4");
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text);
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        choices.add(choice);
        response.add("choices", choices);
        response.add("usage", usage(5000, 100));
        return response;
    }

    private void writeConfig(HttpServer server) throws IOException {
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), String.join("\n",
                "provider=openai-compatible",
                "api_key=test-token",
                "model=gpt-5.4",
                "base_url=http://127.0.0.1:" + server.getAddress().getPort(),
                "tool.list_files=true",
                "tool.read_file=true",
                "tool.search_files=true",
                "tool.run_command=false",
                "tool.write_file=true",
                "tool.web_search=false"));
    }

    private static JsonObject usage(int promptTokens, int completionTokens) {
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", promptTokens);
        usage.addProperty("completion_tokens", completionTokens);
        usage.addProperty("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    private String switchToTempUserHome() {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        NemoClient.getInstance().resetForTests();
        return originalUserHome;
    }

    private ChatPanelView waitForPanel(org.fisk.swim.ui.Window window) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Object panel = HeadlessWindowHarness.getField(window, "_panelView");
            if (panel instanceof ChatPanelView chatPanelView) {
                return chatPanelView;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for Nemo chat panel");
    }

    private void waitForLine(ChatPanelView panel, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<String> lines = displayLines(panel);
            if (lines.stream().anyMatch(line -> line.contains(expected))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for line: " + expected + "\nCurrent lines: " + displayLines(panel));
    }

    private void waitForNoLine(ChatPanelView panel, String unwanted) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<String> lines = displayLines(panel);
            if (lines.stream().noneMatch(line -> line.contains(unwanted))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for line to disappear: " + unwanted + "\nCurrent lines: " + displayLines(panel));
    }

    private void waitForThinking(ChatPanelView panel) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (displayLines(panel).stream().anyMatch(line -> line.contains("*thinking*"))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for pending worker. Current lines: " + displayLines(panel));
    }

    private String waitForAnyAnswer(ChatPanelView panel) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String answer = findAnswer(panel);
            if (answer != null) {
                return answer;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Nemo answer. Current lines: " + displayLines(panel));
    }

    private String findAnswer(ChatPanelView panel) throws Exception {
        for (String line : displayLines(panel)) {
            if (line.contains("nemo> First answer")) {
                return "First answer";
            }
            if (line.contains("nemo> Second answer")) {
                return "Second answer";
            }
        }
        return null;
    }

    private static void submit(ChatPanelView panel, String text) {
        for (char character : text.toCharArray()) {
            panel.processEvent(new org.fisk.swim.event.KeyStrokes(List.of(new KeyStroke(character, false, false))));
            panel.respond();
        }
        panel.processEvent(new org.fisk.swim.event.KeyStrokes(List.of(new KeyStroke(KeyType.Enter))));
        panel.respond();
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    @SuppressWarnings("unchecked")
    private static List<String> displayLines(ChatPanelView panel) throws Exception {
        var method = ChatPanelView.class.getDeclaredMethod("getDisplayLines");
        method.setAccessible(true);
        return (List<String>) method.invoke(panel);
    }
}
