package org.fisk.swim.nemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.text.BufferContext;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private static final Gson GSON = new Gson();
    private final HttpClient _httpClient = HttpClient.newHttpClient();
    
    private record CompletionResponse(JsonObject message, Integer promptTokens) {
    }

    private static final class ToolCallAccumulator {
        private String _id;
        private String _type = "function";
        private final StringBuilder _name = new StringBuilder();
        private final StringBuilder _arguments = new StringBuilder();
    }

    NemoClient.ResponseResult request(NemoClient.Configuration configuration, BufferContext context,
            List<NemoClient.ChatTurn> turns, NemoClient.ToolExecutionSession executionSession) throws IOException, InterruptedException {
        if (isOpenAiCompatible(configuration)) {
            return requestOpenAiCompatible(configuration, context, turns, executionSession);
        }
        PathInfo pathInfo = resolvePathInfo(configuration, context);
        List<NemoSkillDocument> skills = NemoSkillLoader.loadApplicableSkills(context, pathInfo.workspaceRoot(), configuration);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(NemoPromptBuilder.buildInput(context, turns, configuration, skills)));

        ChatModel chatModel = createModel(configuration);
        List<ToolSpecification> tools = buildToolSpecifications(configuration);
        TokenUsage cumulativeUsage = null;
        var toolTraces = new ArrayList<NemoClient.ToolTrace>();
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
                    var call = new NemoClient.ToolCall(
                            toolCall.id(),
                            toolCall.name(),
                            parseArguments(toolCall.arguments()));
                    String output = NemoClient.executeToolSafely(configuration, context, call, executionSession);
                    toolTraces.add(NemoClient.toolTrace(call, output));
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
            return new NemoClient.ResponseResult(text, contextUsagePercent(configuration, cumulativeUsage),
                    List.copyOf(toolTraces));
        }
    }

    private NemoClient.ResponseResult requestOpenAiCompatible(
            NemoClient.Configuration configuration,
            BufferContext context,
            List<NemoClient.ChatTurn> turns,
            NemoClient.ToolExecutionSession executionSession) throws IOException, InterruptedException {
        PathInfo pathInfo = resolvePathInfo(configuration, context);
        List<NemoSkillDocument> skills = NemoSkillLoader.loadApplicableSkills(context, pathInfo.workspaceRoot(), configuration);
        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMessage(NemoPromptBuilder.buildInput(context, turns, configuration, skills)));
        List<ToolSpecification> tools = buildToolSpecifications(configuration);
        Integer promptTokens = null;
        var toolTraces = new ArrayList<NemoClient.ToolTrace>();
        while (true) {
            CompletionResponse response = invokeChatCompletions(configuration, messages, tools);
            promptTokens = response.promptTokens() != null ? response.promptTokens() : promptTokens;
            JsonObject message = response.message();
            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                messages.add(message.deepCopy());
                for (JsonElement element : toolCalls) {
                    JsonObject toolCall = element.getAsJsonObject();
                    JsonObject function = toolCall.getAsJsonObject("function");
                    var call = new NemoClient.ToolCall(
                            toolCall.get("id").getAsString(),
                            function.get("name").getAsString(),
                            parseArguments(function.get("arguments").getAsString()));
                    String output = NemoClient.executeToolSafely(configuration, context, call, executionSession);
                    toolTraces.add(NemoClient.toolTrace(call, output));
                    messages.add(toolResultMessage(
                            toolCall.get("id").getAsString(),
                            function.get("name").getAsString(),
                            output));
                }
                continue;
            }

            String text = extractContent(message);
            if (text == null || text.isBlank()) {
                text = "Nemo returned no text.";
            }
            return new NemoClient.ResponseResult(text, contextUsagePercent(configuration, promptTokens),
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

    private static Integer contextUsagePercent(NemoClient.Configuration configuration, Integer promptTokens) {
        Integer contextWindowTokens = configuration.contextWindowTokens();
        if (promptTokens == null || contextWindowTokens == null || contextWindowTokens <= 0) {
            return null;
        }
        return (int) Math.round((promptTokens * 100.0) / contextWindowTokens);
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
                    "Create or overwrite a file in the workspace using full file contents.",
                    JsonObjectSchema.builder()
                            .addStringProperty("path", "Path relative to the workspace root.")
                            .addStringProperty("content", "Full file contents to write.")
                            .required(List.of("path", "content"))
                            .additionalProperties(false)
                            .build()));
        }
        if (configuration.toolApplyPatch() && NemoClient.isToolAllowedByPermission(configuration, "apply_patch")) {
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

    private record PathInfo(java.nio.file.Path workspaceRoot) {
    }

    private boolean isOpenAiCompatible(NemoClient.Configuration configuration) {
        String provider = configuration.provider();
        return "openai".equals(provider) || "openai-compatible".equals(provider);
    }

    private CompletionResponse invokeChatCompletions(
            NemoClient.Configuration configuration,
            List<JsonObject> messages,
            List<ToolSpecification> tools) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("model", configuration.model());
        JsonArray messageArray = new JsonArray();
        for (JsonObject message : messages) {
            messageArray.add(message.deepCopy());
        }
        request.add("messages", messageArray);
        if (!tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (ToolSpecification tool : tools) {
                toolArray.add(toOpenAiTool(tool));
            }
            request.add("tools", toolArray);
        }
        if (configuration.temperature() != null) {
            request.addProperty("temperature", configuration.temperature());
        }
        if (configuration.topP() != null) {
            request.addProperty("top_p", configuration.topP());
        }
        if (configuration.maxOutputTokens() != null) {
            request.addProperty("max_tokens", configuration.maxOutputTokens());
        }
        for (var entry : configuration.customParameters().entrySet()) {
            request.add(entry.getKey(), JsonParser.parseString(GSON.toJson(entry.getValue())));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(buildChatCompletionsUri(configuration))
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .header("Content-Type", "application/json");
        for (var entry : effectiveHeaders(configuration).entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        HttpResponse<String> response = _httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw errorFromBody(response.body());
        }
        return parseCompletionResponse(response.body());
    }

    private static URI buildChatCompletionsUri(NemoClient.Configuration configuration) {
        String base = configuration.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder builder = new StringBuilder(base).append("/chat/completions");
        if (!configuration.queryParameters().isEmpty()) {
            builder.append('?');
            boolean first = true;
            for (var entry : configuration.queryParameters().entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(builder.toString());
    }

    private static Map<String, String> effectiveHeaders(NemoClient.Configuration configuration) {
        var headers = new LinkedHashMap<String, String>();
        if (!configuration.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + configuration.apiKey());
        }
        if (!configuration.organization().isBlank()) {
            headers.put("OpenAI-Organization", configuration.organization());
        }
        if (!configuration.project().isBlank()) {
            headers.put("OpenAI-Project", configuration.project());
        }
        headers.putAll(customHeaders(configuration));
        return headers;
    }

    private static JsonObject userMessage(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", text);
        return message;
    }

    private static JsonObject toolResultMessage(String toolCallId, String name, String output) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "tool");
        message.addProperty("tool_call_id", toolCallId);
        message.addProperty("name", name);
        message.addProperty("content", output);
        return message;
    }

    private static JsonObject toOpenAiTool(ToolSpecification tool) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", tool.name());
        if (tool.description() != null && !tool.description().isBlank()) {
            function.addProperty("description", tool.description());
        }
        function.add("parameters", toJsonSchema(tool.parameters()));
        wrapper.add("function", function);
        return wrapper;
    }

    private static CompletionResponse parseCompletionResponse(String body) throws IOException {
        String trimmed = body == null ? "" : body.stripLeading();
        if (trimmed.startsWith("data:")) {
            return parseSseCompletionResponse(body);
        }
        JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
        if (root.has("error")) {
            throw errorFromBody(root.get("error").toString());
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        return new CompletionResponse(message == null ? assistantMessage("", null) : message.deepCopy(), extractPromptTokens(root, null));
    }

    private static CompletionResponse parseSseCompletionResponse(String body) throws IOException {
        StringBuilder content = new StringBuilder();
        LinkedHashMap<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        Integer promptTokens = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
                promptTokens = extractPromptTokens(chunk, promptTokens);
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices == null) {
                    continue;
                }
                for (JsonElement choiceElement : choices) {
                    JsonObject choice = choiceElement.getAsJsonObject();
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    appendContent(content, delta.get("content"));
                    appendToolCalls(toolCalls, delta.getAsJsonArray("tool_calls"));
                }
            }
        }
        return new CompletionResponse(assistantMessage(content.toString(), toolCalls), promptTokens);
    }

    private static void appendContent(StringBuilder content, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            content.append(element.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject object = item.getAsJsonObject();
                JsonElement text = object.get("text");
                if (text != null && !text.isJsonNull()) {
                    content.append(text.getAsString());
                }
            }
        }
    }

    private static void appendToolCalls(LinkedHashMap<Integer, ToolCallAccumulator> toolCalls, JsonArray deltas) {
        if (deltas == null) {
            return;
        }
        for (JsonElement deltaElement : deltas) {
            JsonObject delta = deltaElement.getAsJsonObject();
            int index = delta.has("index") ? delta.get("index").getAsInt() : toolCalls.size();
            ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
            JsonElement id = delta.get("id");
            if (id != null && !id.isJsonNull()) {
                accumulator._id = id.getAsString();
            }
            JsonElement type = delta.get("type");
            if (type != null && !type.isJsonNull()) {
                accumulator._type = type.getAsString();
            }
            JsonObject function = delta.getAsJsonObject("function");
            if (function == null) {
                continue;
            }
            JsonElement name = function.get("name");
            if (name != null && !name.isJsonNull()) {
                accumulator._name.append(name.getAsString());
            }
            JsonElement arguments = function.get("arguments");
            if (arguments != null && !arguments.isJsonNull()) {
                accumulator._arguments.append(arguments.getAsString());
            }
        }
    }

    private static JsonObject assistantMessage(String content, LinkedHashMap<Integer, ToolCallAccumulator> toolCalls) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.add("content", com.google.gson.JsonNull.INSTANCE);
            JsonArray toolCallArray = new JsonArray();
            for (ToolCallAccumulator accumulator : toolCalls.values()) {
                JsonObject toolCall = new JsonObject();
                toolCall.addProperty("id", accumulator._id == null || accumulator._id.isBlank()
                        ? "call_" + toolCallArray.size()
                        : accumulator._id);
                toolCall.addProperty("type", accumulator._type == null || accumulator._type.isBlank()
                        ? "function"
                        : accumulator._type);
                JsonObject function = new JsonObject();
                function.addProperty("name", accumulator._name.toString());
                function.addProperty("arguments", accumulator._arguments.toString());
                toolCall.add("function", function);
                toolCallArray.add(toolCall);
            }
            message.add("tool_calls", toolCallArray);
        } else {
            message.addProperty("content", content == null ? "" : content);
        }
        return message;
    }

    private static IOException errorFromBody(String body) {
        String message = body;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error")) {
                message = root.get("error").toString();
            } else {
                message = root.toString();
            }
        } catch (RuntimeException ignored) {
        }
        return new IOException(message);
    }

    private static String extractContent(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        StringBuilder builder = new StringBuilder();
        appendContent(builder, content);
        return builder.toString();
    }

    private static JsonObject toJsonSchema(JsonObjectSchema schema) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "object");
        if (schema.description() != null && !schema.description().isBlank()) {
            object.addProperty("description", schema.description());
        }
        JsonObject properties = new JsonObject();
        for (var entry : schema.properties().entrySet()) {
            properties.add(entry.getKey(), toJsonSchema(entry.getValue()));
        }
        object.add("properties", properties);
        if (!schema.required().isEmpty()) {
            JsonArray required = new JsonArray();
            for (String name : schema.required()) {
                required.add(name);
            }
            object.add("required", required);
        }
        if (schema.additionalProperties() != null) {
            object.addProperty("additionalProperties", schema.additionalProperties());
        }
        return object;
    }

    private static JsonObject toJsonSchema(dev.langchain4j.model.chat.request.json.JsonSchemaElement element) {
        JsonObject object = new JsonObject();
        if (element instanceof JsonObjectSchema objectSchema) {
            return toJsonSchema(objectSchema);
        }
        if (element instanceof JsonStringSchema stringSchema) {
            object.addProperty("type", "string");
            if (stringSchema.description() != null && !stringSchema.description().isBlank()) {
                object.addProperty("description", stringSchema.description());
            }
            return object;
        }
        if (element instanceof JsonIntegerSchema integerSchema) {
            object.addProperty("type", "integer");
            if (integerSchema.description() != null && !integerSchema.description().isBlank()) {
                object.addProperty("description", integerSchema.description());
            }
            return object;
        }
        if (element instanceof JsonArraySchema arraySchema) {
            object.addProperty("type", "array");
            if (arraySchema.description() != null && !arraySchema.description().isBlank()) {
                object.addProperty("description", arraySchema.description());
            }
            if (arraySchema.items() != null) {
                object.add("items", toJsonSchema(arraySchema.items()));
            }
            return object;
        }
        return object;
    }

    private static Integer extractPromptTokens(JsonObject response, Integer current) {
        JsonObject usage = response.getAsJsonObject("usage");
        if (usage == null || !usage.has("prompt_tokens")) {
            return current;
        }
        return usage.get("prompt_tokens").getAsInt();
    }
}
