package org.fisk.swim.nemo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class NemoMcpClient {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_FUNCTION_NAME_LENGTH = 64;
    private static final int MAX_ERROR_CHARS = 4_000;
    private static final Pattern SAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    private final Map<String, Session> _sessions = new LinkedHashMap<>();

    record ToolDescriptor(String exposedName, String serverName, String toolName, String title, String description,
            JsonObject inputSchema) {
    }

    synchronized List<ToolDescriptor> listTools(List<NemoMcpServerConfig> configs) {
        var descriptors = new ArrayList<ToolDescriptor>();
        var usedNames = new LinkedHashSet<String>();
        for (NemoMcpServerConfig config : configs) {
            if (!config.enabled() || config.command().isBlank()) {
                continue;
            }
            try {
                Session session = session(config);
                descriptors.addAll(session.listTools(usedNames));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                _log.warn("Unable to list MCP tools for {}", config.name(), e);
            }
        }
        return descriptors;
    }

    synchronized String callTool(List<NemoMcpServerConfig> configs, String exposedName, JsonObject arguments,
            int maxOutputChars) throws IOException, InterruptedException {
        for (NemoMcpServerConfig config : configs) {
            if (!config.enabled() || config.command().isBlank()) {
                continue;
            }
            Session session = session(config);
            ToolDescriptor descriptor = session.tool(exposedName);
            if (descriptor == null) {
                listTools(configs);
                descriptor = session.tool(exposedName);
            }
            if (descriptor != null) {
                return truncate(formatToolResult(session.callTool(descriptor.toolName(), arguments)), maxOutputChars);
            }
        }
        return "Unknown MCP tool: " + exposedName;
    }

    synchronized String status(List<NemoMcpServerConfig> configs) {
        if (configs.isEmpty()) {
            return "No MCP servers configured.";
        }
        var lines = new ArrayList<String>();
        lines.add("MCP servers:");
        var usedNames = new LinkedHashSet<String>();
        for (NemoMcpServerConfig config : configs) {
            if (!config.enabled()) {
                lines.add("- " + config.name() + " | disabled");
                continue;
            }
            if (config.command().isBlank()) {
                lines.add("- " + config.name() + " | missing command");
                continue;
            }
            try {
                Session session = session(config);
                List<ToolDescriptor> tools = session.listTools(usedNames);
                lines.add("- " + config.name() + " | " + commandLine(config) + " | tools=" + tools.size());
                for (ToolDescriptor tool : tools) {
                    lines.add("  - " + tool.exposedName() + " -> " + tool.toolName());
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                lines.add("- " + config.name() + " | failed: " + e.getMessage());
            }
        }
        return String.join("\n", lines);
    }

    synchronized void shutdownAll() {
        for (Session session : _sessions.values()) {
            session.close();
        }
        _sessions.clear();
    }

    private Session session(NemoMcpServerConfig config) throws IOException, InterruptedException {
        Session session = _sessions.get(config.name());
        if (session == null || !session.config().equals(config)) {
            if (session != null) {
                session.close();
            }
            session = new Session(config);
            _sessions.put(config.name(), session);
        }
        session.ensureStarted();
        return session;
    }

    private static JsonObject object() {
        return new JsonObject();
    }

    private static JsonObject object(String key, JsonElement value) {
        var object = new JsonObject();
        object.add(key, value);
        return object;
    }

    private static JsonObject ensureObjectSchema(JsonObject schema) {
        JsonObject result = schema == null ? new JsonObject() : schema.deepCopy();
        if (!result.has("type")) {
            result.addProperty("type", "object");
        }
        if (!result.has("properties") || !result.get("properties").isJsonObject()) {
            result.add("properties", new JsonObject());
        }
        return result;
    }

    private static String commandLine(NemoMcpServerConfig config) {
        var parts = new ArrayList<String>();
        parts.add(config.command());
        parts.addAll(config.args());
        return String.join(" ", parts);
    }

    private static String makeExposedName(String serverName, String toolName, LinkedHashSet<String> usedNames) {
        String server = sanitizeName(serverName, "server");
        String tool = sanitizeName(toolName, "tool");
        String prefix = "mcp__";
        String separator = "__";
        int available = MAX_FUNCTION_NAME_LENGTH - prefix.length() - separator.length();
        int serverBudget = Math.min(server.length(), Math.max(8, available / 3));
        int toolBudget = Math.max(1, available - serverBudget);
        if (tool.length() > toolBudget) {
            tool = tool.substring(0, toolBudget);
        }
        if (server.length() > serverBudget) {
            server = server.substring(0, serverBudget);
        }
        String base = prefix + server + separator + tool;
        String candidate = base;
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            String suffixText = "_" + suffix++;
            int maxBase = MAX_FUNCTION_NAME_LENGTH - suffixText.length();
            candidate = (base.length() <= maxBase ? base : base.substring(0, maxBase)) + suffixText;
        }
        usedNames.add(candidate);
        return candidate;
    }

    private static String sanitizeName(String value, String fallback) {
        String sanitized = SAFE_NAME_CHARS.matcher(value == null ? "" : value.trim()).replaceAll("_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static String formatToolResult(JsonObject result) {
        var lines = new ArrayList<String>();
        if (result.has("structuredContent") && !result.get("structuredContent").isJsonNull()) {
            lines.add("structuredContent: " + result.get("structuredContent"));
        }
        JsonArray content = result.getAsJsonArray("content");
        if (content != null) {
            for (JsonElement element : content) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String type = stringMember(item, "type");
                switch (type) {
                case "text" -> lines.add(stringMember(item, "text"));
                case "image" -> lines.add("[image: " + firstNonBlank(stringMember(item, "mimeType"), "unknown")
                        + ", " + stringMember(item, "data").length() + " base64 chars]");
                case "resource" -> lines.add(formatResource(item));
                default -> lines.add(item.toString());
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add(result.toString());
        }
        String text = String.join("\n", lines).trim();
        return booleanMember(result, "isError", false) ? "MCP tool error: " + text : text;
    }

    private static String formatResource(JsonObject item) {
        JsonObject resource = item.getAsJsonObject("resource");
        if (resource == null) {
            return item.toString();
        }
        String uri = stringMember(resource, "uri");
        String text = stringMember(resource, "text");
        if (!text.isBlank()) {
            return (uri.isBlank() ? "resource" : "resource " + uri) + ":\n" + text;
        }
        String blob = stringMember(resource, "blob");
        String mimeType = firstNonBlank(stringMember(resource, "mimeType"), "unknown");
        return (uri.isBlank() ? "resource" : "resource " + uri) + " [" + mimeType + ", " + blob.length()
                + " base64 chars]";
    }

    private static String truncate(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stringMember(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString()
                : "";
    }

    private static boolean booleanMember(JsonObject object, String name, boolean fallback) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static final class Session {
        private final NemoMcpServerConfig _config;
        private final BlockingQueue<JsonObject> _messages = new LinkedBlockingQueue<>();
        private final StringBuilder _stderr = new StringBuilder();
        private final Map<String, ToolDescriptor> _toolsByExposedName = new LinkedHashMap<>();
        private Process _process;
        private BufferedWriter _stdin;
        private long _nextId = 1;

        private Session(NemoMcpServerConfig config) {
            _config = config;
        }

        private NemoMcpServerConfig config() {
            return _config;
        }

        private synchronized void ensureStarted() throws IOException, InterruptedException {
            if (_process != null && _process.isAlive()) {
                return;
            }
            close();
            var command = new ArrayList<String>();
            command.add(_config.command());
            command.addAll(_config.args());
            var builder = new ProcessBuilder(command);
            if (_config.cwd() != null) {
                builder.directory(_config.cwd().toFile());
            }
            builder.environment().putAll(_config.env());
            _process = builder.start();
            _stdin = new BufferedWriter(new OutputStreamWriter(_process.getOutputStream(), StandardCharsets.UTF_8));
            startStdoutReader();
            startStderrReader();
            initialize();
        }

        private synchronized List<ToolDescriptor> listTools(LinkedHashSet<String> usedNames)
                throws IOException, InterruptedException {
            ensureStarted();
            _toolsByExposedName.clear();
            var tools = new ArrayList<ToolDescriptor>();
            String cursor = "";
            do {
                JsonObject params = cursor.isBlank() ? object() : object("cursor", primitive(cursor));
                JsonObject result = request("tools/list", params);
                JsonArray items = result.getAsJsonArray("tools");
                if (items != null) {
                    for (JsonElement element : items) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject tool = element.getAsJsonObject();
                        String name = stringMember(tool, "name");
                        if (name.isBlank()) {
                            continue;
                        }
                        String exposedName = makeExposedName(_config.name(), name, usedNames);
                        JsonObject inputSchema = tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
                                ? tool.getAsJsonObject("inputSchema")
                                : object();
                        var descriptor = new ToolDescriptor(
                                exposedName,
                                _config.name(),
                                name,
                                stringMember(tool, "title"),
                                stringMember(tool, "description"),
                                ensureObjectSchema(inputSchema));
                        tools.add(descriptor);
                        _toolsByExposedName.put(exposedName, descriptor);
                    }
                }
                cursor = stringMember(result, "nextCursor");
            } while (!cursor.isBlank());
            return tools;
        }

        private synchronized ToolDescriptor tool(String exposedName) {
            return _toolsByExposedName.get(exposedName);
        }

        private synchronized JsonObject callTool(String toolName, JsonObject arguments)
                throws IOException, InterruptedException {
            ensureStarted();
            var params = new JsonObject();
            params.addProperty("name", toolName);
            params.add("arguments", arguments == null ? object() : arguments.deepCopy());
            return request("tools/call", params);
        }

        private void initialize() throws IOException, InterruptedException {
            var params = new JsonObject();
            params.addProperty("protocolVersion", PROTOCOL_VERSION);
            params.add("capabilities", object());
            var clientInfo = new JsonObject();
            clientInfo.addProperty("name", "swim-nemo");
            clientInfo.addProperty("title", "SWIM Nemo");
            clientInfo.addProperty("version", "0.0.1");
            params.add("clientInfo", clientInfo);
            request("initialize", params);
            notification("notifications/initialized", null);
        }

        private JsonObject request(String method, JsonObject params) throws IOException, InterruptedException {
            long id = _nextId++;
            var request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", id);
            request.addProperty("method", method);
            if (params != null) {
                request.add("params", params);
            }
            send(request);
            return waitForResponse(id);
        }

        private void notification(String method, JsonObject params) throws IOException {
            var notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", method);
            if (params != null) {
                notification.add("params", params);
            }
            send(notification);
        }

        private void send(JsonObject message) throws IOException {
            _stdin.write(message.toString());
            _stdin.write('\n');
            _stdin.flush();
        }

        private JsonObject waitForResponse(long id) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(_config.timeoutSeconds()).toNanos();
            while (System.nanoTime() < deadline) {
                if (_process != null && !_process.isAlive() && _messages.isEmpty()) {
                    throw new IOException("MCP server exited with code " + _process.exitValue() + stderrSummary());
                }
                long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                JsonObject message = _messages.poll(Math.min(100, remainingMillis), TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                if (!message.has("id") || message.get("id").getAsLong() != id) {
                    continue;
                }
                if (message.has("error") && message.get("error").isJsonObject()) {
                    JsonObject error = message.getAsJsonObject("error");
                    throw new IOException("MCP error " + stringMember(error, "code") + ": "
                            + stringMember(error, "message"));
                }
                JsonObject result = message.getAsJsonObject("result");
                return result == null ? object() : result;
            }
            throw new IOException("Timed out waiting for MCP response from " + _config.name() + stderrSummary());
        }

        private String stderrSummary() {
            synchronized (_stderr) {
                return _stderr.isEmpty() ? "" : ": " + _stderr.toString().trim();
            }
        }

        private void startStdoutReader() {
            var thread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(_process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            JsonElement parsed = JsonParser.parseString(line);
                            if (parsed.isJsonObject()) {
                                _messages.offer(parsed.getAsJsonObject());
                            }
                        } catch (RuntimeException e) {
                            _log.debug("Ignoring non-JSON MCP stdout from {}: {}", _config.name(), line);
                        }
                    }
                } catch (IOException e) {
                    _log.debug("MCP stdout reader stopped for {}", _config.name(), e);
                }
            }, "swim-nemo-mcp-stdout-" + _config.name());
            thread.setDaemon(true);
            thread.start();
        }

        private void startStderrReader() {
            var thread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(_process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendStderr(line + "\n");
                    }
                } catch (IOException e) {
                    _log.debug("MCP stderr reader stopped for {}", _config.name(), e);
                }
            }, "swim-nemo-mcp-stderr-" + _config.name());
            thread.setDaemon(true);
            thread.start();
        }

        private void appendStderr(String text) {
            synchronized (_stderr) {
                _stderr.append(text);
                if (_stderr.length() > MAX_ERROR_CHARS) {
                    _stderr.delete(0, _stderr.length() - MAX_ERROR_CHARS);
                }
            }
        }

        private synchronized void close() {
            if (_process != null) {
                _process.destroy();
                try {
                    if (!_process.waitFor(250, TimeUnit.MILLISECONDS)) {
                        _process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    _process.destroyForcibly();
                }
            }
            _process = null;
            _stdin = null;
            _messages.clear();
            _toolsByExposedName.clear();
        }
    }

    private static JsonElement primitive(String value) {
        return JsonParser.parseString(_gson.toJson(value));
    }
}
