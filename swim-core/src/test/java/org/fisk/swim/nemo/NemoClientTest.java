package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.fisk.swim.api.SwimNemoTool;
import org.fisk.swim.api.SwimNemoToolInvocation;
import org.fisk.swim.api.SwimNemoToolRegistry;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

class NemoClientTest {
    @TempDir
    Path tempDir;

    private static final class RecordingToolSession implements NemoClient.ToolExecutionSession {
        private final AtomicReference<NemoClient.ToolApprovalRequest> request = new AtomicReference<>();
        private final AtomicInteger approvals = new AtomicInteger();
        private boolean approved = true;

        @Override
        public NemoClient.ApprovalResult requestApproval(Path workspaceRoot, NemoClient.ToolApprovalRequest request) {
            approvals.incrementAndGet();
            this.request.set(request);
            return new NemoClient.ApprovalResult(approved, false);
        }
    }

    @AfterEach
    void tearDown() {
        NemoClient.getInstance().resetForTests();
        SwimNemoToolRegistry.clearForTests();
    }

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
    void buildInputDescribesPersistentWorkspaceWritesWhenEnabled() throws IOException {
        Path file = tempDir.resolve("Writable.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "Please edit this file")),
                NemoClient.Configuration.builder().build(),
                List.of());

        assertTrue(prompt.contains("permission mode: workspace-write"));
        assertTrue(prompt.contains("successful edits are saved to disk and persist across Nemo/editor runs"));
        assertTrue(prompt.contains("delegate_task starts focused work in a separate Nemo sub-agent worker"));
        assertTrue(prompt.contains("worker_status/read_worker to check sub-agent progress"));
        assertTrue(prompt.contains("message_worker to send corrections or extra instructions"));
        assertTrue(prompt.contains("start_editor_control requests host approval"));
        assertTrue(prompt.contains("screen_snapshot can inspect a host-filtered view"));
        assertTrue(prompt.contains("drive_editor can send bounded key streams"));
        assertTrue(prompt.contains("private/non-buffer workspaces are not visible"));
        assertTrue(prompt.contains("finish_editor_control releases the editor-control lock"));
    }

    @Test
    void buildInputDescribesReadOnlyPermissionWhenMutationsAreDisabled() throws IOException {
        Path file = tempDir.resolve("ReadOnly.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "Please edit this file")),
                NemoClient.Configuration.builder().toolPermissionMode("read-only").build(),
                List.of());

        assertTrue(prompt.contains("permission mode: read-only"));
        assertTrue(prompt.contains("mutating tools are disabled"));
        assertFalse(prompt.contains("successful edits are saved to disk and persist across Nemo/editor runs"));
    }

    @Test
    void buildInputDescribesConfiguredMcpServers() throws IOException {
        Path file = tempDir.resolve("Mcp.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "Use available tools")),
                NemoClient.Configuration.builder()
                        .mcpServers(List.of(new NemoMcpServerConfig("docs", true, "server", List.of(), Map.of(), null, 5)))
                        .build(),
                List.of());

        assertTrue(prompt.contains("MCP stdio servers are configured"));
        assertTrue(prompt.contains("tools named mcp__server__tool"));
    }

    @Test
    void omitsNonPromptTurnsFromConversationTranscript() throws IOException {
        Path file = tempDir.resolve("Conversation.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        String prompt = NemoClient.buildInput(context, List.of(
                new NemoClient.ChatTurn("me", ":workers", false),
                new NemoClient.ChatTurn("nemo", "Workers:\nsession-1", false),
                new NemoClient.ChatTurn("me", "Explain this class"),
                new NemoClient.ChatTurn("nemo", "It models a demo.")));

        assertFalse(prompt.contains(":workers"));
        assertFalse(prompt.contains("Workers:"));
        assertTrue(prompt.contains("me> Explain this class"));
        assertTrue(prompt.contains("nemo> It models a demo."));
    }

    @Test
    void buildInputCompactsOlderTurnsWhenContextBudgetIsSmall() throws IOException {
        Path file = tempDir.resolve("LargeConversation.txt");
        String fileText = "before cursor\n" + "a".repeat(2_000) + "\nCURSOR_ANCHOR\n" + "b".repeat(2_000) + "\nafter cursor\n";
        Files.writeString(file, fileText);
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        context.getBuffer().getCursor().setPosition(fileText.indexOf("CURSOR_ANCHOR"));
        var configuration = NemoClient.Configuration.builder()
                .systemPrompt("System prompt")
                .contextWindowTokens(900)
                .build();

        String prompt = NemoPromptBuilder.buildInput(context, List.of(
                new NemoClient.ChatTurn("me", "old request " + "x".repeat(1_000)),
                new NemoClient.ChatTurn("nemo", "old answer " + "y".repeat(1_000)),
                new NemoClient.ChatTurn("me", "latest request must survive")),
                configuration,
                List.of());

        assertTrue(prompt.length() <= promptBudgetChars(900));
        assertTrue(prompt.contains("[earlier conversation compacted:"));
        assertTrue(prompt.contains("me> latest request must survive"));
        assertTrue(prompt.contains("CURSOR_ANCHOR"));
        assertTrue(prompt.contains("File contents (budgeted around cursor):"));
    }

    @Test
    void buildInputExcerptsLargeFileAroundCursorWhenContextBudgetIsSmall() throws IOException {
        Path file = tempDir.resolve("LargeFile.txt");
        String fileText = "HEAD_SENTINEL\n" + "h".repeat(2_000) + "\nCURSOR_ANCHOR\n" + "t".repeat(2_000)
                + "\nTAIL_SENTINEL\n";
        Files.writeString(file, fileText);
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        context.getBuffer().getCursor().setPosition(fileText.indexOf("CURSOR_ANCHOR"));
        int contextWindowTokens = 1000;
        var configuration = NemoClient.Configuration.builder()
                .systemPrompt("System prompt")
                .contextWindowTokens(contextWindowTokens)
                .build();

        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "explain the code around the cursor")),
                configuration,
                List.of());

        assertTrue(prompt.length() <= promptBudgetChars(contextWindowTokens));
        assertTrue(prompt.contains("CURSOR_ANCHOR"));
        assertTrue(prompt.contains("chars before cursor"));
        assertTrue(prompt.contains("chars after cursor"));
        assertFalse(prompt.contains("HEAD_SENTINEL"));
        assertFalse(prompt.contains("TAIL_SENTINEL"));
    }

    @Test
    void buildInputBudgetsSkillsBeforeDroppingCurrentRequest() throws IOException {
        Path file = tempDir.resolve("SkillsBudget.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        int contextWindowTokens = 1500;
        var configuration = NemoClient.Configuration.builder()
                .systemPrompt("System prompt")
                .contextWindowTokens(contextWindowTokens)
                .build();

        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "current request must survive")),
                configuration,
                List.of(new NemoSkillDocument("SKILLS.md", "skill-start\n" + "s".repeat(5_000) + "\nskill-end")));

        assertTrue(prompt.length() <= promptBudgetChars(contextWindowTokens));
        assertTrue(prompt.contains("Applicable workspace instructions (budgeted):"));
        assertTrue(prompt.contains("skill-start"));
        assertFalse(prompt.contains("skill-end"));
        assertTrue(prompt.contains("me> current request must survive"));
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
    void loadsConfigurationFromPropertiesFile() throws IOException {
        Path config = tempDir.resolve("nemo.conf");
        Files.writeString(config, String.join("\n",
                "api_key=token",
                "base_url=https://example.invalid/litellm",
                "model=gpt-5.4",
                "project=proj_123",
                "organization=org_456",
                "reasoning_effort=xhigh",
                "header.client=swim",
                "tool.web_search=true",
                "tool.run_command=false",
                "tool.command_policy=trusted",
                "tool.permission_mode=read-only",
                "tool.os_sandbox=required",
                "tool.approval_policy=on-request",
                "tool.write_file=false",
                "tool.git_add=false",
                "tool.git_commit=false",
                "tool.max_results=42"));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("token", configuration.apiKey());
        assertEquals("gpt-5.4", configuration.model());
        assertEquals("https://example.invalid/litellm/responses", configuration.responsesUri().toString());
        assertEquals("Bearer token", configuration.headers().get("Authorization"));
        assertEquals("proj_123", configuration.headers().get("OpenAI-Project"));
        assertEquals("org_456", configuration.headers().get("OpenAI-Organization"));
        assertEquals("xhigh", configuration.reasoningEffort());
        assertEquals("swim", configuration.headers().get("client"));
        assertTrue(configuration.toolWebSearch());
        assertEquals("trusted", configuration.toolCommandPolicy());
        assertEquals("read_only", configuration.toolPermissionMode());
        assertEquals("required", configuration.toolOsSandbox());
        assertEquals("on_request", configuration.toolApprovalPolicy());
        assertEquals(42, configuration.toolMaxResults());
        assertTrue(!configuration.toolRunCommand());
        assertTrue(!configuration.toolWriteFile());
        assertTrue(!configuration.toolGitAdd());
        assertTrue(!configuration.toolGitCommit());
    }

    @Test
    void loadsMcpServersFromPropertiesFile() throws IOException {
        Path config = tempDir.resolve("nemo.properties");
        Files.writeString(config, String.join("\n",
                "mcp.server.docs.command=java",
                "mcp.server.docs.args=-cp \"target/test-classes\" org.example.Server",
                "mcp.server.docs.cwd=" + tempDir,
                "mcp.server.docs.env.API_TOKEN=secret",
                "mcp.server.docs.timeout_seconds=7"));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals(1, configuration.mcpServers().size());
        var server = configuration.mcpServers().get(0);
        assertEquals("docs", server.name());
        assertEquals("java", server.command());
        assertEquals(List.of("-cp", "target/test-classes", "org.example.Server"), server.args());
        assertEquals(tempDir.toAbsolutePath().normalize(), server.cwd());
        assertEquals("secret", server.env().get("API_TOKEN"));
        assertEquals(7, server.timeoutSeconds());
    }

    @Test
    void webSearchAndSubAgentDelegationAreEnabledByDefaultAndCanBeDisabled() throws IOException {
        assertTrue(NemoClient.Configuration.builder().build().toolWebSearch());
        assertTrue(NemoClient.Configuration.builder().build().toolDelegateTask());
        assertTrue(NemoClient.Configuration.builder().build().toolScreenSnapshot());
        assertTrue(NemoClient.Configuration.builder().build().toolDriveEditor());
        assertFalse(NemoClient.Configuration.builder().build().forSubAgent().toolDelegateTask());

        Path propertiesConfig = tempDir.resolve("nemo-disabled.properties");
        Files.writeString(propertiesConfig, String.join("\n",
                "tool.web_search=false",
                "tool.delegate_task=false",
                "tool.screen_snapshot=false",
                "tool.drive_editor=false"));
        var propertiesConfiguration = NemoClient.loadConfiguration(propertiesConfig);
        assertFalse(propertiesConfiguration.toolWebSearch());
        assertFalse(propertiesConfiguration.toolDelegateTask());
        assertFalse(propertiesConfiguration.toolScreenSnapshot());
        assertFalse(propertiesConfiguration.toolDriveEditor());

        Path jsonConfig = tempDir.resolve("nemo-disabled.json");
        Files.writeString(jsonConfig, """
                {
                  "tools": {
                    "webSearch": false,
                    "delegateTask": false,
                    "screenSnapshot": false,
                    "driveEditor": false
                  }
                }
                """);
        var jsonConfiguration = NemoClient.loadConfiguration(jsonConfig);
        assertFalse(jsonConfiguration.toolWebSearch());
        assertFalse(jsonConfiguration.toolDelegateTask());
        assertFalse(jsonConfiguration.toolScreenSnapshot());
        assertFalse(jsonConfiguration.toolDriveEditor());
    }

    @Test
    void configAndStatePathsLiveUnderNemoDirectory() {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            assertEquals(tempDir.resolve(".swim/nemo/nemo.conf"), NemoClient.getConfigPath());
            assertEquals(tempDir.resolve(".swim/nemo/sessions.json"), NemoClient.getStatePath());
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void loadConfigurationMigratesLegacyConfigIntoNemoDirectory() throws IOException {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            Path swimDir = tempDir.resolve(".swim");
            Files.createDirectories(swimDir);
            Path legacyConfig = swimDir.resolve("nemo.conf");
            Files.writeString(legacyConfig, "api_key=token\n");

            var configuration = NemoClient.loadConfiguration(NemoClient.getConfigPath());

            assertEquals("token", configuration.apiKey());
            assertTrue(Files.isRegularFile(NemoClient.getConfigPath()));
            assertFalse(Files.exists(legacyConfig));
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void loadsConfigurationFromJsonFileWithLangchainSettings() throws IOException {
        Path config = tempDir.resolve("nemo.conf");
        Files.writeString(config, """
                {
                  "provider": "openai-compatible",
                  "model": "gpt-5.4-mini",
                  "apiKey": "token",
                  "baseUrl": "https://example.invalid/openai/v1",
                  "headers": {
                    "X-Test": "swim"
                  },
                  "queryParameters": {
                    "api-version": "2026-01-01"
                  },
                  "customParameters": {
                    "reasoning": "medium"
                  },
                  "reasoningEffort": "high",
                  "contextWindowTokens": 65536,
                  "skills": {
                    "enabled": true,
                    "maxFiles": 3,
                    "maxChars": 512
                  },
                  "tools": {
                    "runCommand": false,
                    "commandPolicy": "trusted",
                    "permissionMode": "full-access",
                    "osSandbox": "disabled",
                    "approvalPolicy": "never"
                  }
                }
                """);

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("openai-compatible", configuration.provider());
        assertEquals("gpt-5.4-mini", configuration.model());
        assertEquals("token", configuration.apiKey());
        assertEquals("https://example.invalid/openai/v1", configuration.baseUrl());
        assertEquals("swim", configuration.headers().get("X-Test"));
        assertEquals("2026-01-01", configuration.queryParameters().get("api-version"));
        assertEquals("medium", configuration.customParameters().get("reasoning"));
        assertEquals("high", configuration.reasoningEffort());
        assertEquals(65536, configuration.contextWindowTokens());
        assertEquals(3, configuration.skillsMaxFiles());
        assertEquals(512, configuration.skillsMaxChars());
        assertFalse(configuration.toolRunCommand());
        assertEquals("trusted", configuration.toolCommandPolicy());
        assertEquals("full_access", configuration.toolPermissionMode());
        assertEquals("disabled", configuration.toolOsSandbox());
        assertEquals("never", configuration.toolApprovalPolicy());
    }

    @Test
    void loadsMcpServersFromJsonFile() throws IOException {
        Path config = tempDir.resolve("nemo-mcp.json");
        Files.writeString(config, """
                {
                  "mcp": {
                    "servers": {
                      "docs": {
                        "command": "java",
                        "args": ["-cp", "target/test-classes", "org.example.Server"],
                        "cwd": "%s",
                        "env": {
                          "API_TOKEN": "secret"
                        },
                        "timeoutSeconds": 9
                      }
                    }
                  }
                }
                """.formatted(tempDir.toString().replace("\\", "\\\\")));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals(1, configuration.mcpServers().size());
        var server = configuration.mcpServers().get(0);
        assertEquals("docs", server.name());
        assertEquals("java", server.command());
        assertEquals(List.of("-cp", "target/test-classes", "org.example.Server"), server.args());
        assertEquals(tempDir.toAbsolutePath().normalize(), server.cwd());
        assertEquals("secret", server.env().get("API_TOKEN"));
        assertEquals(9, server.timeoutSeconds());
    }

    @Test
    void buildToolsIncludesConfiguredMcpTools() throws Exception {
        var configuration = NemoClient.Configuration.builder()
                .mcpServers(List.of(fakeMcpServer("mock")))
                .build();

        var tools = NemoClient.buildTools(configuration);

        assertTrue(tools.toString().contains("\"name\":\"mcp__mock__echo\""));
        assertTrue(tools.toString().contains("Echo a message."));
    }

    @Test
    void readOnlyModeOmitsAndBlocksMcpTools() throws Exception {
        Path project = tempDir.resolve("mcp-read-only");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .mcpServers(List.of(fakeMcpServer("mock")))
                .build();

        assertFalse(NemoClient.buildTools(configuration).toString().contains("mcp__mock__echo"));

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("mcp-read-only", "mcp__mock__echo", json(Map.of("message", "hello"))));

        assertTrue(output.contains("blocked by Nemo permissions"));
    }

    @Test
    void executesMcpToolAfterApproval() throws Exception {
        Path project = tempDir.resolve("mcp-workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .mcpServers(List.of(fakeMcpServer("mock")))
                .build();

        String denied = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("mcp-denied", "mcp__mock__echo", json(Map.of("message", "hello"))));
        assertTrue(denied.contains("MCP tool calls require approval"));

        var approvalCount = new AtomicInteger();
        var approvalTool = new AtomicReference<String>();
        NemoClient.ToolExecutionSession approvalSession = (workspaceRoot, request) -> {
            approvalCount.incrementAndGet();
            approvalTool.set(request.toolName());
            return new NemoClient.ApprovalResult(true, false);
        };

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("mcp-approved", "mcp__mock__echo", json(Map.of("message", "hello"))),
                approvalSession);

        assertEquals(1, approvalCount.get());
        assertEquals("mcp__mock__echo", approvalTool.get());
        assertEquals("echo: hello", output);
    }

    @Test
    void buildToolsIncludesRegisteredPluginTools() {
        registerPluginTool("sample-plugin", "echo", "Sample plugin echo.", false, true,
                invocation -> "unused");

        var tools = NemoClient.buildTools(NemoClient.Configuration.builder().build());

        String toolJson = tools.toString();
        assertTrue(toolJson.contains("\"name\":\"plugin__sample_plugin__echo\""));
        assertTrue(toolJson.contains("Sample plugin echo."));
        assertTrue(toolJson.contains("\"message\""));
    }

    @Test
    void langChainToolsIncludeRegisteredPluginTools() {
        registerPluginTool("sample-plugin", "echo", "Sample plugin echo.", false, true,
                invocation -> "unused");

        var names = NemoLangChain4jClient.buildToolSpecifications(NemoClient.Configuration.builder().build()).stream()
                .map(tool -> tool.name())
                .toList();

        assertTrue(names.contains("swim_help"));
        assertTrue(names.contains("plugin__sample_plugin__echo"));
    }

    @Test
    void executesPluginToolAfterApproval() throws Exception {
        Path project = tempDir.resolve("plugin-workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var invocationRef = new AtomicReference<SwimNemoToolInvocation>();
        registerPluginTool("sample-plugin", "echo", "Sample plugin echo.", false, true, invocation -> {
            invocationRef.set(invocation);
            JsonObject arguments = com.google.gson.JsonParser.parseString(invocation.argumentsJson()).getAsJsonObject();
            return "plugin echo: " + arguments.get("message").getAsString();
        });
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String denied = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("plugin-denied", "plugin__sample_plugin__echo", json(Map.of("message", "hello"))));
        assertTrue(denied.contains("plugin tool calls require approval"));

        var approvalCount = new AtomicInteger();
        var approvalTool = new AtomicReference<String>();
        NemoClient.ToolExecutionSession approvalSession = (workspaceRoot, request) -> {
            approvalCount.incrementAndGet();
            approvalTool.set(request.toolName());
            return new NemoClient.ApprovalResult(true, false);
        };

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("plugin-approved", "plugin__sample_plugin__echo", json(Map.of("message", "hello"))),
                approvalSession);

        assertEquals(1, approvalCount.get());
        assertEquals("plugin__sample_plugin__echo", approvalTool.get());
        assertEquals("plugin echo: hello", output);
        assertEquals("sample-plugin", invocationRef.get().pluginId());
        assertEquals("echo", invocationRef.get().toolName());
        assertEquals(file, invocationRef.get().currentPath());
        assertEquals(project, invocationRef.get().workspaceRoot());
    }

    @Test
    void readOnlyModeOmitsAndBlocksMutatingPluginToolsButAllowsReadOnlyPluginTools() throws Exception {
        Path project = tempDir.resolve("plugin-read-only");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        registerPluginTool("sample-plugin", "mutate", "Mutates something.", false, true,
                invocation -> "mutated");
        registerPluginTool("sample-plugin", "inspect", "Inspects something.", true, false,
                invocation -> "inspect ok");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .build();

        String tools = NemoClient.buildTools(configuration).toString();
        assertFalse(tools.contains("plugin__sample_plugin__mutate"));
        assertTrue(tools.contains("plugin__sample_plugin__inspect"));

        String blocked = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("plugin-read-only-blocked", "plugin__sample_plugin__mutate",
                        json(Map.of("message", "hello"))));
        assertTrue(blocked.contains("does not allow plugin tools"));

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("plugin-read-only-allowed", "plugin__sample_plugin__inspect",
                        json(Map.of("message", "hello"))));
        assertEquals("inspect ok", output);
    }


    @Test
    void buildInputIncludesApplicableSkillsFiles() throws IOException {
        Path project = tempDir.resolve("project");
        Path src = project.resolve("src");
        Files.createDirectories(src);
        Files.writeString(project.resolve("SKILLS.md"), "Root skill");
        Files.writeString(src.resolve("SKILLS.md"), "Nested skill");
        Path file = src.resolve("Demo.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .skillsEnabled(true)
                .workspaceRoot(project)
                .build();

        var skills = NemoSkillLoader.loadApplicableSkills(context, project, configuration);
        String prompt = NemoPromptBuilder.buildInput(context,
                List.of(new NemoClient.ChatTurn("me", "Explain")),
                configuration,
                skills);

        assertTrue(prompt.contains("Applicable workspace instructions"));
        assertTrue(prompt.contains("--- built-in/editor-control ---"));
        assertTrue(prompt.contains("Call start_editor_control before screen_snapshot"));
        assertTrue(prompt.contains("--- SKILLS.md ---"));
        assertTrue(prompt.contains("Root skill"));
        assertTrue(prompt.contains("--- src/SKILLS.md ---"));
        assertTrue(prompt.contains("Nested skill"));
    }

    @Test
    void editorControlSkillDoesNotConsumeWorkspaceGuidanceBudget() throws IOException {
        Path project = tempDir.resolve("editor-control-skill");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), "Root agents");
        Path file = project.resolve("Demo.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .skillsEnabled(true)
                .workspaceRoot(project)
                .skillsMaxFiles(1)
                .build();

        var guidance = NemoSkillLoader.loadApplicableSkills(context, project, configuration);

        assertEquals(List.of("built-in/editor-control", "AGENTS.md"),
                guidance.stream().map(NemoSkillDocument::relativePath).toList());
    }

    @Test
    void loadGuidanceIncludesRootAndNestedAgentsInOrder() throws IOException {
        Path project = tempDir.resolve("project");
        Path src = project.resolve("src");
        Files.createDirectories(src);
        Files.writeString(project.resolve("AGENTS.md"), "Root agents");
        Files.writeString(src.resolve("AGENTS.md"), "Nested agents");
        Path file = src.resolve("Demo.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .skillsEnabled(true)
                .workspaceRoot(project)
                .toolScreenSnapshot(false)
                .toolDriveEditor(false)
                .build();

        var guidance = NemoSkillLoader.loadApplicableSkills(context, project, configuration);

        assertEquals(List.of("AGENTS.md", "src/AGENTS.md"),
                guidance.stream().map(NemoSkillDocument::relativePath).toList());
        assertEquals("Root agents", guidance.get(0).content());
        assertEquals("Nested agents", guidance.get(1).content());
    }

    @Test
    void loadGuidancePrefersAgentsOverrideOverAgents() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), "Base agents");
        Files.writeString(project.resolve("AGENTS.override.md"), "Override agents");
        Path file = project.resolve("Demo.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .skillsEnabled(true)
                .workspaceRoot(project)
                .toolScreenSnapshot(false)
                .toolDriveEditor(false)
                .build();

        var guidance = NemoSkillLoader.loadApplicableSkills(context, project, configuration);

        assertEquals(List.of("AGENTS.override.md"),
                guidance.stream().map(NemoSkillDocument::relativePath).toList());
        assertEquals("Override agents", guidance.get(0).content());
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
    void extractContextUsagePercentUsesUsageLimitsWhenPresent() {
        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString("""
                {
                  "usage": {
                    "input_tokens": 5000,
                    "input_tokens_limit": 20000
                  }
                }
                """).getAsJsonObject();

        assertEquals(25, NemoClient.extractContextUsagePercent(response));
    }

    @Test
    void extractContextUsagePercentReturnsNullWithoutKnownLimit() {
        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString("""
                {
                  "usage": {
                    "input_tokens": 5000
                  }
                }
                """).getAsJsonObject();

        assertEquals(null, NemoClient.extractContextUsagePercent(response));
    }

    @Test
    void extractContextUsagePercentFindsNestedGatewayFields() {
        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString("""
                {
                  "usage": {
                    "prompt_tokens": 6000
                  },
                  "metadata": {
                    "model": {
                      "context_length": 24000
                    }
                  }
                }
                """).getAsJsonObject();

        assertEquals(25, NemoClient.extractContextUsagePercent(response));
    }

    @Test
    void extractContextUsagePercentSupportsCamelCaseFieldNames() {
        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString("""
                {
                  "usage": {
                    "inputTokens": 3000
                  },
                  "limits": {
                    "maxContextTokens": 12000
                  }
                }
                """).getAsJsonObject();

        assertEquals(25, NemoClient.extractContextUsagePercent(response));
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
                true,
                true,
                true,
                true,
                true,
                true,
                50,
                2000,
                10);

        var tools = NemoClient.buildTools(configuration);

        assertEquals(21, tools.size());
        assertEquals("web_search", tools.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("delegate_task", tools.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("worker_status", tools.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("read_worker", tools.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals("join_worker", tools.get(4).getAsJsonObject().get("name").getAsString());
        assertEquals("message_worker", tools.get(5).getAsJsonObject().get("name").getAsString());
        assertEquals("start_editor_control", tools.get(6).getAsJsonObject().get("name").getAsString());
        assertEquals("screen_snapshot", tools.get(7).getAsJsonObject().get("name").getAsString());
        assertEquals("drive_editor", tools.get(8).getAsJsonObject().get("name").getAsString());
        assertEquals("finish_editor_control", tools.get(9).getAsJsonObject().get("name").getAsString());
        assertEquals("swim_help", tools.get(10).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void buildToolsOmitsMutatingToolsInReadOnlyMode() {
        var configuration = NemoClient.Configuration.builder()
                .toolWebSearch(true)
                .toolPermissionMode("read-only")
                .build();

        var tools = NemoClient.buildTools(configuration);
        var names = new java.util.ArrayList<String>();
        for (var tool : tools) {
            var object = tool.getAsJsonObject();
            if (object.has("name")) {
                names.add(object.get("name").getAsString());
            }
        }

        assertTrue(names.contains("list_files"));
        assertTrue(names.contains("delegate_task"));
        assertTrue(names.contains("worker_status"));
        assertTrue(names.contains("read_worker"));
        assertTrue(names.contains("join_worker"));
        assertTrue(names.contains("message_worker"));
        assertTrue(names.contains("start_editor_control"));
        assertTrue(names.contains("screen_snapshot"));
        assertTrue(names.contains("finish_editor_control"));
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("search_files"));
        assertTrue(names.contains("git_status"));
        assertTrue(names.contains("git_diff"));
        assertFalse(names.contains("run_command"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("apply_patch"));
        assertFalse(names.contains("git_add"));
        assertFalse(names.contains("git_commit"));
        assertFalse(names.contains("drive_editor"));
    }

    @Test
    void buildToolsDescribeWorkspaceWritesAsPersistent() {
        var tools = NemoClient.buildTools(NemoClient.Configuration.builder().build());
        String descriptions = tools.toString();

        assertTrue(descriptions.contains("Successful writes are saved to disk and persist across Nemo/editor runs"));
        assertTrue(descriptions.contains("Successful patches are saved to disk and persist across Nemo/editor runs"));
    }

    @Test
    void startEditorControlRequiresHostOnlyApprovalAndExcludesHostOverlay() throws Exception {
        Path project = tempDir.resolve("editor-tools");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            harness.getWindow().showHostApprovalOverlay(List.of(new org.fisk.swim.ui.HostApprovalOverlayView.Entry(
                    "approval-secret",
                    "session",
                    "drive_editor",
                    "editor control",
                    "SECRET OVERLAY",
                    false)), ignored -> {
                    });
            var session = new RecordingToolSession();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    session);

            assertEquals("start_editor_control", session.request.get().toolName());
            assertTrue(session.request.get().hostOnly());
            assertTrue(output.contains("alpha"));
            assertFalse(output.contains("SECRET OVERLAY"));
            assertFalse(output.contains("approval-secret"));

            String snapshot = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("screen", "screen_snapshot", json(Map.of())),
                    session);
            assertTrue(snapshot.contains("alpha"));
            assertFalse(snapshot.contains("SECRET OVERLAY"));
        }
    }

    @Test
    void screenSnapshotBlocksMailBeforeApproval() throws Exception {
        Path project = tempDir.resolve("mail-screen-block");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            assertTrue(harness.getWindow().showMailWorkspace(secretMailClient()));
            var session = new RecordingToolSession();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    session);

            assertTrue(output.contains("mail is visible"));
            assertFalse(output.contains("SECRET SUBJECT"));
            assertFalse(output.contains("SECRET BODY"));
            assertEquals(0, session.approvals.get());
        }
    }

    @Test
    void screenSnapshotRequiresActiveEditorControlSession() throws Exception {
        Path project = tempDir.resolve("screen-requires-start");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("screen", "screen_snapshot", json(Map.of())),
                    new RecordingToolSession());

            assertTrue(output.contains("call start_editor_control first"));
            assertFalse(output.contains("alpha"));
        }
    }

    @Test
    void driveEditorRequiresActiveEditorControlSession() throws Exception {
        Path project = tempDir.resolve("drive-requires-start");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("drive", "drive_editor", json(Map.of("input", "ihello <ESC>"))),
                    new RecordingToolSession());

            assertTrue(output.contains("call start_editor_control first"));
            assertEquals("alpha\n", context.getBuffer().getString());
        }
    }

    @Test
    void driveEditorToolEditsBufferAfterSessionStartApproval() throws Exception {
        Path project = tempDir.resolve("drive-editor");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            var session = new RecordingToolSession();

            String started = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    session);
            assertTrue(started.contains("editor control started"));

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("drive", "drive_editor", json(Map.of(
                            "input", "ihello <ESC>",
                            "max_events", 50))),
                    session);

            assertEquals("start_editor_control", session.request.get().toolName());
            assertTrue(session.request.get().hostOnly());
            assertTrue(output.contains("accepted: true"));
            assertTrue(context.getBuffer().getString().startsWith("hello alpha"));
        }
    }

    @Test
    void editorControlLeaseRejectsOtherToolSessions() throws Exception {
        Path project = tempDir.resolve("drive-editor-lock");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            var owner = new RecordingToolSession();
            var other = new RecordingToolSession();

            assertTrue(NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    owner).contains("editor control started"));
            String blocked = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("drive", "drive_editor", json(Map.of("input", "ihello <ESC>"))),
                    other);
            assertTrue(blocked.contains("locked by"));
            assertEquals("alpha\n", context.getBuffer().getString());

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("drive", "drive_editor", json(Map.of("input", "ihello <ESC>"))),
                    owner);
            assertTrue(output.contains("accepted: true"));
            assertTrue(context.getBuffer().getString().startsWith("hello alpha"));
        }
    }

    @Test
    void finishEditorControlReleasesLeaseForNextSession() throws Exception {
        Path project = tempDir.resolve("drive-editor-release");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            var owner = new RecordingToolSession();
            var other = new RecordingToolSession();

            assertTrue(NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    owner).contains("editor control started"));
            assertTrue(NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("screen", "screen_snapshot", json(Map.of())),
                    other).contains("locked by"));

            String finished = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("finish", "finish_editor_control", json(Map.of())),
                    owner);
            assertTrue(finished.contains("editor control finished"));

            String restarted = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start2", "start_editor_control", json(Map.of())),
                    other);
            assertTrue(restarted.contains("editor control started"));
        }
    }

    @Test
    void driveEditorBlocksMailBeforeApproval() throws Exception {
        Path project = tempDir.resolve("mail-drive-block");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            assertTrue(harness.getWindow().showMailWorkspace(secretMailClient()));
            var session = new RecordingToolSession();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    session);

            assertTrue(output.contains("mail is visible"));
            assertFalse(output.contains("SECRET SUBJECT"));
            assertFalse(output.contains("SECRET BODY"));
            assertEquals(0, session.approvals.get());
        }
    }

    @Test
    void runDoesNotOpenNemoWhileMailIsVisible() throws Exception {
        Path project = tempDir.resolve("mail-run-block");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var window = harness.getWindow();
            assertTrue(window.showMailWorkspace(secretMailClient()));

            NemoClient.getInstance().run(window.getBufferContext(), "Summarize this");

            assertFalse(window.isShowingPanel());
            assertTrue(HeadlessWindowHarness.getField(window.getCommandView(), "_message", String.class)
                    .contains("Nemo is unavailable"));
        }
    }

    @Test
    void readOnlyModeAllowsSnapshotButBlocksDriving() throws Exception {
        Path project = tempDir.resolve("read-only-editor");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .build();
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            var context = harness.getWindow().getBufferContext();
            var session = new RecordingToolSession();

            String started = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("start", "start_editor_control", json(Map.of())),
                    session);
            String snapshot = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("screen", "screen_snapshot", json(Map.of())),
                    session);
            String drive = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("drive", "drive_editor", json(Map.of("input", "ihello <ESC>"))),
                    session);

            assertTrue(started.contains("editor control started"));
            assertTrue(snapshot.contains("alpha"));
            assertTrue(drive.contains("blocked by Nemo permissions"));
            assertEquals("alpha\n", context.getBuffer().getString());
        }
    }

    @Test
    void finishEditorControlRequiresInvokingConversationSession() throws Exception {
        Path project = tempDir.resolve("finish-editor");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("finish", "finish_editor_control", json(Map.of())));

        assertTrue(output.contains("no invoking Nemo conversation is active"));
    }

    @Test
    void parsesDuckDuckGoHtmlResults() {
        String html = """
                <div class="result">
                  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs%3Fx%3D1&amp;rut=abc">
                    Example &amp; Docs
                  </a>
                  <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com">
                    One <b>useful</b> snippet &amp; more.
                  </a>
                </div>
                <div class="result">
                  <a class="result__a" href="https://direct.example/path">Direct Result</a>
                  <div class="result__snippet">Second &#x26; result.</div>
                </div>
                """;

        var results = NemoClient.parseDuckDuckGoResults(html, 5);

        assertEquals(2, results.size());
        assertEquals("Example & Docs", results.get(0).title());
        assertEquals("https://example.com/docs?x=1", results.get(0).url());
        assertEquals("One useful snippet & more.", results.get(0).snippet());
        assertEquals("Direct Result", results.get(1).title());
        assertEquals("https://direct.example/path", results.get(1).url());
        assertEquals("Second & result.", results.get(1).snippet());
    }

    @Test
    void webSearchRejectsBlankQueriesWithoutNetwork() {
        assertTrue(NemoClient.webSearch(json(Map.of("query", " "))).contains("query is blank"));
    }

    @Test
    void delegateTaskRejectsBlankTaskWithoutNetwork() throws Exception {
        Path project = tempDir.resolve("delegate-blank");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("delegate-blank", "delegate_task", json(Map.of("task", " "))));

        assertTrue(output.contains("task is blank"));
    }

    @Test
    void macOsSandboxProfileAllowsOnlyWorkspaceWrites() throws Exception {
        Path project = tempDir.resolve("workspace-profile");
        Files.createDirectories(project);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String profile = NemoClient.macOsSandboxProfile(configuration, project);

        assertTrue(profile.contains("(deny file-write*)"));
        assertTrue(profile.contains("(allow file-write* (literal \"/dev/null\"))"));
        assertTrue(profile.contains(project.toAbsolutePath().normalize().toString()));
        assertFalse(profile.contains(tempDir.resolve("outside").toString()));
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
                false,
                false,
                false,
                false,
                false,
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
    void executesSwimHelpToolInReadOnlyMode() throws Exception {
        Path project = tempDir.resolve("help-tool");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .build();

        String index = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("help-index", "swim_help", json(Map.of())));
        String chapter = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("help-files", "swim_help", json(Map.of("topic", "files"))));

        assertTrue(index.contains("SWIM Help Index"));
        assertTrue(index.contains("files - Files, Buffers, and Panes"));
        assertTrue(chapter.contains("Files, Buffers, and Panes"));
        assertTrue(chapter.contains(":bnext and :bprev cycle through buffers"));
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
                false,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4", "run_command", json(Map.of("command", "printf 'ok'"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(output.contains("ok"));
    }

    @Test
    void runCommandBlocksShellControlOperatorsByDefault() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));

        assertTrue(output.contains("blocked by Nemo policy"));
        assertFalse(Files.exists(owned));
    }

    @Test
    void runCommandBlocksDangerousExecutablesByDefault() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked", "run_command", json(Map.of("command", "rm note.txt"))));

        assertTrue(output.contains("blocked by Nemo policy"));
        assertTrue(Files.exists(file));
    }

    @Test
    void runCommandTrustedPolicyPreservesRawShellBehavior() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolCommandPolicy("trusted")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("trusted", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(Files.exists(owned));
    }

    @Test
    void readOnlyPermissionBlocksMutatingTools() throws Exception {
        Path project = tempDir.resolve("workspace-read-only");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .build();

        String shellOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-shell", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));
        String writeOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-write", "write_file", json(Map.of(
                        "path", "note.txt",
                        "content", "changed\n"))));

        assertTrue(shellOutput.contains("blocked by Nemo permissions"));
        assertTrue(writeOutput.contains("blocked by Nemo permissions"));
        assertEquals("hello\n", Files.readString(file));
        assertFalse(Files.exists(owned));
    }

    @Test
    void fullAccessPermissionBypassesRestrictedCommandPolicy() throws Exception {
        Path project = tempDir.resolve("workspace-full-access");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("full-access")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("full-access", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(Files.exists(owned));
    }

    @Test
    void requiredOsSandboxBlocksWhenUnavailable() throws Exception {
        String originalOverride = System.getProperty("swim.nemo.os_sandbox_available");
        System.setProperty("swim.nemo.os_sandbox_available", "false");
        NemoClient.resetMacOsSandboxAvailabilityForTests();
        try {
            Path project = tempDir.resolve("workspace-required-sandbox");
            Files.createDirectories(project);
            Path file = project.resolve("note.txt");
            Files.writeString(file, "hello\n");
            var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
            var configuration = NemoClient.Configuration.builder()
                    .workspaceRoot(project)
                    .toolOsSandbox("required")
                    .build();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("sandbox-required", "run_command", json(Map.of("command", "printf ok"))));

            assertTrue(output.contains("exit_code: sandbox_unavailable"));
            assertFalse(output.contains("ok"));
        } finally {
            if (originalOverride == null) {
                System.clearProperty("swim.nemo.os_sandbox_available");
            } else {
                System.setProperty("swim.nemo.os_sandbox_available", originalOverride);
            }
            NemoClient.resetMacOsSandboxAvailabilityForTests();
        }
    }

    @Test
    void workspaceWriteOsSandboxDeniesWritesOutsideWorkspaceWhenAvailable() throws Exception {
        Assumptions.assumeTrue(NemoClient.isMacOsSandboxAvailable(), "macOS sandbox-exec unavailable");
        Path project = tempDir.resolve("workspace-sandbox");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path allowed = project.resolve("allowed.txt");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolCommandPolicy("trusted")
                .toolOsSandbox("required")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("sandbox", "run_command", json(Map.of(
                        "command", "touch allowed.txt; touch " + shellQuoteForTest(outside)))));

        assertTrue(Files.exists(allowed));
        assertFalse(Files.exists(outside));
        assertTrue(output.contains("Operation not permitted") || output.contains("exit_code: 1"));
    }

    @Test
    void workspaceWriteOsSandboxDenialCanEscalateWithApproval() throws Exception {
        Assumptions.assumeTrue(NemoClient.isMacOsSandboxAvailable(), "macOS sandbox-exec unavailable");
        Path project = tempDir.resolve("workspace-sandbox-approval");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path outside = tempDir.resolve("outside-approved.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolCommandPolicy("trusted")
                .toolOsSandbox("auto")
                .toolApprovalPolicy("on-escalation")
                .build();
        var approvalCount = new AtomicInteger();
        var approvalReason = new AtomicReference<String>();
        NemoClient.ToolExecutionSession approvalSession = (workspaceRoot, request) -> {
            approvalCount.incrementAndGet();
            approvalReason.set(request.reason());
            return new NemoClient.ApprovalResult(true, false);
        };

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("sandbox-approval", "run_command", json(Map.of(
                        "command", "touch " + shellQuoteForTest(outside)))),
                approvalSession);

        assertEquals(1, approvalCount.get());
        assertEquals("OS sandbox blocked filesystem write", approvalReason.get());
        assertTrue(Files.exists(outside));
        assertTrue(output.contains("exit_code: 0"));
    }

    @Test
    void deniedEscalationApprovalIsReportedToNemo() throws Exception {
        Path project = tempDir.resolve("denied-escalation");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolApprovalPolicy("on-escalation")
                .build();
        var approvalCount = new AtomicInteger();
        NemoClient.ToolExecutionSession approvalSession = (workspaceRoot, request) -> {
            approvalCount.incrementAndGet();
            return new NemoClient.ApprovalResult(false, false);
        };

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("policy-denied", "run_command", json(Map.of("command", "rm target"))),
                approvalSession);

        assertEquals(1, approvalCount.get());
        assertTrue(output.contains("restricted mode does not allow rm"));
        assertTrue(output.contains("Nemo approval denied: user denied run_command"));
    }

    @Test
    void reportsRecoverableHintForMavenProjectListCommandsWithoutAlsoMake() {
        assertTrue(NemoClient.mavenAlsoMakeHint(
                "mvn -q -pl swim-core -Dtest=NemoChatIT,ChatPanelViewTest test").contains(" -am"));
        assertEquals(
                null,
                NemoClient.mavenAlsoMakeHint("mvn -q -pl swim-core -am -Dtest=NemoChatIT test"));
        assertEquals(
                null,
                NemoClient.mavenAlsoMakeHint("printf 'ok'"));
    }

    @Test
    void executesRunCommandWhenModelRepeatsWorkspaceDirectoryName() throws Exception {
        Path project = tempDir.resolve(".swim");
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
                false,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4b", "run_command", json(Map.of(
                        "command", "printf 'ok'",
                        "cwd", ".swim"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(output.contains("ok"));
    }

    @Test
    void runCommandTreatsFileCwdAsItsParentDirectory() throws Exception {
        Path project = tempDir.resolve("workspace");
        Path srcDir = project.resolve("src");
        Files.createDirectories(srcDir);
        Path file = srcDir.resolve("Main.txt");
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
                false,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4c", "run_command", json(Map.of(
                        "cwd", "src/Main.txt",
                        "command", "pwd"))));

        assertTrue(output.contains(srcDir.toString()));
    }

    @Test
    void executeToolSafelyReturnsRecoverableMessageForToolErrors() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("Main.txt");
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
                false,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        String output = NemoClient.executeToolSafely(configuration, context,
                new NemoClient.ToolCall("4d", "run_command", json(Map.of(
                        "cwd", "missing.txt",
                        "command", "pwd"))));

        assertTrue(output.startsWith("Tool run_command failed:"));
        assertTrue(output.contains("Recover by inspecting the path"));
    }

    @Test
    void runCommandReturnsRecoverableHintForMavenProjectSelectionWithoutAlsoMake() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("Main.txt");
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
                false,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4e", "run_command", json(Map.of(
                        "command", "mvn -q -pl swim-core -Dtest=NemoChatIT,ChatPanelViewTest test"))));

        assertTrue(output.startsWith("Tool run_command failed:"));
        assertTrue(output.contains("must also include -am"));
    }

    @Test
    void executesWriteFileToolAndSynchronizesCurrentBuffer() throws Exception {
        Path project = tempDir.resolve("workspace");
        Path file = project.resolve("src/Main.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Demo {}\n");
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
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                50,
                4000,
                5);

        try (var harness = HeadlessWindowHarness.create(file, 80, 20)) {
            var context = harness.getWindow().getBufferContext();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("5", "write_file", json(Map.of(
                            "path", "src/Main.txt",
                            "content", "class Updated {}\n"))));

            assertTrue(output.contains("wrote 17 chars to src/Main.txt"));
            assertEquals("class Updated {}\n", Files.readString(file));
            assertEquals("class Updated {}\n", context.getBuffer().getString());
        }
    }

    @Test
    void appendsToolRoundWithoutPreviousResponseIdState() {
        var history = new com.google.gson.JsonArray();
        history.add(NemoClient.createUserInputMessage("initial prompt"));

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
        assertEquals("message", history.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("function_call", history.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("function_call_output", history.get(2).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void createsInitialUserInputAsMessageItem() {
        var message = NemoClient.createUserInputMessage("hello");

        assertEquals("message", message.get("type").getAsString());
        assertEquals("user", message.get("role").getAsString());
        assertEquals("input_text", message.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("hello", message.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void parsesStreamedResponsesTextAndUsage() {
        JsonObject response = NemoClient.parseResponsesBody("""
                event: response.output_item.done
                data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}}

                event: response.completed
                data: {"type":"response.completed","response":{"output":[],"usage":{"input_tokens":27},"limits":{"max_context_tokens":270}}}

                """);

        assertEquals("ok", NemoClient.extractOutputText(response.toString()));
        assertEquals(10, NemoClient.extractContextUsagePercent(response));
    }

    @Test
    void parsesStreamedResponsesFunctionCalls() {
        JsonObject response = NemoClient.parseResponsesBody("""
                event: response.output_item.done
                data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"list_files","arguments":"{\\"path\\":\\".\\"}"}}

                event: response.completed
                data: {"type":"response.completed","response":{"output":[]}}

                """);

        List<NemoClient.ToolCall> calls = NemoClient.extractToolCalls(response);

        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).callId());
        assertEquals("list_files", calls.get(0).name());
        assertEquals(".", calls.get(0).arguments().get("path").getAsString());
    }

    private static int promptBudgetChars(int contextWindowTokens) {
        int reserve = Math.max(32, contextWindowTokens / 10);
        return (contextWindowTokens - reserve) * 3;
    }

    private static com.google.gson.JsonObject json(Map<String, ?> values) {
        return com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(values)).getAsJsonObject();
    }

    private static AutoCloseable registerPluginTool(String pluginId, String name, String description,
            boolean availableInReadOnly, boolean requiresApproval,
            Function<SwimNemoToolInvocation, String> executor) {
        return SwimNemoToolRegistry.register(pluginId, new SwimNemoTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getInputSchemaJson() {
                return """
                        {
                          "type": "object",
                          "properties": {
                            "message": {
                              "type": "string",
                              "description": "Message for the plugin tool."
                            }
                          },
                          "required": ["message"],
                          "additionalProperties": false
                        }
                        """;
            }

            @Override
            public boolean availableInReadOnly() {
                return availableInReadOnly;
            }

            @Override
            public boolean requiresApproval() {
                return requiresApproval;
            }

            @Override
            public String execute(SwimNemoToolInvocation invocation) {
                return executor.apply(invocation);
            }
        });
    }


    @Test
    void executesGitHelpersAndApplyPatchTool() throws Exception {
        Path project = tempDir.resolve("repo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("note.txt"), "hello\n");
        runGit(project, "git init");
        runGit(project, "git config user.email 'nemo@example.com'");
        runGit(project, "git config user.name 'Nemo'");
        runGit(project, "git add note.txt");
        runGit(project, "git commit -m init");
        Files.writeString(project.resolve("note.txt"), "hello\nworld\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), project.resolve("note.txt"));
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
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                50,
                4000,
                5);

        String status = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("5", "git_status", json(Map.of())));
        String diff = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("6", "git_diff", json(Map.of())));
        String patchResult = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("7", "apply_patch", json(Map.of(
                        "patch", "diff --git a/note.txt b/note.txt\n--- a/note.txt\n+++ b/note.txt\n@@ -1,2 +1,3 @@\n hello\n world\n+done\n"))));
        String addResult = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("8", "git_add", json(Map.of("path", "note.txt"))));
        String commitResult = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("9", "git_commit", json(Map.of("message", "Update note"))));

        assertTrue(status.contains("note.txt"));
        assertTrue(diff.contains("+world"));
        assertTrue(patchResult.contains("exit_code: 0"));
        assertTrue(addResult.contains("exit_code: 0"));
        assertTrue(commitResult.contains("exit_code: 0"));
        assertEquals("hello\nworld\ndone\n", Files.readString(project.resolve("note.txt")));
        assertEquals("Update note", runGitCapture(project, "git log -1 --pretty=%s").trim());
    }

    @Test
    void gitAddStagesMultiplePathsFromArray() throws Exception {
        Path project = tempDir.resolve("repo-multi-add");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "a\n");
        Files.createDirectories(project.resolve("dir"));
        Files.writeString(project.resolve("dir/b.txt"), "b\n");
        runGit(project, "git init");
        runGit(project, "git config user.email 'nemo@example.com'");
        runGit(project, "git config user.name 'Nemo'");
        runGit(project, "git add .");
        runGit(project, "git commit -m init");
        Files.writeString(project.resolve("a.txt"), "aa\n");
        Files.writeString(project.resolve("dir/b.txt"), "bb\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), project.resolve("a.txt"));
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
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                50,
                4000,
                5);

        String addResult = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("10", "git_add", json(Map.of("paths", List.of("a.txt", "dir/b.txt")))));

        assertTrue(addResult.contains("exit_code: 0"));
        String cachedDiff = runGitCapture(project, "git diff --cached --name-only");
        assertTrue(cachedDiff.contains("a.txt"));
        assertTrue(cachedDiff.contains("dir/b.txt"));
    }

    @Test
    void gitAddFallsBackToWholeWorkspaceWhenPathIsBlank() throws Exception {
        Path project = tempDir.resolve("repo-add-all");
        Files.createDirectories(project);
        Files.writeString(project.resolve("note.txt"), "one\n");
        runGit(project, "git init");
        runGit(project, "git config user.email 'nemo@example.com'");
        runGit(project, "git config user.name 'Nemo'");
        runGit(project, "git add .");
        runGit(project, "git commit -m init");
        Files.writeString(project.resolve("note.txt"), "two\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), project.resolve("note.txt"));
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
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                50,
                4000,
                5);

        String addResult = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("11", "git_add", json(Map.of("path", ""))));

        assertTrue(addResult.contains("exit_code: 0"));
        assertTrue(runGitCapture(project, "git diff --cached --name-only").contains("note.txt"));
    }

    private static void runGit(Path cwd, String command) throws IOException, InterruptedException {
        var process = new ProcessBuilder("zsh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException(new String(process.getInputStream().readAllBytes()));
        }
    }

    private static String runGitCapture(Path cwd, String command) throws IOException, InterruptedException {
        var process = new ProcessBuilder("zsh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException(output);
        }
        return output;
    }

    private static String shellQuoteForTest(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private MailClient secretMailClient() {
        return new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(),
                        List.of(new MailThreadSummary(1L, "work", "SECRET SUBJECT", "sender@example.com",
                                "SECRET SNIPPET", "2026-05-15T10:00:00Z", true, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(1L, threadId, "SECRET SUBJECT", "sender@example.com",
                        "dest@example.com", "2026-05-15T10:00:00Z", "SECRET BODY", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return tempDir.resolve(".swim/email");
            }
        };
    }

    private static NemoMcpServerConfig fakeMcpServer(String name) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new NemoMcpServerConfig(
                name,
                true,
                java.toString(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()),
                Map.of(),
                null,
                5);
    }
}
