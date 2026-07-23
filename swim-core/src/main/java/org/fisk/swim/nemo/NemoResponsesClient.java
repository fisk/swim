package org.fisk.swim.nemo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.fisk.swim.text.BufferContext;
import org.fisk.swim.api.SwimHttpClients;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.output.TokenUsage;

final class NemoResponsesClient {
    private static final Gson GSON = new Gson();

    NemoClient.ResponseResult request(NemoClient.Configuration configuration, BufferContext context,
            List<NemoClient.ChatTurn> turns, NemoClient.ToolExecutionSession executionSession)
            throws IOException, InterruptedException {
        Path workspaceRoot = NemoClient.resolveWorkspaceRoot(configuration, context);
        List<NemoSkillDocument> skills = NemoSkillLoader.loadApplicableSkills(context, workspaceRoot, configuration);
        JsonArray input = new JsonArray();
        input.add(userMessage(NemoPromptBuilder.buildInput(context, turns, configuration, skills)));

        HttpClient httpClient = SwimHttpClients.newBuilder()
                .connectTimeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .build();
        JsonArray tools = tools(configuration);
        TokenUsage cumulativeUsage = null;
        var toolTraces = new ArrayList<NemoClient.ToolTrace>();
        int attempts = Math.max(1, configuration.maxRetries() + 1);
        int failedAttempts = 0;
        while (true) {
            List<NemoClient.ChatTurn> queuedTurns = NemoClient.drainQueuedTurns(executionSession);
            if (!queuedTurns.isEmpty()) {
                input.add(userMessage(NemoClient.queuedWorkerMessage(queuedTurns)));
            }

            JsonObject response;
            try {
                response = send(configuration, httpClient, input, tools);
                failedAttempts = 0;
            } catch (IOException e) {
                failedAttempts++;
                if (failedAttempts >= attempts) {
                    throw e;
                }
                continue;
            }
            cumulativeUsage = TokenUsage.sum(cumulativeUsage, tokenUsage(response));

            ResponseParts parts = responseParts(response);
            if (!parts.toolCalls().isEmpty()) {
                for (JsonObject rawToolCall : parts.rawToolCalls()) {
                    input.add(rawToolCall);
                }
                for (NemoClient.ToolCall toolCall : parts.toolCalls()) {
                    NemoClient.ToolProgress progress = NemoClient.reportToolStart(executionSession, toolCall);
                    NemoClient.ToolExecutionResult result = NemoClient.executeToolDetailedSafely(
                            configuration, context, toolCall, executionSession);
                    NemoClient.reportOrCollectToolCompletion(executionSession, toolTraces, progress,
                            NemoClient.toolTrace(toolCall, result));
                    input.add(functionCallOutput(toolCall.callId(), result.output()));
                }
                continue;
            }

            String text = parts.text().trim();
            if (text.isBlank()) {
                text = "Nemo returned no text.";
            }
            return new NemoClient.ResponseResult(text, contextUsagePercent(configuration, cumulativeUsage),
                    List.copyOf(toolTraces));
        }
    }

    private static JsonObject send(NemoClient.Configuration configuration, HttpClient httpClient, JsonArray input,
            JsonArray tools) throws IOException, InterruptedException {
        JsonObject requestBody = requestBody(configuration, input, tools);
        HttpRequest.Builder request = HttpRequest.newBuilder(responsesUri(configuration))
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .header("Content-Type", "application/json");
        if (usesStreamingResponses(configuration) && !hasHeader(configuration, "Accept")) {
            request.header("Accept", "text/event-stream");
        }
        if (!configuration.apiKey().isBlank() && !hasHeader(configuration, "Authorization")) {
            request.header("Authorization", "Bearer " + configuration.apiKey());
        }
        if (!configuration.organization().isBlank() && !hasHeader(configuration, "OpenAI-Organization")) {
            request.header("OpenAI-Organization", configuration.organization());
        }
        if (!configuration.project().isBlank() && !hasHeader(configuration, "OpenAI-Project")) {
            request.header("OpenAI-Project", configuration.project());
        }
        for (Map.Entry<String, String> header : configuration.headers().entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(header.getKey())) {
                request.header(header.getKey(), header.getValue());
            }
        }
        String accountId = chatGptAccountId(configuration);
        if (!accountId.isBlank()) {
            request.header("ChatGPT-Account-Id", accountId);
        }

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(NemoClient.compactRawBody(response.body()));
        }
        return parseResponseBody(response.body());
    }

    private static JsonObject requestBody(NemoClient.Configuration configuration, JsonArray input, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", configuration.model());
        if (!configuration.systemPrompt().isBlank()) {
            body.addProperty("instructions", configuration.systemPrompt());
        }
        body.add("input", input);
        if (tools.size() > 0) {
            body.add("tools", tools);
        }
        if (configuration.maxOutputTokens() != null) {
            body.addProperty("max_output_tokens", configuration.maxOutputTokens());
        }
        if (configuration.temperature() != null) {
            body.addProperty("temperature", configuration.temperature());
        }
        if (configuration.topP() != null) {
            body.addProperty("top_p", configuration.topP());
        }
        if (configuration.parallelToolCalls()) {
            body.addProperty("parallel_tool_calls", true);
        }
        if (!configuration.reasoningEffort().isBlank()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", configuration.reasoningEffort());
            body.add("reasoning", reasoning);
        }
        for (Map.Entry<String, Object> parameter : configuration.customParameters().entrySet()) {
            body.add(parameter.getKey(), GSON.toJsonTree(parameter.getValue()));
        }
        if (usesStreamingResponses(configuration)) {
            body.addProperty("stream", true);
            body.addProperty("store", false);
        }
        return body;
    }

    private static boolean usesStreamingResponses(NemoClient.Configuration configuration) {
        return "chatgpt".equals(configuration.provider());
    }

    private static JsonObject parseResponseBody(String body) throws IOException {
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("event:") || trimmed.startsWith("data:")) {
            return parseEventStream(body);
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static JsonObject parseEventStream(String body) throws IOException {
        var output = new JsonArray();
        var text = new StringBuilder();
        JsonObject completedResponse = null;
        JsonObject usage = null;
        String eventName = "";
        var data = new StringBuilder();

        for (String line : body.split("\\R", -1)) {
            if (line.isEmpty()) {
                StreamEventResult result = consumeStreamEvent(eventName, data.toString(), output, text);
                if (result.response() != null) {
                    completedResponse = result.response();
                }
                if (result.usage() != null) {
                    usage = result.usage();
                }
                eventName = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = lineValue(line, "event:");
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(lineValue(line, "data:"));
            }
        }

        StreamEventResult result = consumeStreamEvent(eventName, data.toString(), output, text);
        if (result.response() != null) {
            completedResponse = result.response();
        }
        if (result.usage() != null) {
            usage = result.usage();
        }
        if (completedResponse != null) {
            return responseWithAccumulatedOutput(completedResponse, output, text, usage);
        }

        JsonObject response = responseWithAccumulatedOutput(new JsonObject(), output, text, usage);
        if (usage != null) {
            response.add("usage", usage);
        }
        return response;
    }

    private static JsonObject responseWithAccumulatedOutput(JsonObject response, JsonArray output, StringBuilder text,
            JsonObject usage) {
        JsonObject result = response.deepCopy();
        if (!hasNonEmptyArray(result, "output")) {
            JsonArray mergedOutput = new JsonArray();
            for (JsonElement item : output) {
                mergedOutput.add(item.deepCopy());
            }
            if (mergedOutput.isEmpty() && !text.isEmpty()) {
                mergedOutput.add(messageFromText(text.toString()));
            }
            if (!mergedOutput.isEmpty()) {
                result.add("output", mergedOutput);
            }
        }
        if (usage != null && (!result.has("usage") || result.get("usage").isJsonNull())) {
            result.add("usage", usage.deepCopy());
        }
        return result;
    }

    private static JsonObject messageFromText(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        JsonArray content = new JsonArray();
        JsonObject outputText = new JsonObject();
        outputText.addProperty("type", "output_text");
        outputText.addProperty("text", text);
        content.add(outputText);
        message.add("content", content);
        return message;
    }

    private static boolean hasNonEmptyArray(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() && !object.getAsJsonArray(name).isEmpty();
    }

    private static StreamEventResult consumeStreamEvent(String eventName, String data, JsonArray output,
            StringBuilder text) throws IOException {
        if (data.isBlank() || "[DONE]".equals(data.trim())) {
            return new StreamEventResult(null, null);
        }
        JsonObject payload = JsonParser.parseString(data).getAsJsonObject();
        if (hasNonNull(payload, "error")) {
            throw new IOException(NemoClient.compactRawBody(payload.get("error").toString()));
        }
        String type = firstNonBlank(eventName, stringMember(payload, "type"));
        if ("response.failed".equals(type) || "response.incomplete".equals(type)) {
            throw new IOException(NemoClient.compactRawBody(payload.toString()));
        }
        JsonObject response = objectMember(payload, "response");
        JsonObject usage = objectMember(payload, "usage");
        if (usage == null && response != null) {
            usage = objectMember(response, "usage");
        }
        if ("response.completed".equals(type) && response != null) {
            return new StreamEventResult(response, usage);
        }
        if (payload.has("output") && payload.get("output").isJsonArray()) {
            return new StreamEventResult(payload, usage);
        }
        if ("response.output_text.delta".equals(type)) {
            text.append(firstNonBlank(stringMember(payload, "delta"), stringMember(payload, "text")));
        } else if ("response.output_text.done".equals(type) && text.isEmpty()) {
            text.append(firstNonBlank(stringMember(payload, "text"), stringMember(payload, "delta")));
        } else if ("response.output_item.done".equals(type)) {
            JsonObject item = objectMember(payload, "item");
            if (item != null) {
                output.add(item);
            }
        }
        return new StreamEventResult(null, usage);
    }

    private static String lineValue(String line, String prefix) {
        String value = line.substring(prefix.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static URI responsesUri(NemoClient.Configuration configuration) {
        String base = configuration.baseUrl();
        String endpoint = base.endsWith("/responses") ? base : stripTrailingSlash(base) + "/responses";
        if (!configuration.queryParameters().isEmpty()) {
            endpoint += "?" + encodedQuery(configuration.queryParameters());
        }
        return URI.create(endpoint);
    }

    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encodedQuery(Map<String, String> queryParameters) {
        var parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : queryParameters.entrySet()) {
            parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private static JsonObject userMessage(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject inputText = new JsonObject();
        inputText.addProperty("type", "input_text");
        inputText.addProperty("text", text);
        content.add(inputText);
        message.add("content", content);
        return message;
    }

    private static JsonObject functionCallOutput(String callId, String output) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", callId);
        item.addProperty("output", output == null ? "" : output);
        return item;
    }

    private static JsonArray tools(NemoClient.Configuration configuration) {
        JsonArray tools = new JsonArray();
        for (ToolSpecification specification : NemoLangChain4jClient.buildToolSpecifications(configuration)) {
            JsonObject source = JsonParser.parseString(specification.toJson()).getAsJsonObject();
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", stringMember(source, "name"));
            tool.addProperty("description", stringMember(source, "description"));
            tool.add("parameters", source.has("parameters") ? source.get("parameters") : new JsonObject());
            if (configuration.strictTools() || Boolean.TRUE.equals(specification.strict())) {
                tool.addProperty("strict", true);
            }
            tools.add(tool);
        }
        return tools;
    }

    private static ResponseParts responseParts(JsonObject response) throws IOException {
        if (hasNonNull(response, "error")) {
            throw new IOException(NemoClient.compactRawBody(response.get("error").toString()));
        }
        var text = new StringBuilder();
        var toolCalls = new ArrayList<NemoClient.ToolCall>();
        var rawToolCalls = new ArrayList<JsonObject>();
        if (response.has("output") && response.get("output").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("output")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String type = stringMember(item, "type");
                if ("function_call".equals(type)) {
                    JsonObject raw = item.deepCopy();
                    rawToolCalls.add(raw);
                    toolCalls.add(new NemoClient.ToolCall(
                            firstNonBlank(stringMember(item, "call_id"), stringMember(item, "id")),
                            stringMember(item, "name"),
                            parseArguments(item.get("arguments"))));
                } else {
                    appendText(text, item);
                }
            }
        }
        if (text.isEmpty() && response.has("output_text") && response.get("output_text").isJsonPrimitive()) {
            text.append(response.get("output_text").getAsString());
        }
        return new ResponseParts(text.toString(), List.copyOf(toolCalls), List.copyOf(rawToolCalls));
    }

    private static void appendText(StringBuilder text, JsonObject item) {
        if (item.has("content") && item.get("content").isJsonArray()) {
            for (JsonElement contentElement : item.getAsJsonArray("content")) {
                if (!contentElement.isJsonObject()) {
                    continue;
                }
                JsonObject content = contentElement.getAsJsonObject();
                String type = stringMember(content, "type");
                if ("output_text".equals(type) || "text".equals(type)) {
                    appendTextPart(text, stringMember(content, "text"));
                }
            }
        }
        appendTextPart(text, stringMember(item, "text"));
    }

    private static void appendTextPart(StringBuilder text, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append("\n");
        }
        text.append(part);
    }

    private static JsonObject parseArguments(JsonElement arguments) {
        if (arguments == null || arguments.isJsonNull()) {
            return new JsonObject();
        }
        if (arguments.isJsonObject()) {
            return arguments.getAsJsonObject();
        }
        if (arguments.isJsonPrimitive()) {
            String raw = arguments.getAsString();
            if (!raw.isBlank()) {
                return JsonParser.parseString(raw).getAsJsonObject();
            }
        }
        return new JsonObject();
    }

    private static TokenUsage tokenUsage(JsonObject response) {
        if (!response.has("usage") || !response.get("usage").isJsonObject()) {
            return null;
        }
        JsonObject usage = response.getAsJsonObject("usage");
        Integer inputTokens = integerMember(usage, "input_tokens", "prompt_tokens");
        Integer outputTokens = integerMember(usage, "output_tokens", "completion_tokens");
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        return new TokenUsage(inputTokens, outputTokens);
    }

    private static Integer contextUsagePercent(NemoClient.Configuration configuration, TokenUsage usage) {
        Integer contextWindowTokens = configuration.contextWindowTokens();
        if (usage == null || usage.inputTokenCount() == null || contextWindowTokens == null || contextWindowTokens <= 0) {
            return null;
        }
        return (int) Math.round((usage.inputTokenCount() * 100.0) / contextWindowTokens);
    }

    private static Integer integerMember(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                return object.get(name).getAsInt();
            }
        }
        return null;
    }

    private static String stringMember(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            return "";
        }
        return object.get(name).getAsString();
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(name);
    }

    private static boolean hasNonNull(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String chatGptAccountId(NemoClient.Configuration configuration) {
        if (!"chatgpt".equals(configuration.provider()) || hasHeader(configuration, "ChatGPT-Account-Id")) {
            return "";
        }
        Path authPath = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
        if (!Files.isRegularFile(authPath)) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(authPath)).getAsJsonObject();
            JsonObject tokens = root.has("tokens") && root.get("tokens").isJsonObject()
                    ? root.getAsJsonObject("tokens")
                    : null;
            return stringMember(tokens, "account_id");
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hasHeader(NemoClient.Configuration configuration, String name) {
        for (String key : configuration.headers().keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private record ResponseParts(String text, List<NemoClient.ToolCall> toolCalls, List<JsonObject> rawToolCalls) {
    }

    private record StreamEventResult(JsonObject response, JsonObject usage) {
    }
}
