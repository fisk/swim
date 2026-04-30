package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void resolvesConfigurationFromOpenAiPrefixedEnvironmentVariables() {
        var configuration = NemoClient.resolveConfiguration(Map.of(
                "OPENAI_API_KEY", "token",
                "OPENAI_BASE_URL", "https://example.invalid/litellm",
                "OPENAI_MODEL", "gpt-5.4",
                "OPENAI_PROJECT", "proj_123",
                "OPENAI_ORGANIZATION", "org_456",
                "OPENAI_HEADER_CLIENT", "codex-cli",
                "OPENAI_HEADER_CLIENT_VERSION", "0"));

        assertEquals("token", configuration.apiKey());
        assertEquals("gpt-5.4", configuration.model());
        assertEquals("https://example.invalid/litellm/responses", configuration.responsesUri().toString());
        assertEquals("Bearer token", configuration.headers().get("Authorization"));
        assertEquals("proj_123", configuration.headers().get("OpenAI-Project"));
        assertEquals("org_456", configuration.headers().get("OpenAI-Organization"));
        assertEquals("codex-cli", configuration.headers().get("client"));
        assertEquals("0", configuration.headers().get("client-version"));
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
}
