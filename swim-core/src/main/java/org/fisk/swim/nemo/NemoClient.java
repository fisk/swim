package org.fisk.swim.nemo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.ListView.ListItem;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class NemoClient {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();
    private static final String _defaultModel = "gpt-4.1";
    private static final String _defaultBaseUrl = "https://api.openai.com/v1";
    private static final NemoClient _instance = new NemoClient();

    private final HttpClient _httpClient = HttpClient.newHttpClient();

    private NemoClient() {
    }

    public static NemoClient getInstance() {
        return _instance;
    }

    public void run(BufferContext context, String question) {
        question = question.trim();
        if (question.equals("")) {
            showMessage("Usage: :nemo <question>");
            return;
        }

        Configuration configuration = resolveConfiguration(System.getenv());
        if (configuration.apiKey().isBlank()) {
            showMessage("Set OPENAI_API_KEY to use :nemo");
            return;
        }

        String finalQuestion = question;
        Configuration finalConfiguration = configuration;
        showMessage("Nemo is thinking...");
        var worker = new Thread(() -> {
            try {
                String response = request(finalConfiguration, buildInput(context, finalQuestion));
                showResult("Nemo", response);
            } catch (Exception e) {
                _log.error("Nemo request failed", e);
                showMessage("Nemo failed: " + e.getMessage());
            }
        }, "swim-nemo");
        worker.setDaemon(true);
        worker.start();
    }

    record Configuration(String apiKey, String model, URI responsesUri, Map<String, String> headers) {
    }

    static String buildInput(BufferContext context, String question) {
        var buffer = context.getBuffer();
        return String.join("\n",
                "You are Nemo, an AI assistant inside the SWIM text editor.",
                "Answer concisely and focus on the current file.",
                "",
                "User request:",
                question,
                "",
                "Current file:",
                buffer.getPath().toString(),
                "",
                "File contents:",
                buffer.getString());
    }

    static Configuration resolveConfiguration(Map<String, String> env) {
        String apiKey = env.getOrDefault("OPENAI_API_KEY", "").trim();
        String model = env.getOrDefault("OPENAI_MODEL", "").trim();
        if (model.equals("")) {
            model = _defaultModel;
        }

        URI responsesUri = buildResponsesUri(
                env.getOrDefault("OPENAI_RESPONSES_URL", "").trim(),
                env.getOrDefault("OPENAI_BASE_URL", "").trim());

        var headers = new LinkedHashMap<String, String>();
        if (!apiKey.equals("")) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", "application/json");

        String organization = env.getOrDefault("OPENAI_ORGANIZATION", "").trim();
        if (organization.equals("")) {
            organization = env.getOrDefault("OPENAI_ORG", "").trim();
        }
        if (!organization.equals("")) {
            headers.put("OpenAI-Organization", organization);
        }

        String project = env.getOrDefault("OPENAI_PROJECT", "").trim();
        if (!project.equals("")) {
            headers.put("OpenAI-Project", project);
        }

        for (var entry : env.entrySet()) {
            if (!entry.getKey().startsWith("OPENAI_HEADER_")) {
                continue;
            }
            String value = entry.getValue().trim();
            if (value.equals("")) {
                continue;
            }
            String name = headerName(entry.getKey().substring("OPENAI_HEADER_".length()));
            headers.put(name, value);
        }

        return new Configuration(apiKey, model, responsesUri, headers);
    }

    static URI buildResponsesUri(String explicitResponsesUrl, String baseUrl) {
        if (!explicitResponsesUrl.equals("")) {
            return URI.create(explicitResponsesUrl);
        }
        if (baseUrl.equals("")) {
            baseUrl = _defaultBaseUrl;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/responses")) {
            return URI.create(baseUrl);
        }
        return URI.create(baseUrl + "/responses");
    }

    static String headerName(String rawName) {
        return rawName.toLowerCase().replace('_', '-');
    }

    private String request(Configuration configuration, String input) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", configuration.model());
        payload.addProperty("instructions", "You are Nemo, a concise coding assistant inside the SWIM text editor.");
        payload.addProperty("input", input);

        HttpRequest.Builder builder = HttpRequest.newBuilder(configuration.responsesUri())
                .POST(HttpRequest.BodyPublishers.ofString(_gson.toJson(payload), StandardCharsets.UTF_8));
        for (var header : configuration.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException(extractErrorMessage(response.body(), response.statusCode()));
        }
        return extractOutputText(response.body());
    }

    static String extractOutputText(String responseBody) {
        JsonObject root = parseJsonObject(responseBody);
        JsonArray output = root.getAsJsonArray("output");
        if (output == null) {
            return "Nemo returned no output.";
        }
        StringBuilder builder = new StringBuilder();
        for (var item : output) {
            JsonObject itemObject = item.getAsJsonObject();
            JsonArray content = itemObject.getAsJsonArray("content");
            if (content == null) {
                continue;
            }
            for (var contentItem : content) {
                JsonObject contentObject = contentItem.getAsJsonObject();
                if (!"output_text".equals(contentObject.get("type").getAsString())) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append("\n");
                }
                builder.append(contentObject.get("text").getAsString());
            }
        }
        if (builder.isEmpty()) {
            return "Nemo returned no text.";
        }
        return builder.toString();
    }

    private static String extractErrorMessage(String responseBody, int statusCode) {
        try {
            JsonObject root = parseJsonObject(responseBody);
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return "HTTP " + statusCode + ": " + error.get("message").getAsString();
            }
        } catch (RuntimeException e) {
        }
        return "HTTP " + statusCode + ": " + compactRawBody(responseBody);
    }

    private static JsonObject parseJsonObject(String body) {
        String trimmed = body == null ? "" : body.trim();
        int objectStart = trimmed.indexOf('{');
        if (objectStart > 0) {
            trimmed = trimmed.substring(objectStart);
        }
        return JsonParser.parseString(trimmed).getAsJsonObject();
    }

    static String compactRawBody(String body) {
        if (body == null) {
            return "";
        }
        body = body.replaceAll("\\s+", " ").trim();
        if (body.length() > 200) {
            return body.substring(0, 200) + "...";
        }
        return body;
    }

    static List<String> wrapText(String text, int width) {
        var lines = new ArrayList<String>();
        for (String rawLine : text.split("\\R", -1)) {
            if (rawLine.length() <= width) {
                lines.add(rawLine);
                continue;
            }
            String line = rawLine;
            while (line.length() > width) {
                int split = line.lastIndexOf(' ', width);
                if (split <= 0) {
                    split = width;
                }
                lines.add(line.substring(0, split));
                line = line.substring(Math.min(split + 1, line.length()));
            }
            lines.add(line);
        }
        return lines;
    }

    private void showMessage(String message) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            if (Window.getInstance() != null) {
                Window.getInstance().getCommandView().setMessage(message);
            }
        }));
    }

    private void showResult(String title, String text) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            var window = Window.getInstance();
            if (window == null) {
                return;
            }
            if (window.isShowingList()) {
                window.hideList();
            }
            window.showList(toListItems(text), title);
        }));
    }

    private static List<ListItem> toListItems(String text) {
        var items = new ArrayList<ListItem>();
        for (var line : wrapText(text, 72)) {
            items.add(new TextItem(line));
        }
        return items;
    }

    private static class TextItem extends ListItem {
        private final String _text;

        private TextItem(String text) {
            _text = text;
        }

        @Override
        public void onClick() {
        }

        @Override
        public String displayString() {
            return _text;
        }
    }
}
