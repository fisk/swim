package org.fisk.swim.nemo;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimNemoToolDescriptor;
import org.fisk.swim.text.BufferContext;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;

final class NemoLangChain4jClient {
    NemoClient.ResponseResult request(NemoClient.Configuration configuration, BufferContext context,
            List<NemoClient.ChatTurn> turns, NemoClient.ToolExecutionSession executionSession) throws IOException, InterruptedException {
        PathInfo pathInfo = resolvePathInfo(configuration, context);
        List<NemoSkillDocument> skills = NemoSkillLoader.loadApplicableSkills(context, pathInfo.workspaceRoot(), configuration);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(NemoPromptBuilder.buildInput(context, turns, configuration, skills)));

        ChatModel chatModel = createModel(configuration);
        List<ToolSpecification> tools = buildToolSpecifications(configuration);
        TokenUsage cumulativeUsage = null;
        var toolTraces = new ArrayList<NemoClient.ToolTrace>();
        while (true) {
            List<NemoClient.ChatTurn> queuedTurns = NemoClient.drainQueuedTurns(executionSession);
            if (!queuedTurns.isEmpty()) {
                messages.add(new UserMessage(NemoClient.queuedWorkerMessage(queuedTurns)));
            }
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            cumulativeUsage = TokenUsage.sum(cumulativeUsage, response.tokenUsage());

            AiMessage aiMessage = response.aiMessage();
            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                for (ToolExecutionRequest toolCall : aiMessage.toolExecutionRequests()) {
                    var call = new NemoClient.ToolCall(
                            toolCall.id(),
                            toolCall.name(),
                            parseArguments(toolCall.arguments()));
                    NemoClient.ToolExecutionResult result = NemoClient.executeToolDetailedSafely(
                            configuration, context, call, executionSession);
                    toolTraces.add(NemoClient.toolTrace(call, result));
                    messages.add(new ToolExecutionResultMessage(toolCall.id(), toolCall.name(), result.output()));
                }
                continue;
            }

            String text = aiMessage.text();
            if ((text == null || text.isBlank()) && aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
                text = aiMessage.thinking();
            }
            if (text == null || text.isBlank()) {
                text = "Nemo returned no text.";
            }
            return new NemoClient.ResponseResult(text, contextUsagePercent(configuration, cumulativeUsage),
                    List.copyOf(toolTraces));
        }
    }

    private ChatModel createModel(NemoClient.Configuration configuration) {
        var builder = OpenAiChatModel.builder()
                .modelName(configuration.model())
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .maxRetries(configuration.maxRetries())
                .logRequests(configuration.logRequests())
                .logResponses(configuration.logResponses());
        if (!configuration.baseUrl().isBlank()) {
            builder.baseUrl(configuration.baseUrl());
        }
        if (!configuration.apiKey().isBlank()) {
            builder.apiKey(configuration.apiKey());
        }
        if (!configuration.organization().isBlank()) {
            builder.organizationId(configuration.organization());
        }
        if (!configuration.project().isBlank()) {
            builder.projectId(configuration.project());
        }
        var customHeaders = customHeaders(configuration);
        if (!customHeaders.isEmpty()) {
            builder.customHeaders(customHeaders);
        }
        if (!configuration.queryParameters().isEmpty()) {
            builder.customQueryParams(configuration.queryParameters());
        }
        if (!configuration.customParameters().isEmpty()) {
            builder.customParameters(configuration.customParameters());
        }
        if (configuration.temperature() != null) {
            builder.temperature(configuration.temperature());
        }
        if (configuration.topP() != null) {
            builder.topP(configuration.topP());
        }
        if (configuration.maxOutputTokens() != null) {
            builder.maxTokens(configuration.maxOutputTokens());
        }
        if (!configuration.reasoningEffort().isBlank()) {
            builder.reasoningEffort(configuration.reasoningEffort());
        }
        if (configuration.strictTools()) {
            builder.strictTools(true);
        }
        if (configuration.parallelToolCalls()) {
            builder.parallelToolCalls(true);
        }
        if (configuration.returnThinking()) {
            builder.returnThinking(true);
        }
        if (configuration.sendThinking()) {
            builder.sendThinking(true, configuration.thinkingFieldName());
        }
        return builder.build();
    }

    private static java.util.Map<String, String> customHeaders(NemoClient.Configuration configuration) {
        var headers = new java.util.LinkedHashMap<String, String>();
        for (var entry : configuration.headers().entrySet()) {
            String key = entry.getKey();
            if ("Authorization".equalsIgnoreCase(key)
                    || "Content-Type".equalsIgnoreCase(key)
                    || "OpenAI-Organization".equalsIgnoreCase(key)
                    || "OpenAI-Project".equalsIgnoreCase(key)) {
                continue;
            }
            headers.put(key, entry.getValue());
        }
        return headers;
    }

    private static JsonObject parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(arguments).getAsJsonObject();
    }

    private static Integer contextUsagePercent(NemoClient.Configuration configuration, TokenUsage usage) {
        Integer contextWindowTokens = configuration.contextWindowTokens();
        if (usage == null || usage.inputTokenCount() == null || contextWindowTokens == null || contextWindowTokens <= 0) {
            return null;
        }
        return (int) Math.round((usage.inputTokenCount() * 100.0) / contextWindowTokens);
    }

    private static PathInfo resolvePathInfo(NemoClient.Configuration configuration, BufferContext context) {
        return new PathInfo(NemoClient.resolveWorkspaceRoot(configuration, context));
    }

    static List<ToolSpecification> buildToolSpecifications(NemoClient.Configuration configuration) {
        var tools = new ArrayList<ToolSpecification>();
        if (configuration.toolWebSearch()) {
            tools.add(tool("web_search",
                    "Search the web. Prefer this only when the answer cannot be found in the workspace.",
                    JsonObjectSchema.builder()
                            .addStringProperty("query", "Text to search for.")
                            .addIntegerProperty("max_results", "Maximum number of results to return.")
                            .required(List.of("query"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolDelegateTask()) {
            tools.add(tool("delegate_task",
                    "Start a focused workspace task in a separate Nemo sub-agent worker and return immediately with the new session id. Use this to split investigation, review, or implementation work that can run in parallel. The sub-agent inherits this session's tools, permissions, sandbox, and approval policy, but cannot delegate again.",
                    JsonObjectSchema.builder()
                            .addStringProperty("task", "Detailed work request for the sub-agent.")
                            .addStringProperty("title", "Optional short label for the delegated task.")
                            .addProperty("focus_paths", JsonArraySchema.builder()
                                    .description("Optional workspace-relative paths the sub-agent should focus on.")
                                    .items(JsonStringSchema.builder().description("Workspace-relative path.").build())
                                    .build())
                            .required(List.of("task"))
                            .additionalProperties(false)
                            .build()));
            tools.add(tool("worker_status",
                    "List delegated Nemo workers and sessions for this workspace, or inspect one session's running/idle status.",
                    JsonObjectSchema.builder()
                            .addStringProperty("session_id", "Optional session id or title to inspect.")
                            .additionalProperties(false)
                            .build()));
            tools.add(tool("read_worker",
                    "Read a delegated Nemo worker/session transcript without waiting for it to finish.",
                    JsonObjectSchema.builder()
                            .addStringProperty("session_id", "Session id or unique title to read.")
                            .addIntegerProperty("max_turns", "Maximum transcript turns to include.")
                            .addIntegerProperty("max_output_chars", "Maximum characters to return.")
                            .required(List.of("session_id"))
                            .additionalProperties(false)
                            .build()));
            tools.add(tool("join_worker",
                    "Wait for a delegated Nemo worker/session to finish, bounded by timeout_seconds, then return its transcript.",
                    JsonObjectSchema.builder()
                            .addStringProperty("session_id", "Session id or unique title to join.")
                            .addIntegerProperty("timeout_seconds", "Maximum seconds to wait.")
                            .addIntegerProperty("max_turns", "Maximum transcript turns to include.")
                            .addIntegerProperty("max_output_chars", "Maximum characters to return.")
                            .required(List.of("session_id"))
                            .additionalProperties(false)
                            .build()));
            tools.add(tool("message_worker",
                    "Send an additional user message to a delegated Nemo worker/session without switching to it. If the worker is running, the message is queued and delivered at the next safe request boundary; if it is idle, it starts a new turn in that session.",
                    JsonObjectSchema.builder()
                            .addStringProperty("session_id", "Session id or unique title to message.")
                            .addStringProperty("message", "User message or correction for the worker.")
                            .required(List.of("session_id", "message"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolScreenSnapshot() || configuration.toolDriveEditor()) {
            tools.add(tool("start_editor_control",
                    "Request host approval to start an explicit editor-control session. Only one Nemo session can hold control at a time; call this before screen_snapshot or drive_editor, then finish_editor_control when done.",
                    JsonObjectSchema.builder()
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolScreenSnapshot()) {
            tools.add(tool("screen_snapshot",
                    "Return a host-filtered structured text snapshot of the current editor screen during an active editor-control session. Host-only approval overlays and Nemo's own chat contents are not included.",
                    JsonObjectSchema.builder()
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolDriveEditor() && NemoClient.isToolAllowedByPermission(configuration, "drive_editor")) {
            tools.add(tool("drive_editor",
                    "Send a bounded stream of text editor input to the active buffer during the active editor-control session and return before/after snapshots. Literal text is typed as characters; supported tokens include <ESC>, <ENTER>, <TAB>, <BACKSPACE>, <UP>, <DOWN>, <LEFT>, <RIGHT>, <PAGE-UP>, <PAGE-DOWN>, <SPACE>, <LT>, <GT>, and <CTRL-x>. Editor actions assess sandbox permissions at execution time: workspace-local navigation, editing, search, and saves are allowed when permitted; host overlays, shell, Nemo, mail, Slack, Todo, external workspaces, and other boundary-crossing actions are blocked.",
                    JsonObjectSchema.builder()
                            .addStringProperty("input", "Literal text plus optional key tokens to send to the editor.")
                            .addIntegerProperty("max_events", "Optional maximum parsed key events to process, capped at 500.")
                            .required(List.of("input"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolScreenSnapshot() || configuration.toolDriveEditor()) {
            tools.add(tool("finish_editor_control",
                    "Finish the active editor-control session, release the single-control lock, and reopen the Nemo chat conversation that invoked these tools so you can report findings to the user.",
                    JsonObjectSchema.builder()
                            .additionalProperties(false)
                            .build()));
        }
        tools.add(tool("swim_help",
                "Read SWIM editor help chapters. Use with no topic for the index, or pass a chapter id, chapter title, or search topic to learn how to operate the editor.",
                JsonObjectSchema.builder()
                        .addStringProperty("topic", "Optional chapter id, chapter title, or search text. Examples: start, movement, files, nemo.")
                        .additionalProperties(false)
                        .build()));
        tools.add(tool("current_editor_context",
                "Report the user's current editor workspace kind, current buffer file path when a buffer is active, and active project root. This is path-only orientation and does not read file contents or screen contents.",
                JsonObjectSchema.builder()
                        .additionalProperties(false)
                        .build()));
        for (NemoMcpClient.ToolDescriptor descriptor : NemoClient.mcpToolDescriptors(configuration)) {
            tools.add(mcpTool(descriptor));
        }
        for (SwimNemoToolDescriptor descriptor : NemoClient.pluginToolDescriptors(configuration)) {
            tools.add(pluginTool(descriptor));
        }
        if (configuration.toolListFiles()) {
            tools.add(tool("list_files",
                    "List files in the workspace. Use this to inspect project structure.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Path relative to the workspace root.")
                            .addIntegerProperty("max_results", "Maximum number of files to return.")
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolReadFile()) {
            tools.add(tool("read_file",
                    "Read a file from the workspace. Use start_line and end_line to limit output.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Path relative to the workspace root.")
                            .addIntegerProperty("start_line", "Optional 1-based start line.")
                            .addIntegerProperty("end_line", "Optional 1-based end line.")
                            .required(List.of("path"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolSearchFiles()) {
            tools.add(tool("search_files",
                    "Search text across workspace files and return matching lines.",
                    JsonObjectSchema.builder()
                            .addStringProperty("query", "Text to search for.")
                            .addStringProperty("path", "Optional path relative to the workspace root.")
                            .addIntegerProperty("max_results", "Maximum number of matches to return.")
                            .required(List.of("query"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolRunCommand() && NemoClient.isToolAllowedByPermission(configuration, "run_command")) {
            tools.add(tool("run_command",
                    "Run a simple workspace command and return exit code, stdout, and stderr. Restricted mode blocks shell control operators and high-risk executables. Nemo applies an OS filesystem-write sandbox outside full-access mode when available.",
                    JsonObjectSchema.builder()
                            .addStringProperty("command", "Shell command to execute.")
                            .addStringProperty("cwd", "Optional working directory relative to the workspace root.")
                            .required(List.of("command"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolWriteFile() && NemoClient.isToolAllowedByPermission(configuration, "write_file")) {
            tools.add(tool("write_file",
                    "Create or overwrite a real file in the workspace using full file contents. Successful writes are saved to disk and persist across Nemo/editor runs.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Path relative to the workspace root.")
                            .addStringProperty("content", "Full file contents to write.")
                            .required(List.of("path", "content"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolApplyPatch() && NemoClient.isToolAllowedByPermission(configuration, "apply_patch")) {
            tools.add(tool("apply_patch",
                    "Apply a targeted unified diff patch inside the workspace. Successful patches are saved to disk and persist across Nemo/editor runs.",
                    JsonObjectSchema.builder()
                            .addStringProperty("patch", "Unified diff patch text to apply.")
                            .required(List.of("patch"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolGitStatus()) {
            tools.add(tool("git_status",
                    "Show git status for the workspace or a subdirectory.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Optional path relative to the workspace root.")
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolGitDiff()) {
            tools.add(tool("git_diff",
                    "Show git diff for the workspace or a subdirectory.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Optional path relative to the workspace root.")
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolGitAdd() && NemoClient.isToolAllowedByPermission(configuration, "git_add")) {
            tools.add(tool("git_add",
                    "Stage files in git. Use this before git_commit when the user asks you to commit changes.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Optional file or directory path relative to the workspace root.")
                            .addProperty("paths", JsonArraySchema.builder()
                                    .description("Optional list of file or directory paths relative to the workspace root.")
                                    .items(JsonStringSchema.builder().description("Relative git pathspec.").build())
                                    .build())
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolGitCommit() && NemoClient.isToolAllowedByPermission(configuration, "git_commit")) {
            tools.add(tool("git_commit",
                    "Create a git commit from the staged changes.",
                    JsonObjectSchema.builder()
                            .addStringProperty("message", "Commit message to use.")
                            .required(List.of("message"))
                            .additionalProperties(false)
                            .build()));
        }
        return tools;
    }

    private static ToolSpecification tool(String name, String description, JsonObjectSchema parameters) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    private static ToolSpecification mcpTool(NemoMcpClient.ToolDescriptor descriptor) {
        var tool = new JsonObject();
        tool.addProperty("name", descriptor.exposedName());
        tool.addProperty("description", mcpToolDescription(descriptor));
        tool.add("parameters", descriptor.inputSchema());
        try {
            return ToolSpecification.fromJson(tool.toString());
        } catch (RuntimeException e) {
            return ToolSpecification.builder()
                    .name(descriptor.exposedName())
                    .description(mcpToolDescription(descriptor))
                    .parameters(JsonObjectSchema.builder().additionalProperties(true).build())
                    .build();
        }
    }

    private static ToolSpecification pluginTool(SwimNemoToolDescriptor descriptor) {
        var tool = new JsonObject();
        tool.addProperty("name", descriptor.exposedName());
        tool.addProperty("description", NemoClient.pluginToolDescription(descriptor));
        tool.add("parameters", NemoClient.pluginToolSchema(descriptor));
        try {
            return ToolSpecification.fromJson(tool.toString());
        } catch (RuntimeException e) {
            return ToolSpecification.builder()
                    .name(descriptor.exposedName())
                    .description(NemoClient.pluginToolDescription(descriptor))
                    .parameters(JsonObjectSchema.builder().additionalProperties(true).build())
                    .build();
        }
    }

    private static String mcpToolDescription(NemoMcpClient.ToolDescriptor descriptor) {
        var parts = new ArrayList<String>();
        parts.add("MCP tool " + descriptor.toolName() + " from server " + descriptor.serverName() + ".");
        if (!descriptor.title().isBlank()) {
            parts.add("Title: " + descriptor.title() + ".");
        }
        if (!descriptor.description().isBlank()) {
            parts.add(descriptor.description());
        }
        parts.add("External MCP tools can access resources outside Nemo's workspace sandbox and require approval unless the session is full-access.");
        return String.join(" ", parts);
    }

    private record PathInfo(java.nio.file.Path workspaceRoot) {
    }

}
