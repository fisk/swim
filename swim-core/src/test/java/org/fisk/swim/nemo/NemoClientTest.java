package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NemoClientTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsPromptFromCurrentBufferAndQuestion() throws IOException {
        Path file = tempDir.resolve("Demo.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoClient.buildInput(context, "What does this file do?");

        assertTrue(prompt.contains("What does this file do?"));
        assertTrue(prompt.contains(file.toString()));
        assertTrue(prompt.contains("class Demo {}"));
    }

    @Test
    void buildsPromptFromConversationTurns() throws IOException {
        Path file = tempDir.resolve("Conversation.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoClient.buildInput(context, List.of(
                new NemoClient.ChatTurn("me", "First"),
                new NemoClient.ChatTurn("nemo", "Second")));

        assertTrue(prompt.contains("me> First"));
        assertTrue(prompt.contains("nemo> Second"));
    }

    @Test
    void extractsOutputTextFromResponsesPayload() {
        String response = """
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        { "type": "output_text", "text": "First line" },
                        { "type": "output_text", "text": "Second line" }
                      ]
                    }
                  ]
                }
                """;

        assertEquals("First line\nSecond line", NemoClient.extractOutputText(response));
    }

    @Test
    void wrapsLongLinesForListRendering() {
        List<String> lines = NemoClient.wrapText("alpha beta gamma delta epsilon", 10);

        assertEquals(List.of("alpha beta", "gamma", "delta", "epsilon"), lines);
    }

    @Test
    void loadsConfigurationFromPropertiesFile() throws IOException {
        Path config = tempDir.resolve("nemo.conf");
        Files.writeString(config, String.join("\n",
                "api_key=token",
                "base_url=https://example.invalid/litellm",
                "model=gpt-5.4",
                "project=proj_123",
                "organization=org_456",
                "header.client=swim",
                "tool.web_search=true",
                "tool.run_command=false",
                "tool.max_results=42"));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("token", configuration.apiKey());
        assertEquals("gpt-5.4", configuration.model());
        assertEquals("https://example.invalid/litellm/responses", configuration.responsesUri().toString());
        assertEquals("Bearer token", configuration.headers().get("Authorization"));
        assertEquals("proj_123", configuration.headers().get("OpenAI-Project"));
        assertEquals("org_456", configuration.headers().get("OpenAI-Organization"));
        assertEquals("swim", configuration.headers().get("client"));
        assertTrue(configuration.toolWebSearch());
        assertEquals(42, configuration.toolMaxResults());
        assertTrue(!configuration.toolRunCommand());
    }

    @Test
    void explicitResponsesUrlWinsOverBaseUrl() {
        assertEquals("https://example.invalid/custom/responses",
                NemoClient.buildResponsesUri("https://example.invalid/custom/responses", "https://ignored.invalid").toString());
    }

    @Test
    void extractsOutputTextWhenGatewayPrefixesJson() {
        String response = """
                data: {"output":[{"content":[{"type":"output_text","text":"Hello from Nemo"}]}]}
                """;

        assertEquals("Hello from Nemo", NemoClient.extractOutputText(response));
    }

    @Test
    void compactsNonJsonErrorBodies() {
        assertEquals("upstream auth failed", NemoClient.compactRawBody("upstream auth failed"));
    }

    @Test
    void buildsToolDefinitionsFromConfiguration() {
        var configuration = new NemoClient.Configuration(
                "token",
                "gpt-5.4",
                java.net.URI.create("https://example.invalid/responses"),
                Map.of(),
                null,
                true,
                true,
                true,
                true,
                true,
                50,
                2000,
                10);

        var tools = NemoClient.buildTools(configuration);

        assertEquals(5, tools.size());
        assertEquals("web_search", tools.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void executesReadListAndSearchToolsInsideWorkspace() throws Exception {
        Path project = tempDir.resolve("project");
        Path file = project.resolve("src/Main.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "alpha\nbeta\nalpha\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = new NemoClient.Configuration(
                "token",
                "gpt-5.4",
                java.net.URI.create("https://example.invalid/responses"),
                Map.of(),
                project,
                false,
                true,
                true,
                true,
                false,
                50,
                4000,
                5);

        String listed = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("1", "list_files", json(Map.of())));
        String read = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("2", "read_file", json(Map.of("path", "src/Main.txt", "start_line", 2, "end_line", 3))));
        String searched = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("3", "search_files", json(Map.of("query", "alpha"))));

        assertTrue(listed.contains("src/Main.txt"));
        assertTrue(read.contains("2: beta"));
        assertTrue(read.contains("3: alpha"));
        assertTrue(searched.contains("src/Main.txt:1: alpha"));
    }

    @Test
    void executesRunCommandTool() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = new NemoClient.Configuration(
                "token",
                "gpt-5.4",
                java.net.URI.create("https://example.invalid/responses"),
                Map.of(),
                project,
                false,
                false,
                false,
                false,
                true,
                50,
                4000,
                5);

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4", "run_command", json(Map.of("command", "printf 'ok'"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(output.contains("ok"));
    }

    @Test
    void appendsToolRoundWithoutPreviousResponseIdState() {
        var history = new com.google.gson.JsonArray();
        history.add("initial prompt");

        var response = com.google.gson.JsonParser.parseString("""
                {
                  "output": [
                    {
                      "type": "function_call",
                      "call_id": "call_1",
                      "name": "list_files",
                      "arguments": "{\\"path\\":\\".\\"}"
                    }
                  ]
                }
                """).getAsJsonObject();

        var toolOutputs = new com.google.gson.JsonArray();
        var toolOutput = new com.google.gson.JsonObject();
        toolOutput.addProperty("type", "function_call_output");
        toolOutput.addProperty("call_id", "call_1");
        toolOutput.addProperty("output", "src/Main.txt");
        toolOutputs.add(toolOutput);

        NemoClient.appendToolRound(history, response, toolOutputs);

        assertEquals(3, history.size());
        assertEquals("function_call", history.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("function_call_output", history.get(2).getAsJsonObject().get("type").getAsString());
    }

    private static com.google.gson.JsonObject json(Map<String, ?> values) {
        return com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(values)).getAsJsonObject();
    }
}
