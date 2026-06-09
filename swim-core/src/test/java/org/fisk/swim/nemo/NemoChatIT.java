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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.EventThread;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
                toolCallResponse("call_1", "list_files", "{\"path\":\".\",\"max_results\":5}"),
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
                assertTrue(transcript.stream().anyMatch(line -> line.contains("tool> list_files: path=.")));
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
    void delegateTaskRunsSubAgentAndReturnsResultToParent() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startDelegationServer(requestCount);
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Delegate this inspection");
                var panel = waitForPanel(window);
                waitForLine(panel, "tool> delegate_task: title=Docs");
                waitForLine(panel, "nemo> Parent saw delegated start.");

                var workerToolConfiguration = NemoClient.Configuration.builder()
                        .workspaceRoot(tempDir)
                        .build();
                String status = NemoClient.executeTool(workerToolConfiguration, window.getBufferContext(),
                        new NemoClient.ToolCall("status", "worker_status", json(Map.of())));
                assertTrue(status.contains("session-2 | Docs | running"));

                submit(panel, ":workers");
                waitForLine(panel, "session-2 | Docs");

                String joined = NemoClient.executeTool(workerToolConfiguration, window.getBufferContext(),
                        new NemoClient.ToolCall("join", "join_worker", json(Map.of(
                                "session_id", "session-2",
                                "timeout_seconds", 5))));
                assertTrue(joined.contains("Sub-agent completed."));

                submit(panel, ":switch session-2");
                panel = waitForPanel(window);
                waitForLine(panel, "nemo> Sub-agent completed.");

                assertTrue(requestCount.get() >= 3);
                assertFalse(displayLines(panel).stream().anyMatch(line -> line.contains("Nemo failed")));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void parentPaneApprovesSubAgentCommandWithoutSwitchingSessions() throws Exception {
        var requestCount = new AtomicInteger();
        String commandArguments = "{\"command\":\"printf ok; touch subagent-approved.txt\"}";
        var server = startSubAgentApprovalServer(requestCount, commandArguments);
        try {
            writeApprovalConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 20)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Delegate a command");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Parent saw delegated start.");
                waitForLine(panel, "Approval required: command policy escalation");
                waitForLine(panel, "session: session-2 | Build");
                assertEquals("approval options", panel.getCommandMenuState().title());
                assertEquals("Approve once", panel.getCommandMenuState().selectedMatch().displayLabel());

                dispatch(panel, new KeyStroke(KeyType.Enter));
                waitForLine(panel, "Approved approval-1 for session-2 (Build).");

                var workerToolConfiguration = NemoClient.Configuration.builder()
                        .workspaceRoot(tempDir)
                        .build();
                String joined = NemoClient.executeTool(workerToolConfiguration, window.getBufferContext(),
                        new NemoClient.ToolCall("join", "join_worker", json(Map.of(
                                "session_id", "session-2",
                                "timeout_seconds", 5))));
                assertTrue(joined.contains("Sub-agent command approved."));
                assertTrue(Files.exists(tempDir.resolve("subagent-approved.txt")));
                assertTrue(requestCount.get() >= 4);
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void tellQueuesMessageForRunningWorkerAndWorkerReceivesFollowUp() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<>("");
        var server = startServer(requestCount, requestBody, 600, List.of(
                textResponse("First answer"),
                textResponse("Queued answer")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 20)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "First question");
                var panel = waitForPanel(window);
                waitForThinking(panel);

                submit(panel, ":new Coordinator");
                panel = waitForPanel(window);
                waitForLine(panel, "Created session-2 (Coordinator).");

                submit(panel, ":tell session-1 Please include the queued correction.");
                waitForLine(panel, "Queued message for session-1 (Session 1).");

                submit(panel, ":switch session-1");
                panel = waitForPanel(window);
                waitForLine(panel, "nemo> Queued answer");

                assertTrue(requestBody.get().contains("Additional user message(s) arrived while this worker was already running."));
                assertTrue(requestBody.get().contains("Please include the queued correction."));
                assertTrue(requestCount.get() >= 2);
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void chatCallsApprovedMcpToolAndReturnsResultToModel() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<>("");
        var server = startServer(requestCount, requestBody, 0, List.of(
                toolCallResponse("call_mcp", "mcp__mock__echo", "{\"message\":\"hello from mcp\"}"),
                textResponse("MCP answer")));
        try {
            writeMcpConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 20)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Use the MCP echo tool");
                var panel = waitForPanel(window);
                waitForLine(panel, "Approval required: MCP tool call");
                assertEquals("approval options", panel.getCommandMenuState().title());
                assertEquals("Approve once", panel.getCommandMenuState().selectedMatch().displayLabel());

                dispatch(panel, new KeyStroke(KeyType.Enter));
                waitForLine(panel, "tool> mcp__mock__echo: message=hello from mcp -> echo: hello from mcp");
                waitForLine(panel, "nemo> MCP answer");

                assertTrue(requestBody.get().contains("mcp__mock__echo"));
                assertTrue(requestBody.get().contains("echo: hello from mcp"));
                assertTrue(requestCount.get() >= 2);
                assertFalse(displayLines(panel).stream().anyMatch(line -> line.contains("Nemo failed")));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void openAiCompatibleUsesLangChainChatResponses() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startServer(requestCount, List.of(textResponse("Hello from langchain4j")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Hi");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Hello from langchain4j");

                assertEquals(1, requestCount.get());
                assertFalse(displayLines(panel).stream().anyMatch(line -> line.contains("Nemo failed")));
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
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("-class Demo {}")));
                assertTrue(displayLines(panel).stream().anyMatch(line -> line.contains("+class Updated {}")));
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
    void approvesRestrictedCommandAndPersistsExactRule() throws Exception {
        var requestCount = new AtomicInteger();
        String arguments = "{\"command\":\"printf ok; touch approved.txt\"}";
        var server = startServer(requestCount, List.of(
                toolCallResponse("call_1", "run_command", arguments),
                textResponse("First approved"),
                toolCallResponse("call_2", "run_command", arguments),
                textResponse("Second approved")));
        try {
            writeApprovalConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Run the restricted command");
                var panel = waitForPanel(window);
                waitForLine(panel, "Approval required:");
                waitForLine(panel, "Approval options open in the menu.");
                assertEquals("approval options", panel.getCommandMenuState().title());
                assertEquals(List.of("Approve once", "Approve always", "Deny"),
                        panel.getCommandMenuState().matches().stream()
                                .map(org.fisk.swim.ui.CommandView.CommandSpec::displayLabel)
                                .toList());
                assertTrue(panel.getCommandMenuState().matches().stream()
                        .anyMatch(spec -> spec.primaryName().equals("approve")
                                && spec.arguments().equals("approval-1")
                                && spec.replacementText().equals("approve approval-1")));
                assertTrue(panel.getCommandMenuState().matches().stream()
                        .anyMatch(spec -> spec.primaryName().equals("approve")
                                && spec.arguments().equals("approval-1 always")
                                && spec.replacementText().equals("approve approval-1 always")));
                dispatch(panel, new KeyStroke(KeyType.ArrowDown));
                assertEquals("approval-1 always", panel.getCommandMenuState().selectedMatch().arguments());
                dispatch(panel, new KeyStroke(KeyType.Enter));
                waitForLine(panel, "nemo> First approved");
                assertTrue(Files.exists(tempDir.resolve("approved.txt")));

                submit(panel, "Run it again");
                waitForLine(panel, "nemo> Second approved");

                long approvalPrompts = displayLines(panel).stream()
                        .filter(line -> line.contains("Approval required:"))
                        .count();
                assertEquals(1, approvalPrompts);
                assertTrue(Files.readString(tempDir.resolve(".swim/nemo/approvals.json")).contains("run_command"));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void defaultOpenAiCompatibleRequestsOmitStrictAndParallelFlags() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<String>("");
        var server = startServer(requestCount, requestBody, List.of(textResponse("Hello")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Hi");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Hello");

                String body = requestBody.get();
                assertFalse(body.contains("strict_tools"));
                assertFalse(body.contains("parallel_tool_calls"));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void readOnlyPermissionOmitsMutatingToolsFromOpenAiCompatibleRequest() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<String>("");
        var server = startServer(requestCount, requestBody, List.of(textResponse("Hello")));
        try {
            writeReadOnlyConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Inspect this workspace");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Hello");

                String toolPayload = JsonParser.parseString(requestBody.get()).getAsJsonObject()
                        .getAsJsonArray("tools")
                        .toString();
                assertTrue(toolPayload.contains("list_files"));
                assertTrue(toolPayload.contains("read_file"));
                assertFalse(toolPayload.contains("run_command"));
                assertFalse(toolPayload.contains("write_file"));
                assertFalse(toolPayload.contains("apply_patch"));
                assertFalse(toolPayload.contains("git_add"));
                assertFalse(toolPayload.contains("git_commit"));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void openAiCompatibleFallbackSurvivesStrictRequestValidation() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<String>("");
        var server = startValidatingServer(requestCount, requestBody, List.of(textResponse("Validated")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Hi");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Validated");

                String body = requestBody.get();
                assertFalse(body.contains("parallel_tool_calls"));
                assertFalse(body.contains("strict_tools"));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void chatGptProviderUsesLangChainChatBackendFromLegacyResponsesUrl() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<String>("");
        var server = startServer(requestCount, requestBody, List.of(textResponse("Native SSO answer")));
        try {
            writeResponsesConfig(server);
            String originalUserHome = switchToTempUserHome();
            try (var harness = HeadlessWindowHarness.create(writeFile("chat.txt", "class Demo {}\n"), 80, 16)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Hi");
                var panel = waitForPanel(window);
                waitForLine(panel, "nemo> Native SSO answer");

                String body = requestBody.get();
                assertTrue(body.contains("\"messages\""));
                assertFalse(body.contains("\"instructions\""));
                assertFalse(body.contains("\"store\""));
                assertFalse(body.contains("\"stream\":true"));
                JsonObject requestJson = JsonParser.parseString(body).getAsJsonObject();
                assertEquals("xhigh", requestJson.get("reasoning_effort").getAsString());
                assertFalse(body.contains("max_output_tokens"));
                assertEquals(1, requestCount.get());
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
    @Timeout(20)
    void toolTranscriptEntriesPersistButAreExcludedFromPrompt() throws Exception {
        var requestCount = new AtomicInteger();
        var requestBody = new AtomicReference<>("");
        var server = startServer(requestCount, requestBody, List.of(textResponse("Follow-up answer")));
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            Path configDir = tempDir.resolve(".swim");
            Files.createDirectories(configDir.resolve("nemo"));
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
                                    { "speaker": "tool", "text": "list_files: path=.", "include_in_prompt": false },
                                    { "speaker": "nemo", "text": "Earlier answer", "include_in_prompt": true }
                                  ]
                                }
                              ]
                            }
                            """.formatted(tempDir.toAbsolutePath(), tempDir.toAbsolutePath()));
            Path file = writeFile("chat.txt", "class Demo {}\n");
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Follow up");
                var panel = waitForPanel(window);
                waitForLine(panel, "tool> list_files: path=.");
                waitForLine(panel, "nemo> Follow-up answer");

                String body = requestBody.get();
                assertTrue(body.contains("Earlier question"));
                assertTrue(body.contains("Earlier answer"));
                assertTrue(body.contains("Follow up"));
                assertFalse(body.contains("list_files: path=."));
                assertFalse(body.contains("tool>"));
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
    void openingPaneDoesNotRewritePersistedFailureHistory() throws Exception {
        String originalUserHome = switchToTempUserHome();
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), "");
        String originalState = """
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
                        { "speaker": "nemo", "text": "Nemo failed: {\\n  \\"code\\" : \\"InvalidParameter\\"\\n}", "include_in_prompt": false }
                      ]
                    }
                  ]
                }
                """.formatted(tempDir.toAbsolutePath(), tempDir.toAbsolutePath());
        Files.writeString(configDir.resolve("nemo/sessions.json"), originalState);
        Path file = writeFile("chat.txt", "class Demo {}\n");
        try {
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);
                var transcript = displayLines(panel);
                assertTrue(transcript.stream().anyMatch(line -> line.contains("me> Earlier question")));
                assertTrue(transcript.stream().anyMatch(line -> line.contains("Nemo failed:")));
            }
            assertEquals(
                    JsonParser.parseString(originalState),
                    JsonParser.parseString(Files.readString(configDir.resolve("nemo/sessions.json"))));
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @Timeout(20)
    void failureNotesAreNotReplayedAfterReopen() throws Exception {
        var requestCount = new AtomicInteger();
        var server = startErrorServer(requestCount, """
                {
                  "error": {
                    "code": "InvalidParameter",
                    "message": "Invalid request parameter"
                  }
                }
                """);
        try {
            writeConfig(server);
            String originalUserHome = switchToTempUserHome();
            Path file = writeFile("chat.txt", "class Demo {}\n");
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "Hello");
                var panel = waitForPanel(window);
                waitForLine(panel, "Nemo failed:");

                window.hidePanel();
                NemoClient.getInstance().run(window.getBufferContext(), "");
                panel = waitForPanel(window);
                Thread.sleep(100);
                var transcript = displayLines(panel);
                assertTrue(transcript.stream().anyMatch(line -> line.contains("me> Hello")));
                assertFalse(transcript.stream().anyMatch(line -> line.contains("Nemo failed:")));
            } finally {
                System.setProperty("user.home", originalUserHome);
            }
            assertEquals(1, requestCount.get());
            assertFalse(Files.readString(tempDir.resolve(".swim/nemo/sessions.json")).contains("Nemo failed:"));
        } finally {
            server.stop(0);
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

    @Test
    @Timeout(15)
    void permissionsCommandShowsAndChangesSessionPermissionMode() throws Exception {
        String originalUserHome = switchToTempUserHome();
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), "");
        Path file = writeFile("permissions.txt", "class Demo {}\n");
        try {
            try (var harness = HeadlessWindowHarness.create(file, 80, 18)) {
                EventThread.getInstance().start();
                var window = harness.getWindow();

                NemoClient.getInstance().run(window.getBufferContext(), "");
                var panel = waitForPanel(window);

                submit(panel, ":permissions");
                waitForLine(panel, "mode: workspace-write");

                submit(panel, ":permissions read-only");
                waitForLine(panel, "mode: read-only");

                submit(panel, ":permissions unsupported");
                waitForLine(panel, "Usage: :permissions read-only|workspace-write|full-access");
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private HttpServer startServer(AtomicInteger requestCount, List<JsonObject> responses) throws IOException {
        return startServer(requestCount, 0, responses);
    }

    private HttpServer startServer(AtomicInteger requestCount, AtomicReference<String> requestBody, List<JsonObject> responses)
            throws IOException {
        return startServer(requestCount, requestBody, 0, responses);
    }

    private HttpServer startServer(AtomicInteger requestCount, long responseDelayMillis, List<JsonObject> responses) throws IOException {
        return startServer(requestCount, new AtomicReference<>(""), responseDelayMillis, responses);
    }

    private HttpServer startServer(AtomicInteger requestCount, AtomicReference<String> requestBody, long responseDelayMillis,
            List<JsonObject> responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions",
                exchange -> handleResponse(exchange, requestCount, requestBody, responseDelayMillis, responses));
        server.start();
        return server;
    }

    private HttpServer startDelegationServer(AtomicInteger requestCount) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject response;
            if (body.contains("You are a Nemo sub-agent delegated")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response = textResponse("Sub-agent completed.");
            } else if (body.contains("Started sub-agent worker")) {
                response = textResponse("Parent saw delegated start.");
            } else {
                response = toolCallResponse("call_delegate", "delegate_task",
                        "{\"title\":\"Docs\",\"task\":\"Inspect chat.txt and summarize it.\",\"focus_paths\":[\"chat.txt\"]}");
            }
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private HttpServer startSubAgentApprovalServer(AtomicInteger requestCount, String commandArguments) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject response;
            if (body.contains("tool_call_id") && body.contains("call_subagent_command")) {
                response = textResponse("Sub-agent command approved.");
            } else if (body.contains("You are a Nemo sub-agent delegated")) {
                response = toolCallResponse("call_subagent_command", "run_command", commandArguments);
            } else if (body.contains("Started sub-agent worker")) {
                response = textResponse("Parent saw delegated start.");
            } else {
                response = toolCallResponse("call_delegate", "delegate_task",
                        "{\"title\":\"Build\",\"task\":\"Run the requested command and report the result.\"}");
            }
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private HttpServer startValidatingServer(AtomicInteger requestCount, AtomicReference<String> requestBody,
            List<JsonObject> responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            int requestIndex = requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBody.set(body);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            var allowed = new LinkedHashSet<>(List.of(
                    "model",
                    "messages",
                    "tools",
                    "stream",
                    "stream_options",
                    "temperature",
                    "top_p",
                    "max_tokens",
                    "max_completion_tokens",
                    "tool_choice",
                    "parallel_tool_calls",
                    "response_format",
                    "stop",
                    "presence_penalty",
                    "frequency_penalty",
                    "logit_bias",
                    "user",
                    "seed",
                    "reasoning_effort"));
            for (var entry : request.entrySet()) {
                if (!allowed.contains(entry.getKey())) {
                    byte[] error = """
                            {
                              "error": {
                                "code": "InvalidParameter",
                                "message": "Invalid request parameter"
                              }
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, error.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(error);
                    }
                    return;
                }
            }
            JsonObject response = responses.get(Math.min(requestIndex - 1, responses.size() - 1));
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private HttpServer startErrorServer(AtomicInteger requestCount, String errorBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private void handleResponse(HttpExchange exchange, AtomicInteger requestCount, AtomicReference<String> requestBody,
            long responseDelayMillis, List<JsonObject> responses) throws IOException {
        int requestIndex = requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

    private static JsonObject toolCallResponse(String callId, String toolName, String arguments) {
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
        toolCall.addProperty("id", callId);
        toolCall.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", toolName);
        function.addProperty("arguments", arguments);
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

    private void writeApprovalConfig(HttpServer server) throws IOException {
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), String.join("\n",
                "provider=openai-compatible",
                "api_key=test-token",
                "model=gpt-5.4",
                "base_url=http://127.0.0.1:" + server.getAddress().getPort(),
                "tool.permission_mode=workspace-write",
                "tool.command_policy=restricted",
                "tool.approval_policy=on-escalation",
                "tool.os_sandbox=disabled",
                "tool.list_files=true",
                "tool.read_file=true",
                "tool.search_files=true",
                "tool.run_command=true",
                "tool.write_file=true",
                "tool.web_search=false"));
    }

    private void writeMcpConfig(HttpServer server) throws IOException {
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Files.writeString(configDir.resolve("nemo/nemo.conf"), """
                {
                  "provider": "openai-compatible",
                  "apiKey": "test-token",
                  "model": "gpt-5.4",
                  "baseUrl": "http://127.0.0.1:%d",
                  "tools": {
                    "webSearch": false
                  },
                  "mcp": {
                    "servers": {
                      "mock": {
                        "command": %s,
                        "args": ["-cp", %s, %s],
                        "timeoutSeconds": 5
                      }
                    }
                  }
                }
                """.formatted(
                server.getAddress().getPort(),
                jsonString(java.toString()),
                jsonString(System.getProperty("java.class.path")),
                jsonString(FakeMcpServer.class.getName())));
    }

    private void writeReadOnlyConfig(HttpServer server) throws IOException {
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), String.join("\n",
                "provider=openai-compatible",
                "api_key=test-token",
                "model=gpt-5.4",
                "base_url=http://127.0.0.1:" + server.getAddress().getPort(),
                "tool.permission_mode=read-only",
                "tool.list_files=true",
                "tool.read_file=true",
                "tool.search_files=true",
                "tool.run_command=true",
                "tool.write_file=true",
                "tool.apply_patch=true",
                "tool.git_status=true",
                "tool.git_diff=true",
                "tool.git_add=true",
                "tool.git_commit=true",
                "tool.web_search=false"));
    }

    private void writeResponsesConfig(HttpServer server) throws IOException {
        Path configDir = tempDir.resolve(".swim");
        Files.createDirectories(configDir.resolve("nemo"));
        Files.writeString(configDir.resolve("nemo/nemo.conf"), String.join("\n",
                "provider=chatgpt",
                "api_key=test-token",
                "model=gpt-5.5",
                "responses_url=http://127.0.0.1:" + server.getAddress().getPort() + "/responses",
                "reasoning_effort=xhigh",
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

    private static JsonObject json(Map<String, ?> values) {
        return JsonParser.parseString(new com.google.gson.Gson().toJson(values)).getAsJsonObject();
    }

    private static String jsonString(String value) {
        return new com.google.gson.Gson().toJson(value);
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
            dispatch(panel, new KeyStroke(character, false, false));
        }
        dispatch(panel, new KeyStroke(KeyType.Enter));
    }

    private static void dispatch(ChatPanelView panel, KeyStroke keyStroke) {
        var response = panel.processEvent(new org.fisk.swim.event.KeyStrokes(List.of(keyStroke)));
        if (response == org.fisk.swim.event.Response.YES) {
            panel.respond();
        }
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
