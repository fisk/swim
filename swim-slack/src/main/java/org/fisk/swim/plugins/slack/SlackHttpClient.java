package org.fisk.swim.plugins.slack;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.fisk.swim.api.SwimHttpClients;

import org.fisk.swim.slack.SlackChannelSummary;
import org.fisk.swim.slack.SlackClient;
import org.fisk.swim.slack.SlackDraft;
import org.fisk.swim.slack.SlackMessageEntry;
import org.fisk.swim.slack.SlackMessagePage;
import org.fisk.swim.slack.SlackMessageSummary;
import org.fisk.swim.slack.SlackSendResult;
import org.fisk.swim.slack.SlackSnapshot;
import org.fisk.swim.slack.SlackThreadDetail;
import org.fisk.swim.slack.SlackWorkspaceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class SlackHttpClient implements SlackClient {
    interface SlackTransport {
        JsonObject invoke(String token, String method, String httpMethod, Map<String, String> query, JsonObject jsonBody)
                throws IOException, InterruptedException;
    }

    private static final class DefaultSlackTransport implements SlackTransport {
        private final HttpClient _httpClient;

        private DefaultSlackTransport(HttpClient httpClient) {
            _httpClient = httpClient;
        }

        @Override
        public JsonObject invoke(String token, String method, String httpMethod, Map<String, String> query, JsonObject jsonBody)
                throws IOException, InterruptedException {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest request = buildRequest(token, method, httpMethod, query, jsonBody);
                HttpResponse<String> response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 && attempt < 2) {
                    long retrySeconds = parseRetryAfter(response);
                    Thread.sleep(Math.max(1L, retrySeconds) * 1000L);
                    continue;
                }
                JsonObject payload = response.body() == null || response.body().isBlank()
                        ? new JsonObject()
                        : new Gson().fromJson(response.body(), JsonObject.class);
                if (response.statusCode() >= 400) {
                    String error = payload != null && payload.has("error") ? payload.get("error").getAsString()
                            : "HTTP " + response.statusCode();
                    throw new IOException(method + " failed: " + error);
                }
                return payload == null ? new JsonObject() : payload;
            }
            throw new IOException(method + " failed after retries");
        }

        private static HttpRequest buildRequest(
                String token,
                String method,
                String httpMethod,
                Map<String, String> query,
                JsonObject jsonBody) {
            URI uri = URI.create("https://slack.com/api/" + method + encodeQuery(query));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if ("POST".equals(httpMethod)) {
                builder.header("Content-Type", "application/json; charset=utf-8");
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody.toString()));
            } else {
                builder.GET();
            }
            return builder.build();
        }

        private static String encodeQuery(Map<String, String> query) {
            if (query == null || query.isEmpty()) {
                return "";
            }
            var parts = new ArrayList<String>();
            for (var entry : query.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            return parts.isEmpty() ? "" : "?" + String.join("&", parts);
        }

        private static long parseRetryAfter(HttpResponse<String> response) {
            return response.headers().firstValue("Retry-After")
                    .map(value -> {
                        try {
                            return Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            return 1L;
                        }
                    })
                    .orElse(1L);
        }
    }

    private static final class WorkspaceState {
        private SlackWorkspaceConfig _config;
        private String _token;
        private String _teamName = "";
        private String _selfUserId = "";
        private String _selfUserDisplayName = "";
        private boolean _connected;
        private String _statusMessage = "";
        private List<SlackChannelSummary> _channels = List.of();
        private final Map<String, String> _userNamesById = new LinkedHashMap<>();
        private final Map<String, ConversationCache> _conversationCaches = new LinkedHashMap<>();
    }

    private static final class ConversationCache {
        private SlackChannelSummary _channel;
        private final List<SlackMessageSummary> _messagesNewestFirst = new ArrayList<>();
        private String _nextCursor = "";
        private boolean _historyComplete;
        private final Map<String, SlackThreadDetail> _threadsByTs = new LinkedHashMap<>();
    }

    private static final Logger LOG = LoggerFactory.getLogger(SlackHttpClient.class);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final SlackPaths _paths;
    private final SlackTransport _transport;
    private final Object _lock = new Object();
    private SlackSnapshot _snapshot = SlackSnapshot.empty();
    private final Map<String, WorkspaceState> _workspaces = new LinkedHashMap<>();

    SlackHttpClient(SlackPaths paths) throws IOException {
        this(paths, new DefaultSlackTransport(SwimHttpClients.newBuilder().build()));
    }

    SlackHttpClient(SlackPaths paths, SlackTransport transport) throws IOException {
        _paths = Objects.requireNonNull(paths);
        _transport = Objects.requireNonNull(transport);
        SlackConfigStore.ensureDefaultFiles(paths);
        _snapshot = buildSnapshot();
    }

    @Override
    public SlackSnapshot snapshot() {
        synchronized (_lock) {
            return _snapshot;
        }
    }

    @Override
    public List<SlackChannelSummary> loadChannels(String workspaceId) {
        synchronized (_lock) {
            WorkspaceState state = _workspaces.get(workspaceId);
            return state == null ? List.of() : state._channels;
        }
    }

    @Override
    public SlackMessagePage loadMessages(String workspaceId, String conversationId, String query, int offset, int limit) {
        synchronized (_lock) {
            WorkspaceState workspace = _workspaces.get(workspaceId);
            if (workspace == null || workspace._token == null || workspace._token.isBlank()) {
                return new SlackMessagePage(List.of(), 0, false);
            }
            ConversationCache cache = conversationCache(workspace, conversationId);
            try {
                ensureMessagesLoaded(workspace, cache, Math.max(0, offset) + Math.max(1, limit));
            } catch (IOException e) {
                LOG.warn("Failed to load Slack history for {} {}", workspaceId, conversationId, e);
                return new SlackMessagePage(List.of(), 0, false);
            }
            List<SlackMessageSummary> filtered = filterMessages(cache._messagesNewestFirst, query);
            int safeOffset = Math.max(0, offset);
            int safeLimit = Math.max(0, limit);
            if (safeOffset >= filtered.size() || safeLimit == 0) {
                return new SlackMessagePage(List.of(), filtered.size(), !cache._historyComplete);
            }
            int endExclusive = Math.min(filtered.size(), safeOffset + safeLimit);
            boolean hasMore = endExclusive < filtered.size() || !cache._historyComplete;
            return new SlackMessagePage(List.copyOf(filtered.subList(safeOffset, endExclusive)), filtered.size(), hasMore);
        }
    }

    @Override
    public SlackThreadDetail loadThread(String workspaceId, String conversationId, String threadTs) {
        synchronized (_lock) {
            WorkspaceState workspace = _workspaces.get(workspaceId);
            if (workspace == null || workspace._token == null || workspace._token.isBlank()) {
                return new SlackThreadDetail(workspaceId, conversationId, conversationId, threadTs, List.of());
            }
            ConversationCache cache = conversationCache(workspace, conversationId);
            SlackThreadDetail cached = cache._threadsByTs.get(threadTs);
            if (cached != null) {
                return cached;
            }
            try {
                SlackThreadDetail loaded = fetchThread(workspace, cache, threadTs);
                cache._threadsByTs.put(threadTs, loaded);
                return loaded;
            } catch (IOException e) {
                LOG.warn("Failed to load Slack thread for {} {}", workspaceId, conversationId, e);
                return new SlackThreadDetail(workspaceId, conversationId, displayName(cache._channel), threadTs, List.of());
            }
        }
    }

    @Override
    public void refresh() {
        synchronized (_lock) {
            try {
                _snapshot = buildSnapshot();
            } catch (IOException e) {
                LOG.warn("Failed to refresh Slack snapshot", e);
                _snapshot = new SlackSnapshot(List.of(), "Slack refresh failed: " + safe(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
    }

    @Override
    public SlackSendResult sendMessage(SlackDraft draft) {
        synchronized (_lock) {
            WorkspaceState workspace = _workspaces.get(draft.workspaceId());
            if (workspace == null || workspace._token == null || workspace._token.isBlank()) {
                return SlackSendResult.failure("Slack workspace is not connected");
            }
            JsonObject body = new JsonObject();
            body.addProperty("channel", draft.conversationId());
            body.addProperty("text", draft.text());
            if (draft.threadTs() != null && !draft.threadTs().isBlank()) {
                body.addProperty("thread_ts", draft.threadTs());
            }
            try {
                JsonObject response = invoke(workspace._token, "chat.postMessage", "POST", Map.of(), body);
                if (!response.get("ok").getAsBoolean()) {
                    return SlackSendResult.failure("Slack send failed: " + response.get("error").getAsString());
                }
                workspace._conversationCaches.remove(draft.conversationId());
                return SlackSendResult.success("Slack message sent");
            } catch (IOException e) {
                LOG.warn("Failed to send Slack message", e);
                return SlackSendResult.failure("Slack send failed: " + safe(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
    }

    @Override
    public Path getDataPath() {
        return _paths.workspacesPath();
    }

    @Override
    public void close() {
    }

    static String normalizeText(String text, Map<String, String> userNamesById) {
        String result = safe(text, "");
        result = result.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        var matcher = java.util.regex.Pattern.compile("<([^>]+)>").matcher(result);
        var output = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(renderToken(token, userNamesById)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String renderToken(String token, Map<String, String> userNamesById) {
        if (token.startsWith("@")) {
            String userId = token.substring(1).split("\\|", 2)[0];
            return "@" + safe(userNamesById.get(userId), userId);
        }
        if (token.startsWith("#")) {
            String[] parts = token.substring(1).split("\\|", 2);
            return "#" + safe(parts.length > 1 ? parts[1] : parts[0], parts[0]);
        }
        if (token.startsWith("!")) {
            return "@" + token.substring(1).replace('^', ' ');
        }
        String[] parts = token.split("\\|", 2);
        if (parts[0].startsWith("http")) {
            return parts.length > 1 ? parts[1] + " (" + parts[0] + ")" : parts[0];
        }
        return parts.length > 1 ? parts[1] : parts[0];
    }

    private SlackSnapshot buildSnapshot() throws IOException {
        SlackWorkspacesConfig config = SlackConfigStore.loadWorkspaces(_paths);
        _workspaces.clear();
        if (config.workspaces().isEmpty()) {
            return new SlackSnapshot(List.of(), "Configure Slack workspaces in " + _paths.workspacesPath());
        }
        var summaries = new ArrayList<SlackWorkspaceSummary>();
        var failures = new ArrayList<String>();
        for (SlackWorkspaceConfig workspaceConfig : config.workspaces()) {
            WorkspaceState state = buildWorkspaceState(workspaceConfig);
            _workspaces.put(workspaceConfig.id(), state);
            String label = safe(firstNonBlank(workspaceConfig.label(), state._teamName, workspaceConfig.id()), workspaceConfig.id());
            summaries.add(new SlackWorkspaceSummary(
                    workspaceConfig.id(),
                    label,
                    state._teamName,
                    state._selfUserDisplayName,
                    state._connected,
                    state._statusMessage));
            if (!state._connected) {
                failures.add(label + ": " + safe(state._statusMessage, "not connected"));
            }
        }
        String status = failures.isEmpty()
                ? "Connected to " + summaries.size() + " Slack workspace" + (summaries.size() == 1 ? "" : "s")
                : String.join("  •  ", failures);
        return new SlackSnapshot(List.copyOf(summaries), status);
    }

    private WorkspaceState buildWorkspaceState(SlackWorkspaceConfig config) {
        var state = new WorkspaceState();
        state._config = config;
        try {
            String token = SlackTokenResolver.resolve(config);
            if (token == null || token.isBlank()) {
                state._statusMessage = "Missing Slack token";
                return state;
            }
            state._token = token;
            JsonObject auth = invoke(token, "auth.test", "GET", Map.of(), null);
            state._teamName = string(auth, "team");
            state._selfUserId = string(auth, "user_id");
            state._userNamesById.putAll(fetchUsers(token));
            state._selfUserDisplayName = safe(state._userNamesById.get(state._selfUserId), string(auth, "user"));
            state._channels = fetchChannels(config.id(), token, state._userNamesById);
            state._connected = true;
            state._statusMessage = "Connected";
            return state;
        } catch (IOException e) {
            state._statusMessage = safe(e.getMessage(), e.getClass().getSimpleName());
            return state;
        }
    }

    private Map<String, String> fetchUsers(String token) throws IOException {
        var users = new LinkedHashMap<String, String>();
        String cursor = "";
        do {
            JsonObject response = invoke(token, "users.list", "GET",
                    Map.of("limit", "200", "cursor", cursor), null);
            JsonArray members = response.getAsJsonArray("members");
            if (members != null) {
                for (JsonElement element : members) {
                    JsonObject user = element.getAsJsonObject();
                    users.put(string(user, "id"), displayName(user));
                }
            }
            cursor = response.has("response_metadata")
                    ? string(response.getAsJsonObject("response_metadata"), "next_cursor")
                    : "";
        } while (cursor != null && !cursor.isBlank());
        return users;
    }

    private List<SlackChannelSummary> fetchChannels(String workspaceId, String token, Map<String, String> userNamesById)
            throws IOException {
        var channels = new ArrayList<SlackChannelSummary>();
        String cursor = "";
        do {
            JsonObject response = invoke(token, "conversations.list", "GET",
                    Map.of(
                            "exclude_archived", "true",
                            "limit", "200",
                            "types", "public_channel,private_channel,mpim,im",
                            "cursor", cursor),
                    null);
            JsonArray conversations = response.getAsJsonArray("channels");
            if (conversations != null) {
                for (JsonElement element : conversations) {
                    JsonObject conversation = element.getAsJsonObject();
                    channels.add(toChannelSummary(workspaceId, conversation, userNamesById));
                }
            }
            cursor = response.has("response_metadata")
                    ? string(response.getAsJsonObject("response_metadata"), "next_cursor")
                    : "";
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(channels);
    }

    private ConversationCache conversationCache(WorkspaceState workspace, String conversationId) {
        return workspace._conversationCaches.computeIfAbsent(conversationId, ignored -> {
            var cache = new ConversationCache();
            cache._channel = workspace._channels.stream()
                    .filter(channel -> channel.channelId().equals(conversationId))
                    .findFirst()
                    .orElse(new SlackChannelSummary(
                            workspace._config.id(),
                            conversationId,
                            conversationId,
                            conversationId,
                            "channel",
                            "",
                            "",
                            false));
            return cache;
        });
    }

    private void ensureMessagesLoaded(WorkspaceState workspace, ConversationCache cache, int targetCount) throws IOException {
        while (cache._messagesNewestFirst.size() < targetCount && !cache._historyComplete) {
            JsonObject response = invoke(workspace._token, "conversations.history", "GET",
                    Map.of(
                            "channel", cache._channel.channelId(),
                            "limit", "200",
                            "cursor", cache._nextCursor),
                    null);
            JsonArray messages = response.getAsJsonArray("messages");
            if (messages == null || messages.isEmpty()) {
                cache._historyComplete = true;
                break;
            }
            for (JsonElement element : messages) {
                SlackMessageSummary summary = toMessageSummary(workspace._config.id(), cache._channel.channelId(),
                        element.getAsJsonObject(), workspace._userNamesById);
                cache._messagesNewestFirst.add(summary);
            }
            cache._nextCursor = response.has("response_metadata")
                    ? string(response.getAsJsonObject("response_metadata"), "next_cursor")
                    : "";
            cache._historyComplete = cache._nextCursor == null || cache._nextCursor.isBlank();
        }
    }

    private SlackThreadDetail fetchThread(WorkspaceState workspace, ConversationCache cache, String threadTs) throws IOException {
        var entries = new ArrayList<SlackMessageEntry>();
        String cursor = "";
        do {
            JsonObject response = invoke(workspace._token, "conversations.replies", "GET",
                    Map.of(
                            "channel", cache._channel.channelId(),
                            "ts", threadTs,
                            "limit", "200",
                            "cursor", cursor),
                    null);
            JsonArray messages = response.getAsJsonArray("messages");
            if (messages != null) {
                for (JsonElement element : messages) {
                    JsonObject message = element.getAsJsonObject();
                    entries.add(new SlackMessageEntry(
                            string(message, "ts"),
                            userDisplay(message, workspace._userNamesById),
                            formatTimestamp(string(message, "ts")),
                            normalizeText(string(message, "text"), workspace._userNamesById)));
                }
            }
            cursor = response.has("response_metadata")
                    ? string(response.getAsJsonObject("response_metadata"), "next_cursor")
                    : "";
        } while (cursor != null && !cursor.isBlank());
        if (entries.isEmpty()) {
            for (SlackMessageSummary message : cache._messagesNewestFirst) {
                if (threadTs.equals(rootThreadTs(message))) {
                    entries.add(new SlackMessageEntry(message.ts(), message.userDisplayName(), message.sentAt(), message.text()));
                    break;
                }
            }
        }
        return new SlackThreadDetail(
                workspace._config.id(),
                cache._channel.channelId(),
                displayName(cache._channel),
                threadTs,
                List.copyOf(entries));
    }

    private JsonObject invoke(String token, String method, String httpMethod, Map<String, String> query, JsonObject body)
            throws IOException {
        try {
            JsonObject response = _transport.invoke(token, method, httpMethod, query, body);
            if (response == null || !response.has("ok") || !response.get("ok").getAsBoolean()) {
                String error = response != null && response.has("error") ? response.get("error").getAsString() : "request failed";
                throw new IOException(method + " failed: " + error);
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + method, e);
        }
    }

    private static SlackChannelSummary toChannelSummary(
            String workspaceId,
            JsonObject conversation,
            Map<String, String> userNamesById) {
        String kind;
        String name = string(conversation, "name");
        if (bool(conversation, "is_im")) {
            kind = "im";
            name = safe(userNamesById.get(string(conversation, "user")), string(conversation, "user"));
        } else if (bool(conversation, "is_mpim")) {
            kind = "mpim";
        } else if (bool(conversation, "is_private")) {
            kind = "private";
        } else {
            kind = "channel";
        }
        String displayName = switch (kind) {
        case "im" -> "@" + safe(name, string(conversation, "id"));
        case "mpim" -> "+" + safe(name, string(conversation, "id"));
        default -> "#" + safe(name, string(conversation, "id"));
        };
        String topic = conversation.has("topic") && conversation.get("topic").isJsonObject()
                ? string(conversation.getAsJsonObject("topic"), "value")
                : "";
        String lastActivityAt = conversation.has("updated")
                ? formatTimestamp(String.valueOf(conversation.get("updated").getAsLong()))
                : "";
        return new SlackChannelSummary(
                workspaceId,
                string(conversation, "id"),
                safe(name, string(conversation, "id")),
                displayName,
                kind,
                topic,
                lastActivityAt,
                bool(conversation, "is_archived"));
    }

    private static SlackMessageSummary toMessageSummary(
            String workspaceId,
            String conversationId,
            JsonObject message,
            Map<String, String> userNamesById) {
        String ts = string(message, "ts");
        return new SlackMessageSummary(
                workspaceId,
                conversationId,
                ts,
                firstNonBlank(string(message, "thread_ts"), ts),
                userDisplay(message, userNamesById),
                formatTimestamp(ts),
                normalizeText(string(message, "text"), userNamesById),
                number(message, "reply_count"));
    }

    private static List<SlackMessageSummary> filterMessages(List<SlackMessageSummary> messages, String query) {
        if (query == null || query.isBlank()) {
            return List.copyOf(messages);
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        var filtered = new ArrayList<SlackMessageSummary>();
        for (SlackMessageSummary message : messages) {
            if (contains(message.userDisplayName(), normalized)
                    || contains(message.sentAt(), normalized)
                    || contains(message.text(), normalized)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String userDisplay(JsonObject message, Map<String, String> userNamesById) {
        String userId = string(message, "user");
        if (!userId.isBlank()) {
            return safe(userNamesById.get(userId), userId);
        }
        if (message.has("bot_profile") && message.get("bot_profile").isJsonObject()) {
            return firstNonBlank(string(message.getAsJsonObject("bot_profile"), "name"), string(message, "username"), "(bot)");
        }
        return firstNonBlank(string(message, "username"), string(message, "subtype"), "(unknown)");
    }

    private static String displayName(JsonObject user) {
        if (user == null) {
            return "(unknown)";
        }
        JsonObject profile = user.has("profile") && user.get("profile").isJsonObject()
                ? user.getAsJsonObject("profile")
                : null;
        return firstNonBlank(
                profile == null ? "" : string(profile, "display_name"),
                profile == null ? "" : string(profile, "real_name"),
                string(user, "real_name"),
                string(user, "name"),
                string(user, "id"));
    }

    private static String displayName(SlackChannelSummary channel) {
        return channel == null ? "(no channel)" : safe(channel.displayName(), safe(channel.name(), channel.channelId()));
    }

    private static String rootThreadTs(SlackMessageSummary message) {
        return firstNonBlank(message.threadTs(), message.ts());
    }

    static String formatTimestamp(String rawTs) {
        if (rawTs == null || rawTs.isBlank()) {
            return "";
        }
        try {
            BigDecimal value = new BigDecimal(rawTs);
            long seconds = value.longValue();
            long nanos = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValue();
            return DISPLAY_TIME.format(Instant.ofEpochSecond(seconds, nanos));
        } catch (NumberFormatException e) {
            return rawTs;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && key != null && object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static int number(JsonObject object, String key) {
        return object != null && key != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt()
                : 0;
    }
}
