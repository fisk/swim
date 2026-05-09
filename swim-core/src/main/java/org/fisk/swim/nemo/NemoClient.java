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
import org.fisk.swim.ui.ChatPanelView;
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
    private Conversation _conversation;

    private NemoClient() {
    }

    public static NemoClient getInstance() {
        return _instance;
    }

    record ChatTurn(String speaker, String text) {
    }

    private static final class Conversation {
        private final BufferContext _context;
        private final Configuration _configuration;
        private final ChatPanelView _panelView;
        private final List<ChatTurn> _turns = new ArrayList<>();
        private boolean _pending;
        private long _requestSequence;
        private long _activeRequestId;
        private Thread _worker;

        private Conversation(BufferContext context, Configuration configuration, ChatPanelView panelView) {
            _context = context;
            _configuration = configuration;
            _panelView = panelView;
        }
    }

    public void run(BufferContext context, String question) {
        question = question.trim();
        Configuration configuration = resolveConfiguration(System.getenv());
        var conversation = ensureConversation(context, configuration);
        if (!question.equals("")) {
            submit(conversation, question);
        }
    }

    record Configuration(String apiKey, String model, URI responsesUri, Map<String, String> headers) {
    }

    static String buildInput(BufferContext context, String question) {
        return buildInput(context, List.of(new ChatTurn("me", question)));
    }

    static String buildInput(BufferContext context, List<ChatTurn> turns) {
        var buffer = context.getBuffer();
        var transcript = new StringBuilder();
        for (var turn : turns) {
            if (!transcript.isEmpty()) {
                transcript.append("\n\n");
            }
            transcript.append(turn.speaker()).append("> ").append(turn.text());
        }
        return String.join("\n",
                "You are Nemo, an AI assistant inside the SWIM text editor.",
                "Answer concisely and focus on the current file.",
                "",
                "Conversation:",
                transcript.toString(),
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

    private synchronized Conversation ensureConversation(BufferContext context, Configuration configuration) {
        var window = Window.getInstance();
        if (window == null) {
            throw new IllegalStateException("No active window");
        }
        if (_conversation != null && _conversation._panelView.getParent() != null
                && _conversation._context == context) {
            return _conversation;
        }

        if (window.isShowingPanel()) {
            window.hidePanel();
        }

        Conversation[] holder = new Conversation[1];
        var panelView = new ChatPanelView(org.fisk.swim.ui.Rect.create(0, 0, 0, 0), "Nemo",
                message -> submit(holder[0], message),
                command -> handleCommand(holder[0], command));
        var conversation = new Conversation(context, configuration, panelView);
        holder[0] = conversation;
        window.showPanel(panelView);
        _conversation = conversation;
        return conversation;
    }

    private synchronized void submit(Conversation conversation, String question) {
        question = question.trim();
        if (question.equals("") || conversation._pending) {
            return;
        }
        conversation._turns.add(new ChatTurn("me", question));
        conversation._panelView.appendMessage("me", question);

        if (conversation._configuration.apiKey().isBlank()) {
            String message = "Set OPENAI_API_KEY to use :nemo";
            conversation._turns.add(new ChatTurn("nemo", message));
            conversation._panelView.appendMessage("nemo", message);
            return;
        }

        conversation._pending = true;
        long requestId = ++conversation._requestSequence;
        conversation._activeRequestId = requestId;
        conversation._panelView.setPending(true);

        var worker = new Thread(() -> {
            try {
                String response = request(conversation._configuration, buildInput(conversation._context, conversation._turns));
                EventThread.getInstance().enqueue(new RunnableEvent(() -> handleResponse(conversation, requestId, response)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                _log.error("Nemo request failed", e);
                EventThread.getInstance().enqueue(new RunnableEvent(() -> handleFailure(conversation, requestId, "Nemo failed: " + e.getMessage())));
            }
        }, "swim-nemo");
        worker.setDaemon(true);
        conversation._worker = worker;
        worker.start();
    }

    private synchronized void handleCommand(Conversation conversation, String command) {
        String trimmed = command.trim();
        conversation._turns.add(new ChatTurn("me", trimmed));
        conversation._panelView.appendMessage("me", trimmed);

        switch (trimmed) {
        case ":abort":
            if (!conversation._pending || conversation._worker == null) {
                String response = "Nothing to abort.";
                conversation._turns.add(new ChatTurn("nemo", response));
                conversation._panelView.appendMessage("nemo", response);
                return;
            }
            conversation._pending = false;
            conversation._activeRequestId = 0;
            Thread worker = conversation._worker;
            conversation._worker = null;
            conversation._panelView.setPending(false);
            worker.interrupt();
            String response = "*aborted*";
            conversation._turns.add(new ChatTurn("nemo", response));
            conversation._panelView.appendMessage("nemo", response);
            return;
        case ":help":
            String help = "Available commands: :abort, :help, :q";
            conversation._turns.add(new ChatTurn("nemo", help));
            conversation._panelView.appendMessage("nemo", help);
            return;
        case ":q":
        case ":quit":
            var window = Window.getInstance();
            if (window != null) {
                window.hidePanel();
            }
            return;
        default:
            String unknown = "Unknown command: " + trimmed;
            conversation._turns.add(new ChatTurn("nemo", unknown));
            conversation._panelView.appendMessage("nemo", unknown);
        }
    }

    private synchronized void handleResponse(Conversation conversation, long requestId, String response) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        conversation._pending = false;
        conversation._activeRequestId = 0;
        conversation._worker = null;
        conversation._turns.add(new ChatTurn("nemo", response));
        conversation._panelView.appendMessage("nemo", response);
        conversation._panelView.setPending(false);
    }

    private synchronized void handleFailure(Conversation conversation, long requestId, String response) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        handleResponse(conversation, requestId, response);
    }

    private void showMessage(String message) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            if (Window.getInstance() != null) {
                Window.getInstance().getCommandView().setMessage(message);
            }
        }));
    }
}
