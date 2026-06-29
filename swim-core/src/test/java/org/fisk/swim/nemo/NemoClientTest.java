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
        assertTrue(prompt.contains("current_editor_context reports the active editor workspace"));
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
                .toolWebSearch(false)
                .toolDelegateTask(false)
                .toolScreenSnapshot(false)
                .toolDriveEditor(false)
                .toolListFiles(false)
                .toolReadFile(false)
                .toolSearchFiles(false)
                .toolRunCommand(false)
                .toolWriteFile(false)
                .toolApplyPatch(false)
                .toolGitStatus(false)
                .toolGitDiff(false)
                .toolGitAdd(false)
                .toolGitCommit(false)
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
                .toolWebSearch(false)
                .toolDelegateTask(false)
                .toolScreenSnapshot(false)
                .toolDriveEditor(false)
                .toolListFiles(false)
                .toolReadFile(false)
                .toolSearchFiles(false)
                .toolRunCommand(false)
                .toolWriteFile(false)
                .toolApplyPatch(false)
                .toolGitStatus(false)
                .toolGitDiff(false)
                .toolGitAdd(false)
                .toolGitCommit(false)
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
        assertEquals("https://example.invalid/litellm", configuration.baseUrl());
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
    void normalizesZaiProviderAliasesAndDefaultsToZhipuRootUrl() throws IOException {
        Path config = tempDir.resolve("nemo.conf");
        Files.writeString(config, String.join("\n",
                "provider=z.ai",
                "api_key=token.secret",
                "model=glm-5",
                "reasoning_effort=xhigh"));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("zai", configuration.provider());
        assertTrue(configuration.isZaiProvider());
        assertTrue(configuration.requiresApiKey());
        assertEquals("https://open.bigmodel.cn/", configuration.baseUrl());
        assertEquals("xhigh", configuration.reasoningEffort());
    }

    @Test
    void acceptsLegacyZipuAndZhipuProviderSpellingsAsZai() throws IOException {
        Path zipuConfig = tempDir.resolve("zipu.conf");
        Files.writeString(zipuConfig, "provider=zipuai\n");
        Path zhipuConfig = tempDir.resolve("zhipu.conf");
        Files.writeString(zhipuConfig, "provider=zhipuai\n");

        assertEquals("zai", NemoClient.loadConfiguration(zipuConfig).provider());
        assertEquals("zai", NemoClient.loadConfiguration(zhipuConfig).provider());
    }

    @Test
    void normalizesGeminiProviderAliasesForGoogleAiStudioKeys() throws IOException {
        Path config = tempDir.resolve("gemini.conf");
        Files.writeString(config, String.join("\n",
                "provider=google-ai-studio",
                "api_key=gemini-token",
                "model=gemini-2.5-flash",
                "reasoning_effort=medium"));

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("gemini", configuration.provider());
        assertTrue(configuration.isGeminiProvider());
        assertTrue(configuration.requiresApiKey());
        assertEquals("gemini-token", configuration.apiKey());
        assertEquals("medium", configuration.reasoningEffort());
        assertEquals("", configuration.baseUrl());
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

        var tools = NemoLangChain4jClient.buildToolSpecifications(configuration);

        assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("mcp__mock__echo")
                && tool.description().contains("Echo a message.")));
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

        assertFalse(toolNames(configuration).contains("mcp__mock__echo"));

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

        var tools = NemoLangChain4jClient.buildToolSpecifications(NemoClient.Configuration.builder().build());

        assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("plugin__sample_plugin__echo")
                && tool.description().contains("Sample plugin echo.")));
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

        List<String> tools = toolNames(configuration);
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
    void responsesUrlConfigurationConvertsToChatBaseUrl() throws IOException {
        Path config = tempDir.resolve("nemo.conf");
        Files.writeString(config, "responses_url=https://example.invalid/custom/responses\n");

        var configuration = NemoClient.loadConfiguration(config);

        assertEquals("https://example.invalid/custom", configuration.baseUrl());
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

        var tools = NemoLangChain4jClient.buildToolSpecifications(configuration);

        assertEquals(31, tools.size());
        assertEquals("web_search", tools.get(0).name());
        assertEquals("delegate_task", tools.get(1).name());
        assertEquals("worker_status", tools.get(2).name());
        assertEquals("read_worker", tools.get(3).name());
        assertEquals("join_worker", tools.get(4).name());
        assertEquals("message_worker", tools.get(5).name());
        assertEquals("start_editor_control", tools.get(6).name());
        assertEquals("screen_snapshot", tools.get(7).name());
        assertEquals("drive_editor", tools.get(8).name());
        assertEquals("finish_editor_control", tools.get(9).name());
        assertEquals("swim_help", tools.get(10).name());
        assertEquals("current_editor_context", tools.get(11).name());
    }

    @Test
    void buildToolsOmitsMutatingToolsInReadOnlyMode() {
        var configuration = NemoClient.Configuration.builder()
                .toolWebSearch(true)
                .toolPermissionMode("read-only")
                .build();

        var names = toolNames(configuration);

        assertTrue(names.contains("list_files"));
        assertTrue(names.contains("delegate_task"));
        assertTrue(names.contains("worker_status"));
        assertTrue(names.contains("read_worker"));
        assertTrue(names.contains("join_worker"));
        assertTrue(names.contains("message_worker"));
        assertTrue(names.contains("start_editor_control"));
        assertTrue(names.contains("screen_snapshot"));
        assertTrue(names.contains("finish_editor_control"));
        assertTrue(names.contains("current_editor_context"));
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("find"));
        assertTrue(names.contains("search_files"));
        assertTrue(names.contains("git_status"));
        assertTrue(names.contains("git_diff"));
        assertFalse(names.contains("run_command"));
        assertFalse(names.contains("shell_start"));
        assertFalse(names.contains("shell_poll"));
        assertFalse(names.contains("shell_stop"));
        assertFalse(names.contains("shell_save"));
        assertFalse(names.contains("shell_list"));
        assertFalse(names.contains("shell_run"));
        assertFalse(names.contains("mvn"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("search_replace"));
        assertFalse(names.contains("apply_patch"));
        assertFalse(names.contains("git_add"));
        assertFalse(names.contains("git_commit"));
        assertFalse(names.contains("drive_editor"));
    }

    @Test
    void buildToolsDescribeWorkspaceWritesAsPersistent() {
        String descriptions = NemoLangChain4jClient.buildToolSpecifications(NemoClient.Configuration.builder().build())
                .stream()
                .map(tool -> tool.description())
                .toList()
                .toString();

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
        Path otherFile = project.resolve("other/Main.txt");
        Files.createDirectories(otherFile.getParent());
        Files.writeString(otherFile, "alpha\n");
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
        String scopedList = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("4", "list_files", json(Map.of("directory", "src"))));
        String scopedSearch = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("5", "search_files", json(Map.of(
                        "query", "alpha",
                        "directory", "src"))));
        String found = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("6", "find", json(Map.of(
                        "query", "main",
                        "directory", "src"))));
        String foundGlob = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("7", "find", json(Map.of(
                        "query", "*.txt",
                        "directory", "src"))));

        assertTrue(listed.contains("src/Main.txt"));
        assertTrue(read.contains("2: beta"));
        assertTrue(read.contains("3: alpha"));
        assertTrue(searched.contains("src/Main.txt:1: alpha"));
        assertTrue(scopedList.contains("src/Main.txt"));
        assertTrue(scopedSearch.contains("src/Main.txt:1: alpha"));
        assertFalse(scopedSearch.contains("other/Main.txt"));
        assertTrue(found.contains("src/Main.txt"));
        assertFalse(found.contains("other/Main.txt"));
        assertTrue(foundGlob.contains("src/Main.txt"));
        assertFalse(foundGlob.contains("other/Main.txt"));
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
    void executesCurrentEditorContextToolInReadOnlyMode() throws Exception {
        Path project = tempDir.resolve("current-context");
        Files.createDirectories(project.resolve(".git"));
        Path file = project.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "SECRET CONTENT\n");
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolPermissionMode("read-only")
                .build();

        try (var harness = HeadlessWindowHarness.create(file, 80, 20)) {
            var context = harness.getWindow().getBufferContext();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("current", "current_editor_context", json(Map.of())));

            assertTrue(output.contains("workspace: buffer"));
            assertTrue(output.contains("workspace_root: " + project.toAbsolutePath().normalize()));
            assertTrue(output.contains("current_file: " + file.toAbsolutePath().normalize()));
            assertTrue(output.contains("current_file_relative_to_workspace: src/Main.java"));
            assertTrue(output.contains("project_root: " + project.toAbsolutePath().normalize()));
            assertTrue(output.contains("current_file_relative_to_project: src/Main.java"));
            assertFalse(output.contains("SECRET CONTENT"));
        }
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
    void runCommandBlocksShellControlOperatorsWhenOsSandboxDisabled() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolOsSandbox("disabled")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));

        assertTrue(output.contains("blocked by Nemo policy"));
        assertFalse(Files.exists(owned));
    }

    @Test
    void runCommandBlocksDangerousExecutablesWhenOsSandboxDisabled() throws Exception {
        Path project = tempDir.resolve("workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolOsSandbox("disabled")
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
                .toolOsSandbox("disabled")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("trusted", "run_command", json(Map.of("command", "printf ok; touch owned.txt"))));

        assertTrue(output.contains("exit_code: 0"));
        assertTrue(Files.exists(owned));
    }

    @Test
    void sandboxedRunCommandSkipsCommandPolicyAndOnRequestApprovalWhenAvailable() throws Exception {
        Assumptions.assumeTrue(NemoClient.isMacOsSandboxAvailable(), "macOS sandbox-exec unavailable");
        Path project = tempDir.resolve("workspace-sandbox-shell-policy");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Path owned = project.resolve("owned.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolCommandPolicy("restricted")
                .toolOsSandbox("required")
                .toolApprovalPolicy("on-request")
                .build();
        var approvalSession = new RecordingToolSession();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("sandbox-policy", "run_command", json(Map.of(
                        "command", "printf ok; touch owned.txt"))),
                approvalSession);

        assertEquals(0, approvalSession.approvals.get());
        assertTrue(output.contains("exit_code: 0"));
        assertTrue(Files.exists(owned));
    }

    @Test
    void asyncShellCanBeStartedAndPolledById() throws Exception {
        Path project = tempDir.resolve("async-shell-workspace");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolCommandPolicy("trusted")
                .toolOsSandbox("disabled")
                .build();

        String started = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("shell-start", "shell_start", json(Map.of(
                        "command", "printf start; sleep 0.1; printf done"))));

        String shellId = shellIdFromOutput(started);
        assertTrue(shellId.startsWith("shell-"));
        assertTrue(started.contains("status: running"));

        String poll = "";
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            poll = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-poll", "shell_poll", json(Map.of(
                            "shell_id", shellId,
                            "max_output_chars", 100))));
            if (poll.contains("status: done")) {
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(poll.contains("status: done"));
        assertTrue(poll.contains("exit_code: 0"));
        assertTrue(poll.contains("startdone"));

        String forgotten = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("shell-forget", "shell_poll", json(Map.of(
                        "shell_id", shellId,
                        "forget_if_finished", true))));
        assertTrue(forgotten.contains("status: done"));

        String missing = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("shell-missing", "shell_poll", json(Map.of("shell_id", shellId))));
        assertTrue(missing.contains("Unknown shell: " + shellId));
    }

    @Test
    void approvedShellLineCanBeSavedAndReusedWithoutCommandPolicyApproval() throws Exception {
        String originalUserHome = switchToTempUserHome();
        try {
            Path project = tempDir.resolve("saved-shell-workspace");
            Files.createDirectories(project);
            Path file = project.resolve("note.txt");
            Files.writeString(file, "hello");
            var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
            var configuration = NemoClient.Configuration.builder()
                    .workspaceRoot(project)
                    .toolCommandPolicy("restricted")
                    .toolOsSandbox("disabled")
                    .build();
            var approvalSession = new RecordingToolSession();

            String saved = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-save", "shell_save", json(Map.of(
                            "name", "compile",
                            "command", "printf ok; touch compiled.txt"))),
                    approvalSession);

            assertEquals(1, approvalSession.approvals.get());
            assertEquals("shell_save", approvalSession.request.get().toolName());
            assertTrue(saved.contains("saved shell line compile"));

            String listed = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-list", "shell_list", json(Map.of())));
            assertTrue(listed.contains("compile | cwd=. | printf ok; touch compiled.txt"));

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-run", "shell_run", json(Map.of("name", "compile"))));

            assertTrue(output.contains("exit_code: 0"));
            assertTrue(Files.exists(project.resolve("compiled.txt")));
        } finally {
            System.setProperty("user.home", originalUserHome);
            NemoClient.getInstance().resetForTests();
        }
    }

    @Test
    void savedShellLineUsesSandboxWithoutSaveApprovalAndCanEscalateAfterSandboxDenial() throws Exception {
        Assumptions.assumeTrue(NemoClient.isMacOsSandboxAvailable(), "macOS sandbox-exec unavailable");
        String originalUserHome = switchToTempUserHome();
        try {
            Path project = tempDir.resolve("saved-shell-sandbox-workspace");
            Files.createDirectories(project);
            Path file = project.resolve("note.txt");
            Path outside = tempDir.resolve("saved-shell-outside.txt");
            Files.writeString(file, "hello");
            var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
            var configuration = NemoClient.Configuration.builder()
                    .workspaceRoot(project)
                    .toolCommandPolicy("restricted")
                    .toolOsSandbox("auto")
                    .toolApprovalPolicy("on-escalation")
                    .build();
            var approvalSession = new RecordingToolSession();

            String saved = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-save", "shell_save", json(Map.of(
                            "name", "outside",
                            "command", "touch " + shellQuoteForTest(outside)))),
                    approvalSession);

            assertEquals(0, approvalSession.approvals.get());
            assertTrue(saved.contains("saved shell line outside"));

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("shell-run", "shell_run", json(Map.of("name", "outside"))),
                    approvalSession);

            assertEquals(1, approvalSession.approvals.get());
            assertEquals("OS sandbox blocked filesystem write", approvalSession.request.get().reason());
            assertTrue(Files.exists(outside));
            assertTrue(output.contains("exit_code: 0"));
        } finally {
            System.setProperty("user.home", originalUserHome);
            NemoClient.getInstance().resetForTests();
        }
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
        String asyncShellOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-async-shell", "shell_start", json(Map.of("command", "printf ok"))));
        String mvnOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-mvn", "mvn", json(Map.of("arguments", List.of("-q", "test")))));
        String writeOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-write", "write_file", json(Map.of(
                        "path", "note.txt",
                        "content", "changed\n"))));
        String replaceOutput = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("blocked-replace", "search_replace", json(Map.of(
                        "path", "note.txt",
                        "search", "hello",
                        "replace", "changed"))));

        assertTrue(shellOutput.contains("blocked by Nemo permissions"));
        assertTrue(asyncShellOutput.contains("blocked by Nemo permissions"));
        assertTrue(mvnOutput.contains("blocked by Nemo permissions"));
        assertTrue(writeOutput.contains("blocked by Nemo permissions"));
        assertTrue(replaceOutput.contains("blocked by Nemo permissions"));
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
    void autoOsSandboxWithoutBackendDoesNotRunUnsandboxedWithoutApprovalSession() throws Exception {
        String originalOverride = System.getProperty("swim.nemo.os_sandbox_available");
        System.setProperty("swim.nemo.os_sandbox_available", "false");
        NemoClient.resetMacOsSandboxAvailabilityForTests();
        try {
            Path project = tempDir.resolve("workspace-auto-sandbox-unavailable");
            Files.createDirectories(project);
            Path file = project.resolve("note.txt");
            Path owned = project.resolve("owned.txt");
            Files.writeString(file, "hello\n");
            var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
            var configuration = NemoClient.Configuration.builder()
                    .workspaceRoot(project)
                    .toolCommandPolicy("trusted")
                    .toolOsSandbox("auto")
                    .build();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("sandbox-auto", "run_command", json(Map.of("command", "touch owned.txt"))));

            assertTrue(output.contains("exit_code: sandbox_unavailable"));
            assertFalse(Files.exists(owned));
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
                .toolOsSandbox("disabled")
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
            var window = harness.getWindow();
            var context = window.getBufferContext();
            window.splitActiveBufferHorizontally();
            var splitContext = window.getBufferContext();

            String output = NemoClient.executeTool(configuration, context,
                    new NemoClient.ToolCall("5", "write_file", json(Map.of(
                            "path", "src/Main.txt",
                            "content", "class Updated {}\n"))));

            assertTrue(output.contains("wrote 17 chars to src/Main.txt"));
            assertEquals("class Updated {}\n", Files.readString(file));
            assertEquals("class Updated {}\n", context.getBuffer().getString());
            assertEquals("class Updated {}\n", splitContext.getBuffer().getString());
        }
    }

    @Test
    void writeFileDetailedResultKeepsPatchOutOfModelOutputButShowsItInTraceDisplay() throws Exception {
        Path project = tempDir.resolve("patch-display");
        Files.createDirectories(project);
        Path file = project.resolve("note.txt");
        Files.writeString(file, "old\nkeep\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        var call = new NemoClient.ToolCall("write", "write_file", json(Map.of(
                "path", "note.txt",
                "content", "new\nkeep\n")));

        NemoClient.ToolExecutionResult result = NemoClient.executeToolDetailedSafely(configuration, context, call, null);
        NemoClient.ToolTrace trace = NemoClient.toolTrace(call, result);

        assertTrue(result.output().contains("wrote 9 chars to note.txt"));
        assertFalse(result.output().contains("-old"));
        assertTrue(result.displayPatch().contains("-old"));
        assertTrue(result.displayPatch().contains("+new"));
        assertFalse(trace.text().contains("-old"));
        assertTrue(trace.displayText().contains("-old"));
        assertTrue(trace.displayText().contains("+new"));
    }

    @Test
    void searchReplaceEditsScopedFileAndReturnsDisplayPatch() throws Exception {
        Path project = tempDir.resolve("replace-workspace");
        Path file = project.resolve("src/note.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "alpha beta alpha\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .build();
        var call = new NemoClient.ToolCall("replace", "search_replace", json(Map.of(
                "directory", "src",
                "path", "note.txt",
                "search", "alpha",
                "replace", "omega")));

        NemoClient.ToolExecutionResult result = NemoClient.executeToolDetailedSafely(configuration, context, call, null);
        NemoClient.ToolTrace trace = NemoClient.toolTrace(call, result);

        assertTrue(result.output().contains("replaced 2 matches in src/note.txt"));
        assertEquals("omega beta omega\n", Files.readString(file));
        assertTrue(result.displayPatch().contains("-alpha beta alpha"));
        assertTrue(result.displayPatch().contains("+omega beta omega"));
        assertFalse(trace.text().contains("-alpha"));
        assertTrue(trace.displayText().contains("+omega beta omega"));
    }

    @Test
    void mvnToolRunsWrapperFromRequestedSubdirectoryWithArguments() throws Exception {
        Path project = tempDir.resolve("mvn-workspace");
        Path module = project.resolve("module");
        Files.createDirectories(module);
        Path wrapper = project.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                pwd > mvn.out
                for arg in "$@"; do
                  printf '%s\\n' "$arg" >> mvn.out
                done
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        Path file = module.resolve("note.txt");
        Files.writeString(file, "hello\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var configuration = NemoClient.Configuration.builder()
                .workspaceRoot(project)
                .toolOsSandbox("disabled")
                .build();

        String output = NemoClient.executeTool(configuration, context,
                new NemoClient.ToolCall("mvn", "mvn", json(Map.of(
                        "directory", "module",
                        "arguments", List.of("-q", "test", "-DskipTests")))));

        assertTrue(output.contains("exit_code: 0"));
        String invocation = Files.readString(module.resolve("mvn.out"));
        assertTrue(invocation.contains(module.toString()));
        assertTrue(invocation.contains("-q\n"));
        assertTrue(invocation.contains("test\n"));
        assertTrue(invocation.contains("-DskipTests\n"));
    }

    private static int promptBudgetChars(int contextWindowTokens) {
        int reserve = Math.max(32, contextWindowTokens / 10);
        return (contextWindowTokens - reserve) * 3;
    }

    private static List<String> toolNames(NemoClient.Configuration configuration) {
        return NemoLangChain4jClient.buildToolSpecifications(configuration).stream()
                .map(tool -> tool.name())
                .toList();
    }

    private static com.google.gson.JsonObject json(Map<String, ?> values) {
        return com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(values)).getAsJsonObject();
    }

    private static String shellIdFromOutput(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("shell_id: ")) {
                return line.substring("shell_id: ".length()).trim();
            }
        }
        return "";
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

    private String switchToTempUserHome() throws IOException {
        String originalUserHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        NemoClient.getInstance().resetForTests();
        return originalUserHome;
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
