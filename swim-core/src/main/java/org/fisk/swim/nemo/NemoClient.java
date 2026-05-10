package org.fisk.swim.nemo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class NemoClient {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();
    private static final String _defaultModel = "gpt-4.1";
    private static final String _defaultBaseUrl = "https://api.openai.com/v1";
    private static final int _defaultMaxResults = 200;
    private static final int _defaultMaxOutputChars = 12_000;
    private static final int _defaultCommandTimeoutSeconds = 20;
    private static final NemoClient _instance = new NemoClient();

    private final HttpClient _httpClient = HttpClient.newHttpClient();
    private Conversation _conversation;

    private NemoClient() {
    }

    public static NemoClient getInstance() {
        return _instance;
    }

    synchronized void resetForTests() {
        _conversation = null;
    }

    record ChatTurn(String speaker, String text) {
    }

    record ToolCall(String callId, String name, JsonObject arguments) {
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
        Configuration configuration = loadConfiguration(getConfigPath());
        var conversation = ensureConversation(context, configuration);
        if (!question.equals("")) {
            submit(conversation, question);
        }
    }

    record Configuration(
            String apiKey,
            String model,
            URI responsesUri,
            Map<String, String> headers,
            Path workspaceRoot,
            boolean toolWebSearch,
            boolean toolListFiles,
            boolean toolReadFile,
            boolean toolSearchFiles,
            boolean toolRunCommand,
            boolean toolWriteFile,
            boolean toolApplyPatch,
            boolean toolGitStatus,
            boolean toolGitDiff,
            int toolMaxResults,
            int toolMaxOutputChars,
            int toolCommandTimeoutSeconds) {
    }

    static Path getConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".swim", "nemo.conf");
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
                "Answer concisely and focus on the current file unless the task requires workspace changes.",
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

    static Configuration loadConfiguration(Path configPath) {
        var properties = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (InputStream input = Files.newInputStream(configPath)) {
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException("Unable to read Nemo config " + configPath, e);
            }
        }

        String apiKey = property(properties, "api_key");
        String model = property(properties, "model");
        if (model.equals("")) {
            model = _defaultModel;
        }

        URI responsesUri = buildResponsesUri(
                property(properties, "responses_url"),
                property(properties, "base_url"));

        var headers = new LinkedHashMap<String, String>();
        if (!apiKey.equals("")) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", "application/json");

        String organization = property(properties, "organization");
        if (!organization.equals("")) {
            headers.put("OpenAI-Organization", organization);
        }

        String project = property(properties, "project");
        if (!project.equals("")) {
            headers.put("OpenAI-Project", project);
        }

        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("header.")) {
                continue;
            }
            String value = property(properties, name);
            if (value.equals("")) {
                continue;
            }
            headers.put(name.substring("header.".length()), value);
        }

        String workspaceRoot = property(properties, "workspace_root");

        return new Configuration(
                apiKey,
                model,
                responsesUri,
                headers,
                workspaceRoot.equals("") ? null : Path.of(workspaceRoot).toAbsolutePath().normalize(),
                booleanProperty(properties, "tool.web_search", false),
                booleanProperty(properties, "tool.list_files", true),
                booleanProperty(properties, "tool.read_file", true),
                booleanProperty(properties, "tool.search_files", true),
                booleanProperty(properties, "tool.run_command", true),
                booleanProperty(properties, "tool.write_file", true),
                booleanProperty(properties, "tool.apply_patch", true),
                booleanProperty(properties, "tool.git_status", true),
                booleanProperty(properties, "tool.git_diff", true),
                intProperty(properties, "tool.max_results", _defaultMaxResults),
                intProperty(properties, "tool.max_output_chars", _defaultMaxOutputChars),
                intProperty(properties, "tool.command_timeout_seconds", _defaultCommandTimeoutSeconds));
    }

    private static String property(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = property(properties, key);
        if (value.equals("")) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = property(properties, key);
        if (value.equals("")) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private JsonObject sendRequest(Configuration configuration, JsonObject payload) throws IOException, InterruptedException {
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
        return parseJsonObject(response.body());
    }

    private String request(Configuration configuration, BufferContext context, List<ChatTurn> turns) throws IOException, InterruptedException {
        JsonArray inputHistory = new JsonArray();
        inputHistory.add(createUserInputMessage(buildInput(context, turns)));

        while (true) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", configuration.model());
            payload.addProperty("instructions", "You are Nemo, a concise coding assistant inside the SWIM text editor. Use tools when they help you answer accurately or modify workspace files.");
            payload.add("input", inputHistory);
            var tools = buildTools(configuration);
            if (tools.size() > 0) {
                payload.add("tools", tools);
            }

            JsonObject response = sendRequest(configuration, payload);
            var toolCalls = extractToolCalls(response);
            if (toolCalls.isEmpty()) {
                return extractOutputText(response.toString());
            }

            JsonArray toolOutputs = new JsonArray();
            for (var call : toolCalls) {
                var output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", call.callId());
                output.addProperty("output", executeTool(configuration, context, call));
                toolOutputs.add(output);
            }
            appendToolRound(inputHistory, response, toolOutputs);
        }
    }

    static void appendToolRound(JsonArray inputHistory, JsonObject response, JsonArray toolOutputs) {
        JsonArray output = response.getAsJsonArray("output");
        if (output != null) {
            for (var item : output) {
                inputHistory.add(item.deepCopy());
            }
        }
        for (var item : toolOutputs) {
            inputHistory.add(item.deepCopy());
        }
    }

    static JsonObject createUserInputMessage(String text) {
        var message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "user");
        var content = new JsonArray();
        var inputText = new JsonObject();
        inputText.addProperty("type", "input_text");
        inputText.addProperty("text", text);
        content.add(inputText);
        message.add("content", content);
        return message;
    }

    static List<ToolCall> extractToolCalls(JsonObject response) {
        var calls = new ArrayList<ToolCall>();
        JsonArray output = response.getAsJsonArray("output");
        if (output == null) {
            return calls;
        }
        for (var item : output) {
            JsonObject object = item.getAsJsonObject();
            if (!"function_call".equals(object.get("type").getAsString())) {
                continue;
            }
            JsonObject arguments = JsonParser.parseString(object.get("arguments").getAsString()).getAsJsonObject();
            calls.add(new ToolCall(
                    object.get("call_id").getAsString(),
                    object.get("name").getAsString(),
                    arguments));
        }
        return calls;
    }

    static JsonArray buildTools(Configuration configuration) {
        var tools = new JsonArray();
        if (configuration.toolWebSearch()) {
            var tool = new JsonObject();
            tool.addProperty("type", "web_search");
            tools.add(tool);
        }
        if (configuration.toolListFiles()) {
            tools.add(functionTool("list_files",
                    "List files in the workspace. Use this to inspect the project structure.",
                    schema(
                            property("path", stringSchema("Path relative to the workspace root.")),
                            property("max_results", integerSchema("Maximum number of files to return.")))));
        }
        if (configuration.toolReadFile()) {
            tools.add(functionTool("read_file",
                    "Read a file from the workspace. Use start_line/end_line to limit output.",
                    schema(List.of(
                            property("path", stringSchema("Path relative to the workspace root.")),
                            property("start_line", integerSchema("Optional 1-based start line.")),
                            property("end_line", integerSchema("Optional 1-based end line."))),
                            List.of("path"))));
        }
        if (configuration.toolSearchFiles()) {
            tools.add(functionTool("search_files",
                    "Search text across workspace files and return matching lines.",
                    schema(List.of(
                            property("query", stringSchema("Text to search for.")),
                            property("path", stringSchema("Optional path relative to the workspace root.")),
                            property("max_results", integerSchema("Maximum number of matches to return."))),
                            List.of("query"))));
        }
        if (configuration.toolRunCommand()) {
            tools.add(functionTool("run_command",
                    "Run a shell command in the workspace and return exit code, stdout, and stderr.",
                    schema(List.of(
                            property("command", stringSchema("Shell command to execute.")),
                            property("cwd", stringSchema("Optional working directory relative to the workspace root."))),
                            List.of("command"))));
        }
        if (configuration.toolWriteFile()) {
            tools.add(functionTool("write_file",
                    "Create or overwrite a file in the workspace. Use this to apply code changes after reading the file you want to edit.",
                    schema(List.of(
                            property("path", stringSchema("Path relative to the workspace root.")),
                            property("content", stringSchema("Full file contents to write."))),
                            List.of("path", "content"))));
        }
        if (configuration.toolApplyPatch()) {
            tools.add(functionTool("apply_patch",
                    "Apply a targeted unified diff patch inside the workspace. Prefer this for small edits instead of rewriting whole files.",
                    schema(List.of(
                            property("patch", stringSchema("Unified diff patch text to apply."))),
                            List.of("patch"))));
        }
        if (configuration.toolGitStatus()) {
            tools.add(functionTool("git_status",
                    "Show git status for the workspace or a subdirectory.",
                    schema(List.of(
                            property("path", stringSchema("Optional path relative to the workspace root."))))));
        }
        if (configuration.toolGitDiff()) {
            tools.add(functionTool("git_diff",
                    "Show git diff for the workspace or a subdirectory.",
                    schema(List.of(
                            property("path", stringSchema("Optional path relative to the workspace root."))),
                            List.of())));
        }
        return tools;
    }

    private static JsonObject functionTool(String name, String description, JsonObject parameters) {
        var tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("parameters", parameters);
        return tool;
    }

    private static JsonObject schema(Map.Entry<String, JsonObject>... properties) {
        return schema(List.of(properties));
    }

    private static JsonObject schema(List<Map.Entry<String, JsonObject>> properties) {
        return schema(properties, List.of());
    }

    private static JsonObject schema(List<Map.Entry<String, JsonObject>> properties, List<String> required) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var propertyObject = new JsonObject();
        for (var entry : properties) {
            propertyObject.add(entry.getKey(), entry.getValue());
        }
        schema.add("properties", propertyObject);
        if (!required.isEmpty()) {
            var requiredArray = new JsonArray();
            for (var name : required) {
                requiredArray.add(name);
            }
            schema.add("required", requiredArray);
        }
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static Map.Entry<String, JsonObject> property(String name, JsonObject schema) {
        return Map.entry(name, schema);
    }

    private static JsonObject stringSchema(String description) {
        var schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        var schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    static String executeTool(Configuration configuration, BufferContext context, ToolCall call) throws IOException, InterruptedException {
        return switch (call.name()) {
        case "list_files" -> listFiles(configuration, context, call.arguments());
        case "read_file" -> readFile(configuration, context, call.arguments());
        case "search_files" -> searchFiles(configuration, context, call.arguments());
        case "run_command" -> runCommand(configuration, context, call.arguments());
        case "write_file" -> writeFile(configuration, context, call.arguments());
        case "apply_patch" -> applyPatch(configuration, context, call.arguments());
        case "git_status" -> gitStatus(configuration, context, call.arguments());
        case "git_diff" -> gitDiff(configuration, context, call.arguments());
        default -> "Unknown tool: " + call.name();
        };
    }

    private static Path resolveWorkspaceRoot(Configuration configuration, BufferContext context) {
        if (configuration.workspaceRoot() != null) {
            return configuration.workspaceRoot();
        }
        var path = context.getBuffer().getPath();
        var projectRoot = ProjectPaths.getProjectRootPath(path);
        if (projectRoot != null) {
            return projectRoot;
        }
        if (path != null && path.toFile().isFile()) {
            return path.toAbsolutePath().getParent();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static Path resolvePathInsideWorkspace(Path workspaceRoot, String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            return workspaceRoot;
        }
        Path requested = Path.of(rawPath);
        Path path = requested.isAbsolute()
                ? requested.normalize()
                : workspaceRoot.resolve(requested).normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw new IOException("Path escapes workspace root: " + rawPath);
        }
        Path fallback = maybeStripWorkspaceRootPrefix(workspaceRoot, requested, path);
        if (fallback != null) {
            return fallback;
        }
        return path;
    }

    private static Path maybeStripWorkspaceRootPrefix(Path workspaceRoot, Path requested, Path resolvedPath) {
        if (requested.isAbsolute() || Files.exists(resolvedPath)) {
            return null;
        }
        Path workspaceName = workspaceRoot.getFileName();
        if (workspaceName == null || requested.getNameCount() == 0 || !workspaceName.equals(requested.getName(0))) {
            return null;
        }
        Path stripped = requested.getNameCount() == 1
                ? workspaceRoot
                : workspaceRoot.resolve(requested.subpath(1, requested.getNameCount())).normalize();
        if (!stripped.startsWith(workspaceRoot)) {
            return null;
        }
        return stripped;
    }

    private static Path requireDirectory(Path path, String rawPath) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException("Not a directory: " + rawPath);
        }
        return path;
    }

    private static String listFiles(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException {
        Path root = resolveWorkspaceRoot(configuration, context);
        Path start = resolvePathInsideWorkspace(root, stringArgument(arguments, "path", ""));
        int maxResults = intArgument(arguments, "max_results", configuration.toolMaxResults());
        var files = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(start)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains(File.separator + ".git" + File.separator))
                    .sorted()
                    .limit(maxResults)
                    .forEach(path -> files.add(root.relativize(path).toString()));
        }
        if (files.isEmpty()) {
            return "(no files)";
        }
        return truncateOutput(configuration, String.join("\n", files));
    }

    private static String readFile(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException {
        Path root = resolveWorkspaceRoot(configuration, context);
        Path path = resolvePathInsideWorkspace(root, stringArgument(arguments, "path", ""));
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a file: " + path);
        }
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int startLine = Math.max(1, intArgument(arguments, "start_line", 1));
        int endLine = intArgument(arguments, "end_line", lines.size());
        endLine = Math.min(lines.size(), endLine <= 0 ? lines.size() : endLine);
        startLine = Math.min(startLine, lines.isEmpty() ? 1 : lines.size());

        var output = new ArrayList<String>();
        for (int i = startLine; i <= endLine && i <= lines.size(); i++) {
            output.add(i + ": " + lines.get(i - 1));
        }
        return truncateOutput(configuration, String.join("\n", output));
    }

    private static String searchFiles(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException {
        Path root = resolveWorkspaceRoot(configuration, context);
        Path start = resolvePathInsideWorkspace(root, stringArgument(arguments, "path", ""));
        String query = stringArgument(arguments, "query", "");
        int maxResults = intArgument(arguments, "max_results", configuration.toolMaxResults());
        var matches = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(start)) {
            var iterator = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains(File.separator + ".git" + File.separator))
                    .sorted()
                    .iterator();
            while (iterator.hasNext() && matches.size() < maxResults) {
                Path path = iterator.next();
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                    if (lines.get(i).contains(query)) {
                        matches.add(root.relativize(path) + ":" + (i + 1) + ": " + lines.get(i));
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            return "(no matches)";
        }
        return truncateOutput(configuration, String.join("\n", matches));
    }

    private static String runCommand(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawCwd = stringArgument(arguments, "cwd", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawCwd), rawCwd);
        String command = stringArgument(arguments, "command", "");

        var process = new ProcessBuilder("zsh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .start();
        try {
            if (!process.waitFor(configuration.toolCommandTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "exit_code: timeout\nstdout:\n\nstderr:\ncommand exceeded " + configuration.toolCommandTimeoutSeconds() + " seconds";
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return truncateOutput(configuration, String.join("\n",
                "exit_code: " + process.exitValue(),
                "stdout:",
                stdout,
                "stderr:",
                stderr));
    }

    private static String writeFile(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException {
        Path root = resolveWorkspaceRoot(configuration, context);
        Path path = resolvePathInsideWorkspace(root, stringArgument(arguments, "path", ""));
        String content = stringArgument(arguments, "content", "");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (isCurrentBufferPath(context, path)) {
            writeOpenBuffer(context, content);
        } else {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }

        return truncateOutput(configuration, "wrote " + content.length() + " chars to " + root.relativize(path));
    }

    private static String applyPatch(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String patch = stringArgument(arguments, "patch", "");
        if (patch.isBlank()) {
            throw new IOException("patch is required");
        }
        Path marker = Files.createTempFile(root, "nemo-patch-", ".diff");
        Files.writeString(marker, patch, StandardCharsets.UTF_8);
        try {
            return runShellCommand(configuration, root, "git apply --whitespace=nowarn " + shellQuote(root.relativize(marker).toString()));
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    private static String gitStatus(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawPath = stringArgument(arguments, "path", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawPath), rawPath);
        return runShellCommand(configuration, cwd, "git status --short --branch");
    }

    private static String gitDiff(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawPath = stringArgument(arguments, "path", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawPath), rawPath);
        return runShellCommand(configuration, cwd, "git diff -- " + shellQuote("."));
    }

    private static String runShellCommand(Configuration configuration, Path cwd, String command) throws IOException, InterruptedException {
        var process = new ProcessBuilder("zsh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .start();
        try {
            if (!process.waitFor(configuration.toolCommandTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "exit_code: timeout\nstdout:\n\nstderr:\ncommand exceeded " + configuration.toolCommandTimeoutSeconds() + " seconds";
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return truncateOutput(configuration, String.join("\n",
                "exit_code: " + process.exitValue(),
                "stdout:",
                stdout,
                "stderr:",
                stderr));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\''") + "'";
    }

    private static boolean isCurrentBufferPath(BufferContext context, Path path) {
        Path currentPath = context.getBuffer().getPath();
        return currentPath != null
                && currentPath.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
    }

    private static void writeOpenBuffer(BufferContext context, String content) throws IOException {
        var eventThread = EventThread.getInstance();
        if (!eventThread.isAlive() || Thread.currentThread() == eventThread) {
            replaceOpenBufferContents(context, content);
            return;
        }

        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        eventThread.enqueue(new RunnableEvent(() -> {
            try {
                replaceOpenBufferContents(context, content);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing open buffer", e);
        }

        Throwable throwable = failure.get();
        if (throwable instanceof IOException ioException) {
            throw ioException;
        }
        if (throwable != null) {
            throw new IOException("Failed to write open buffer", throwable);
        }
    }

    private static void replaceOpenBufferContents(BufferContext context, String content) throws IOException {
        var buffer = context.getBuffer();
        int cursorPosition = Math.min(buffer.getCursor().getPosition(), content.length());
        int length = buffer.getLength();
        if (length > 0) {
            buffer.remove(0, length);
        }
        if (!content.isEmpty()) {
            buffer.insert(0, content);
        }
        buffer.getUndoLog().commit();
        buffer.getCursor().setPosition(cursorPosition);
        context.getBufferView().adaptViewToCursor();
        buffer.writeOrThrow();
    }

    private static String truncateOutput(Configuration configuration, String output) {
        if (output.length() <= configuration.toolMaxOutputChars()) {
            return output;
        }
        return output.substring(0, configuration.toolMaxOutputChars()) + "...";
    }

    private static String stringArgument(JsonObject arguments, String name, String fallback) {
        return arguments.has(name) ? arguments.get(name).getAsString() : fallback;
    }

    private static int intArgument(JsonObject arguments, String name, int fallback) {
        return arguments.has(name) ? arguments.get(name).getAsInt() : fallback;
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
            String message = "Set api_key in " + getConfigPath() + " to use :nemo";
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
                String response = request(conversation._configuration, conversation._context, conversation._turns);
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
