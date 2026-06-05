package org.fisk.swim.nemo;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;

final class NemoLangChain4jClient {
    NemoClient.ResponseResult request(NemoClient.Configuration configuration, BufferContext context,
            List<NemoClient.ChatTurn> turns) throws IOException, InterruptedException {
        PathInfo pathInfo = resolvePathInfo(configuration, context);
        List<NemoSkillDocument> skills = NemoSkillLoader.loadApplicableSkills(context, pathInfo.workspaceRoot(), configuration);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(NemoPromptBuilder.buildInput(context, turns, configuration, skills)));

        ChatModel chatModel = createModel(configuration);
        List<ToolSpecification> tools = buildToolSpecifications(configuration);
        TokenUsage cumulativeUsage = null;
        while (true) {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            cumulativeUsage = TokenUsage.sum(cumulativeUsage, response.tokenUsage());

            AiMessage aiMessage = response.aiMessage();
            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                for (ToolExecutionRequest toolCall : aiMessage.toolExecutionRequests()) {
                    String output = NemoClient.executeToolSafely(configuration, context, new NemoClient.ToolCall(
                            toolCall.id(),
                            toolCall.name(),
                            parseArguments(toolCall.arguments())));
                    messages.add(new ToolExecutionResultMessage(toolCall.id(), toolCall.name(), output));
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
            return new NemoClient.ResponseResult(text, contextUsagePercent(configuration, cumulativeUsage));
        }
    }

    private ChatModel createModel(NemoClient.Configuration configuration) {
        var builder = OpenAiChatModel.builder()
                .modelName(configuration.model())
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .maxRetries(configuration.maxRetries())
                .logRequests(configuration.logRequests())
                .logResponses(configuration.logResponses())
                .strictTools(configuration.strictTools())
                .parallelToolCalls(configuration.parallelToolCalls());
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

    private static List<ToolSpecification> buildToolSpecifications(NemoClient.Configuration configuration) {
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
        if (configuration.toolRunCommand()) {
            tools.add(tool("run_command",
                    "Run a shell command in the workspace and return exit code, stdout, and stderr.",
                    JsonObjectSchema.builder()
                            .addStringProperty("command", "Shell command to execute.")
                            .addStringProperty("cwd", "Optional working directory relative to the workspace root.")
                            .required(List.of("command"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolWriteFile()) {
            tools.add(tool("write_file",
                    "Create or overwrite a file in the workspace using full file contents.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Path relative to the workspace root.")
                            .addStringProperty("content", "Full file contents to write.")
                            .required(List.of("path", "content"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolApplyPatch()) {
            tools.add(tool("apply_patch",
                    "Apply a targeted unified diff patch inside the workspace.",
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
        if (configuration.toolGitAdd()) {
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
        if (configuration.toolGitCommit()) {
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

    private record PathInfo(java.nio.file.Path workspaceRoot) {
    }
}
