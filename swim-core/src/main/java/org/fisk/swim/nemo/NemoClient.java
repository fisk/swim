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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
    private final Map<String, Conversation> _conversations = new LinkedHashMap<>();
    private final Map<String, String> _workspaceSessionIds = new LinkedHashMap<>();
    private boolean _sessionsLoaded;
    private String _activeSessionId;
    private long _nextSessionNumber = 1;

    private NemoClient() {
    }

    public static NemoClient getInstance() {
        return _instance;
    }

    synchronized void resetForTests() {
        for (var conversation : _conversations.values()) {
            stopWorker(conversation);
        }
        _conversations.clear();
        _workspaceSessionIds.clear();
        _sessionsLoaded = false;
        _activeSessionId = null;
        _nextSessionNumber = 1;
    }

    record ChatTurn(String speaker, String text, boolean includeInPrompt) {
        ChatTurn(String speaker, String text) {
            this(speaker, text, true);
        }
    }

    record ToolCall(String callId, String name, JsonObject arguments) {
    }

    record ResponseResult(String text, Integer contextUsagePercent) {
    }

    private static final class Conversation {
        private final String _id;
        private final Path _workspaceRoot;
        private final long _createdAtMillis;
        private final List<ChatTurn> _turns = new ArrayList<>();
        private String _title;
        private long _updatedAtMillis;
        private BufferContext _context;
        private Configuration _configuration;
        private ChatPanelView _panelView;
        private boolean _pending;
        private long _pendingStartedAtMillis;
        private Integer _contextUsagePercent;
        private long _requestSequence;
        private long _activeRequestId;
        private Thread _worker;

        private Conversation(String id, String title, Path workspaceRoot, long createdAtMillis, long updatedAtMillis) {
            _id = id;
            _title = title;
            _workspaceRoot = workspaceRoot;
            _createdAtMillis = createdAtMillis;
            _updatedAtMillis = updatedAtMillis;
        }
    }

    public void run(BufferContext context, String question) {
        question = question.trim();
        Configuration configuration = loadConfiguration(getConfigPath());
        var conversation = ensureConversation(context, configuration);
        if (question.startsWith(":")) {
            handleCommand(conversation, question);
        } else if (!question.equals("")) {
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
            boolean toolGitAdd,
            boolean toolGitCommit,
            int toolMaxResults,
            int toolMaxOutputChars,
            int toolCommandTimeoutSeconds) {
    }

    static Path getConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".swim", "nemo.conf");
    }

    static Path getStatePath() {
        return Paths.get(System.getProperty("user.home"), ".swim", "nemo", "sessions.json");
    }

    static String buildInput(BufferContext context, String question) {
        return buildInput(context, List.of(new ChatTurn("me", question)));
    }

    static String buildInput(BufferContext context, List<ChatTurn> turns) {
        var buffer = context.getBuffer();
        var transcript = new StringBuilder();
        for (var turn : turns) {
            if (!turn.includeInPrompt()) {
                continue;
            }
            if (!transcript.isEmpty()) {
                transcript.append("\n\n");
            }
            transcript.append(turn.speaker()).append("> ").append(turn.text());
        }
        return String.join("\n",
                "You are Nemo, an AI assistant inside the SWIM text editor.",
                "Answer concisely and take action when the user asks for a fix or change.",
                "Use the current file first, but work across the workspace when the task requires it.",
                "Avoid unnecessary questions. Make reasonable decisions unless the intent is genuinely ambiguous.",
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
                booleanProperty(properties, "tool.git_add", true),
                booleanProperty(properties, "tool.git_commit", true),
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

    private ResponseResult request(Configuration configuration, BufferContext context, List<ChatTurn> turns) throws IOException, InterruptedException {
        JsonArray inputHistory = new JsonArray();
        inputHistory.add(createUserInputMessage(buildInput(context, turns)));

        while (true) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", configuration.model());
            payload.addProperty("instructions",
                    "You are Nemo, a concise coding assistant inside the SWIM text editor. "
                            + "Default to direct action for coding tasks: inspect, edit, verify, and then summarize. "
                            + "Use tools when they help you answer accurately or modify workspace files. "
                            + "When the user asks for a commit, inspect the repo state, stage only the relevant files, "
                            + "and create a concise commit message yourself unless the user already provided one. "
                            + "Avoid unnecessary questions unless the request is ambiguous or unrelated changes would force a risky commit.");
            payload.add("input", inputHistory);
            var tools = buildTools(configuration);
            if (tools.size() > 0) {
                payload.add("tools", tools);
            }

            JsonObject response = sendRequest(configuration, payload);
            var toolCalls = extractToolCalls(response);
            if (toolCalls.isEmpty()) {
                return new ResponseResult(extractOutputText(response.toString()), extractContextUsagePercent(response));
            }

            JsonArray toolOutputs = new JsonArray();
            for (var call : toolCalls) {
                var output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", call.callId());
                output.addProperty("output", executeToolSafely(configuration, context, call));
                toolOutputs.add(output);
            }
            appendToolRound(inputHistory, response, toolOutputs);
        }
    }

    static String executeToolSafely(Configuration configuration, BufferContext context, ToolCall call)
            throws InterruptedException {
        try {
            return executeTool(configuration, context, call);
        } catch (IOException e) {
            _log.warn("Nemo tool {} failed", call.name(), e);
            return formatToolError(call, e);
        }
    }

    private static String formatToolError(ToolCall call, IOException error) {
        StringBuilder message = new StringBuilder();
        message.append("Tool ").append(call.name()).append(" failed: ");
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) {
            message.append(error.getClass().getSimpleName());
        } else {
            message.append(detail);
        }
        message.append(". Recover by inspecting the path and retrying with the correct tool for that path type.");
        return message.toString();
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
        if (configuration.toolGitAdd()) {
            tools.add(functionTool("git_add",
                    "Stage files in git. Use this before git_commit when the user asks you to commit changes.",
                    schema(List.of(
                            property("path", stringSchema("Optional file or directory path relative to the workspace root. Defaults to the whole workspace."))),
                            List.of())));
        }
        if (configuration.toolGitCommit()) {
            tools.add(functionTool("git_commit",
                    "Create a git commit from the staged changes.",
                    schema(List.of(
                            property("message", stringSchema("Commit message to use."))),
                            List.of("message"))));
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
        case "git_add" -> gitAdd(configuration, context, call.arguments());
        case "git_commit" -> gitCommit(configuration, context, call.arguments());
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
        if (Files.isDirectory(path)) {
            return path;
        }
        if (Files.isRegularFile(path)) {
            Path parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        throw new IOException("Not a directory: " + rawPath);
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
        String mavenHint = mavenAlsoMakeHint(command);
        if (mavenHint != null) {
            return mavenHint;
        }

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

    static String mavenAlsoMakeHint(String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean maven = trimmed.equals("mvn")
                || trimmed.startsWith("mvn ")
                || trimmed.equals("mvnw")
                || trimmed.startsWith("mvnw ")
                || trimmed.startsWith("./mvnw ")
                || trimmed.equals("./mvnw");
        if (!maven) {
            return null;
        }
        boolean hasProjects = trimmed.matches(".*(^|\\s)(-pl|--projects)(\\s|=).*");
        boolean alsoMake = trimmed.matches(".*(^|\\s)(-am|--also-make)(\\s|$).*");
        if (hasProjects && !alsoMake) {
            return "Tool run_command failed: Maven commands that use -pl/--projects in this repository must also include "
                    + "-am/--also-make so dependent reactor modules are built too. "
                    + "Retry with: " + command + " -am";
        }
        return null;
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

    private static String gitAdd(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        var paths = gitAddPathspecs(root, arguments);
        if (paths.isEmpty()) {
            return runShellCommand(configuration, root, "git add -- .");
        }
        String joinedPathspecs = paths.stream()
                .map(NemoClient::shellQuote)
                .collect(Collectors.joining(" "));
        return runShellCommand(configuration, root, "git add -- " + joinedPathspecs);
    }

    private static List<String> gitAddPathspecs(Path root, JsonObject arguments) throws IOException {
        var pathspecs = new ArrayList<String>();
        String rawPath = stringArgument(arguments, "path", "");
        if (!rawPath.isBlank()) {
            pathspecs.add(toGitPathspec(root, rawPath));
        }
        if (arguments.has("paths") && arguments.get("paths").isJsonArray()) {
            for (var element : arguments.getAsJsonArray("paths")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String raw = element.getAsString();
                if (!raw.isBlank()) {
                    pathspecs.add(toGitPathspec(root, raw));
                }
            }
        }
        return pathspecs.stream().distinct().toList();
    }

    private static String toGitPathspec(Path root, String rawPath) throws IOException {
        Path path = resolvePathInsideWorkspace(root, rawPath);
        return root.equals(path) ? "." : root.relativize(path).toString();
    }

    private static String gitCommit(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String message = stringArgument(arguments, "message", "");
        if (message.isBlank()) {
            throw new IOException("message is required");
        }
        return runShellCommand(configuration, root, "git commit -m " + shellQuote(message));
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

    static Integer extractContextUsagePercent(JsonObject response) {
        Integer inputTokens = firstIntAnywhere(response,
                "input_tokens",
                "prompt_tokens",
                "total_input_tokens",
                "inputTokens",
                "promptTokens",
                "totalInputTokens");
        Integer maxTokens = firstIntAnywhere(response,
                "input_tokens_limit",
                "max_input_tokens",
                "context_window",
                "context_window_tokens",
                "max_context_tokens",
                "max_prompt_tokens",
                "max_input_tokens_per_request",
                "context_length",
                "contextLength",
                "inputTokensLimit",
                "maxInputTokens",
                "maxContextTokens",
                "maxPromptTokens");
        if (inputTokens == null || maxTokens == null || maxTokens <= 0) {
            return null;
        }
        return (int) Math.round((inputTokens * 100.0) / maxTokens);
    }

    private static Integer firstIntAnywhere(JsonElement element, String... names) {
        return firstIntAnywhere(element, 0, names);
    }

    private static Integer firstIntAnywhere(JsonElement element, int depth, String... names) {
        if (element == null || element.isJsonNull() || depth > 8) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            Integer direct = firstInt(object, names);
            if (direct != null) {
                return direct;
            }
            for (var entry : object.entrySet()) {
                Integer nested = firstIntAnywhere(entry.getValue(), depth + 1, names);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            for (var child : element.getAsJsonArray()) {
                Integer nested = firstIntAnywhere(child, depth + 1, names);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static Integer firstInt(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                try {
                    return object.get(name).getAsInt();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
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

    private synchronized void ensureSessionsLoaded() {
        if (_sessionsLoaded) {
            return;
        }

        _sessionsLoaded = true;
        _conversations.clear();
        _workspaceSessionIds.clear();
        _activeSessionId = null;
        _nextSessionNumber = 1;

        Path statePath = getStatePath();
        if (!Files.isRegularFile(statePath)) {
            return;
        }

        try {
            JsonObject root = parseJsonObject(Files.readString(statePath, StandardCharsets.UTF_8));
            if (root.has("next_session_number")) {
                _nextSessionNumber = Math.max(1L, root.get("next_session_number").getAsLong());
            }
            if (root.has("active_session_id")) {
                _activeSessionId = compactRawBody(root.get("active_session_id").getAsString());
            }

            JsonObject workspaceSessions = root.getAsJsonObject("workspace_sessions");
            if (workspaceSessions != null) {
                for (String key : workspaceSessions.keySet()) {
                    _workspaceSessionIds.put(key, workspaceSessions.get(key).getAsString());
                }
            }

            JsonArray sessions = root.getAsJsonArray("sessions");
            long highestSessionNumber = 0;
            if (sessions != null) {
                for (JsonElement element : sessions) {
                    JsonObject sessionObject = element.getAsJsonObject();
                    String id = sessionObject.get("id").getAsString();
                    String title = sessionObject.has("title") ? sessionObject.get("title").getAsString() : id;
                    String workspaceRoot = sessionObject.get("workspace_root").getAsString();
                    long createdAtMillis = sessionObject.has("created_at_millis")
                            ? sessionObject.get("created_at_millis").getAsLong()
                            : System.currentTimeMillis();
                    long updatedAtMillis = sessionObject.has("updated_at_millis")
                            ? sessionObject.get("updated_at_millis").getAsLong()
                            : createdAtMillis;

                    var conversation = new Conversation(
                            id,
                            title,
                            Path.of(workspaceRoot).toAbsolutePath().normalize(),
                            createdAtMillis,
                            updatedAtMillis);
                    JsonArray turns = sessionObject.getAsJsonArray("turns");
                    if (turns != null) {
                        for (JsonElement turnElement : turns) {
                            JsonObject turnObject = turnElement.getAsJsonObject();
                            conversation._turns.add(new ChatTurn(
                                    turnObject.get("speaker").getAsString(),
                                    turnObject.get("text").getAsString(),
                                    !turnObject.has("include_in_prompt")
                                            || turnObject.get("include_in_prompt").getAsBoolean()));
                        }
                    }
                    _conversations.put(conversation._id, conversation);
                    highestSessionNumber = Math.max(highestSessionNumber, sessionNumber(conversation._id));
                }
            }

            _nextSessionNumber = Math.max(_nextSessionNumber, highestSessionNumber + 1);
            if (_activeSessionId != null && !_conversations.containsKey(_activeSessionId)) {
                _activeSessionId = null;
            }
            _workspaceSessionIds.entrySet().removeIf(entry -> !_conversations.containsKey(entry.getValue()));
        } catch (Exception e) {
            _log.error("Unable to load Nemo sessions from {}", statePath, e);
            _conversations.clear();
            _workspaceSessionIds.clear();
            _activeSessionId = null;
            _nextSessionNumber = 1;
        }
    }

    private synchronized void persistSessions() {
        var root = new JsonObject();
        root.addProperty("next_session_number", _nextSessionNumber);
        if (_activeSessionId != null) {
            root.addProperty("active_session_id", _activeSessionId);
        }

        var workspaceSessions = new JsonObject();
        for (var entry : _workspaceSessionIds.entrySet()) {
            if (_conversations.containsKey(entry.getValue())) {
                workspaceSessions.addProperty(entry.getKey(), entry.getValue());
            }
        }
        root.add("workspace_sessions", workspaceSessions);

        var sessions = new JsonArray();
        for (var conversation : _conversations.values()) {
            var session = new JsonObject();
            session.addProperty("id", conversation._id);
            session.addProperty("title", conversation._title);
            session.addProperty("workspace_root", conversation._workspaceRoot.toString());
            session.addProperty("created_at_millis", conversation._createdAtMillis);
            session.addProperty("updated_at_millis", conversation._updatedAtMillis);

            var turns = new JsonArray();
            for (var turn : conversation._turns) {
                var turnObject = new JsonObject();
                turnObject.addProperty("speaker", turn.speaker());
                turnObject.addProperty("text", turn.text());
                turnObject.addProperty("include_in_prompt", turn.includeInPrompt());
                turns.add(turnObject);
            }
            session.add("turns", turns);
            sessions.add(session);
        }
        root.add("sessions", sessions);

        Path statePath = getStatePath();
        try {
            Files.createDirectories(statePath.getParent());
            Path tempPath = Files.createTempFile(statePath.getParent(), "sessions-", ".json.tmp");
            Files.writeString(tempPath, _gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempPath, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            _log.error("Unable to persist Nemo sessions to {}", statePath, e);
        }
    }

    private static long sessionNumber(String sessionId) {
        int separator = sessionId.lastIndexOf('-');
        if (separator < 0 || separator + 1 >= sessionId.length()) {
            return 0;
        }
        try {
            return Long.parseLong(sessionId.substring(separator + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private synchronized Conversation ensureConversation(BufferContext context, Configuration configuration) {
        ensureSessionsLoaded();
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        Conversation conversation = currentVisibleConversation();
        if (conversation != null && conversation._workspaceRoot.equals(workspaceRoot)) {
            bindConversation(conversation, context, configuration);
            return conversation;
        }

        conversation = preferredConversationForWorkspace(workspaceRoot);
        if (conversation == null) {
            conversation = createConversation(workspaceRoot, "");
        }

        bindConversation(conversation, context, configuration);
        showConversation(conversation);
        return conversation;
    }

    private Conversation currentVisibleConversation() {
        if (_activeSessionId == null) {
            return null;
        }
        Conversation conversation = _conversations.get(_activeSessionId);
        if (conversation == null || !isPanelVisible(conversation)) {
            return null;
        }
        return conversation;
    }

    private Conversation preferredConversationForWorkspace(Path workspaceRoot) {
        String preferredId = _workspaceSessionIds.get(workspaceRoot.toString());
        if (preferredId != null) {
            Conversation conversation = _conversations.get(preferredId);
            if (conversation != null) {
                return conversation;
            }
        }

        Conversation newest = null;
        for (var conversation : _conversations.values()) {
            if (!conversation._workspaceRoot.equals(workspaceRoot)) {
                continue;
            }
            if (newest == null || conversation._updatedAtMillis > newest._updatedAtMillis) {
                newest = conversation;
            }
        }
        return newest;
    }

    private Conversation createConversation(Path workspaceRoot, String requestedTitle) {
        long sessionNumber = _nextSessionNumber++;
        String id = "session-" + sessionNumber;
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? "Session " + sessionNumber
                : requestedTitle.trim();
        long now = System.currentTimeMillis();
        var conversation = new Conversation(id, title, workspaceRoot, now, now);
        _conversations.put(conversation._id, conversation);
        return conversation;
    }

    private void bindConversation(Conversation conversation, BufferContext context, Configuration configuration) {
        conversation._context = context;
        conversation._configuration = configuration;
    }

    private synchronized void showConversation(Conversation conversation) {
        var window = Window.getInstance();
        if (window == null) {
            throw new IllegalStateException("No active window");
        }

        if (isPanelVisible(conversation)) {
            _activeSessionId = conversation._id;
            _workspaceSessionIds.put(conversation._workspaceRoot.toString(), conversation._id);
            persistSessions();
            return;
        }

        if (window.isShowingPanel()) {
            window.hidePanel();
        }

        conversation._panelView = createPanelView(conversation);
        window.showPanel(conversation._panelView);
        replayConversationIntoVisiblePanel(conversation);
        _activeSessionId = conversation._id;
        _workspaceSessionIds.put(conversation._workspaceRoot.toString(), conversation._id);
        persistSessions();
    }

    private ChatPanelView createPanelView(Conversation conversation) {
        return new ChatPanelView(org.fisk.swim.ui.Rect.create(0, 0, 0, 0),
                formatPanelTitle(conversation),
                message -> submit(conversation, message),
                command -> handleCommand(conversation, command));
    }

    private void replayConversationIntoVisiblePanel(Conversation conversation) {
        if (!isPanelVisible(conversation)) {
            return;
        }
        conversation._panelView.setMessages(mapTurnsToMessages(conversation));
        if (conversation._pending) {
            conversation._panelView.setPending(true, conversation._pendingStartedAtMillis);
        } else {
            conversation._panelView.setPending(false);
        }
        conversation._panelView.setContextUsagePercent(conversation._contextUsagePercent);
    }

    private List<ChatPanelView.ChatMessage> mapTurnsToMessages(Conversation conversation) {
        var messages = new ArrayList<ChatPanelView.ChatMessage>();
        for (var turn : conversation._turns) {
            messages.add(new ChatPanelView.ChatMessage(turn.speaker(), turn.text()));
        }
        return messages;
    }

    private static String formatPanelTitle(Conversation conversation) {
        return "Nemo " + conversation._id + " | " + conversation._title;
    }

    private static boolean isPanelVisible(Conversation conversation) {
        return conversation._panelView != null && conversation._panelView.getParent() != null;
    }

    private synchronized void appendTurn(Conversation conversation, ChatTurn turn) {
        conversation._turns.add(turn);
        conversation._updatedAtMillis = System.currentTimeMillis();
        if (isPanelVisible(conversation)) {
            conversation._panelView.appendMessage(turn.speaker(), turn.text());
        }
        persistSessions();
    }

    private void appendAssistantNote(Conversation conversation, String text) {
        appendTurn(conversation, new ChatTurn("nemo", text, false));
    }

    private synchronized void submit(Conversation conversation, String question) {
        question = question.trim();
        if (question.equals("") || conversation._pending) {
            return;
        }

        appendTurn(conversation, new ChatTurn("me", question));

        if (conversation._configuration.apiKey().isBlank()) {
            appendAssistantNote(conversation, "Set api_key in " + getConfigPath() + " to use :nemo");
            return;
        }

        conversation._pending = true;
        conversation._pendingStartedAtMillis = System.currentTimeMillis();
        conversation._contextUsagePercent = null;
        long requestId = ++conversation._requestSequence;
        conversation._activeRequestId = requestId;
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(true, conversation._pendingStartedAtMillis);
            conversation._panelView.setContextUsagePercent(null);
        }

        var promptTurns = new ArrayList<>(conversation._turns);
        var worker = new Thread(() -> {
            try {
                ResponseResult response = request(conversation._configuration, conversation._context, promptTurns);
                EventThread.getInstance().enqueue(
                        new RunnableEvent(() -> handleResponse(conversation, requestId, response.text(), response.contextUsagePercent())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                _log.error("Nemo request failed", e);
                EventThread.getInstance().enqueue(new RunnableEvent(
                        () -> handleFailure(conversation, requestId, "Nemo failed: " + e.getMessage())));
            }
        }, "swim-nemo-" + conversation._id);
        worker.setDaemon(true);
        conversation._worker = worker;
        worker.start();
    }

    private synchronized void handleCommand(Conversation conversation, String command) {
        String trimmed = command.trim();
        appendTurn(conversation, new ChatTurn("me", trimmed, false));
        int split = trimmed.indexOf(' ');
        String name = split < 0 ? trimmed : trimmed.substring(0, split);
        String argument = split < 0 ? "" : trimmed.substring(split + 1).trim();

        switch (name) {
        case ":abort":
            handleAbortCommand(conversation, argument);
            return;
        case ":sessions":
            appendAssistantNote(conversation, formatSessions(conversation._workspaceRoot));
            return;
        case ":workers":
            appendAssistantNote(conversation, formatWorkers());
            return;
        case ":new":
            handleNewSessionCommand(conversation, argument);
            return;
        case ":switch":
            handleSwitchCommand(conversation, argument);
            return;
        case ":rename":
            handleRenameCommand(conversation, argument);
            return;
        case ":delete":
            handleDeleteCommand(conversation, argument);
            return;
        case ":help":
            appendAssistantNote(conversation,
                    "Available commands: :abort [session-id|all], :sessions, :workers, :new [title], :switch <session-id>, :rename <title>, :delete [session-id], :help, :q");
            return;
        case ":q":
        case ":quit":
            var window = Window.getInstance();
            if (window != null) {
                window.hidePanel();
            }
            return;
        default:
            appendAssistantNote(conversation, "Unknown command: " + trimmed);
        }
    }

    private void handleAbortCommand(Conversation conversation, String argument) {
        if (argument.isBlank()) {
            if (!abortConversation(conversation)) {
                appendAssistantNote(conversation, "Nothing to abort.");
                return;
            }
            appendAssistantNote(conversation, "*aborted*");
            return;
        }

        if ("all".equals(argument)) {
            int aborted = 0;
            for (var target : _conversations.values()) {
                if (abortConversation(target)) {
                    aborted++;
                    appendAssistantNote(target, "*aborted*");
                }
            }
            appendAssistantNote(conversation, aborted == 0
                    ? "Nothing to abort."
                    : "Aborted " + aborted + " worker" + (aborted == 1 ? "." : "s."));
            return;
        }

        Conversation target = findConversation(argument, conversation._workspaceRoot);
        if (target == null) {
            appendAssistantNote(conversation, "Unknown session: " + argument);
            return;
        }
        if (!abortConversation(target)) {
            appendAssistantNote(conversation, "Nothing to abort for " + target._id + ".");
            return;
        }
        appendAssistantNote(target, "*aborted*");
        if (target == conversation) {
            return;
        }
        appendAssistantNote(conversation, "Aborted " + target._id + ".");
    }

    private void handleNewSessionCommand(Conversation conversation, String argument) {
        var created = createConversation(conversation._workspaceRoot, argument);
        bindConversation(created, conversation._context, conversation._configuration);
        showConversation(created);
        appendAssistantNote(created, "Created " + created._id + " (" + created._title + ").");
    }

    private void handleSwitchCommand(Conversation conversation, String argument) {
        if (argument.isBlank()) {
            appendAssistantNote(conversation, "Usage: :switch <session-id>");
            return;
        }

        Conversation target = findConversation(argument, conversation._workspaceRoot);
        if (target == null) {
            appendAssistantNote(conversation, "Unknown session: " + argument);
            return;
        }

        bindConversation(target, conversation._context, conversation._configuration);
        showConversation(target);
        appendAssistantNote(target, "Switched to " + target._id + " (" + target._title + ").");
    }

    private void handleRenameCommand(Conversation conversation, String argument) {
        if (argument.isBlank()) {
            appendAssistantNote(conversation, "Usage: :rename <title>");
            return;
        }
        conversation._title = argument.trim();
        conversation._updatedAtMillis = System.currentTimeMillis();
        persistSessions();
        if (isPanelVisible(conversation)) {
            reopenConversationPanel(conversation);
        }
        appendAssistantNote(conversation, "Renamed " + conversation._id + " to " + conversation._title + ".");
    }

    private void handleDeleteCommand(Conversation conversation, String argument) {
        Conversation target = argument.isBlank()
                ? conversation
                : findConversation(argument, conversation._workspaceRoot);
        if (target == null) {
            appendAssistantNote(conversation, "Unknown session: " + argument);
            return;
        }

        stopWorker(target);
        _conversations.remove(target._id);
        _workspaceSessionIds.entrySet().removeIf(entry -> target._id.equals(entry.getValue()));
        if (target._id.equals(_activeSessionId)) {
            _activeSessionId = null;
        }

        if (target == conversation) {
            Conversation replacement = preferredConversationForWorkspace(conversation._workspaceRoot);
            if (replacement == null) {
                replacement = createConversation(conversation._workspaceRoot, "");
            }
            bindConversation(replacement, conversation._context, conversation._configuration);
            showConversation(replacement);
            appendAssistantNote(replacement, "Deleted " + target._id + ".");
            return;
        }

        persistSessions();
        appendAssistantNote(conversation, "Deleted " + target._id + ".");
    }

    private synchronized void reopenConversationPanel(Conversation conversation) {
        if (!isPanelVisible(conversation)) {
            return;
        }
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.hidePanel();
        conversation._panelView = null;
        showConversation(conversation);
    }

    private Conversation findConversation(String identifier, Path workspaceRoot) {
        Conversation byId = _conversations.get(identifier);
        if (byId != null && byId._workspaceRoot.equals(workspaceRoot)) {
            return byId;
        }

        Conversation match = null;
        for (var conversation : _conversations.values()) {
            if (!conversation._workspaceRoot.equals(workspaceRoot)) {
                continue;
            }
            if (!conversation._title.equalsIgnoreCase(identifier)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = conversation;
        }
        return match;
    }

    private String formatSessions(Path workspaceRoot) {
        var sessions = new ArrayList<Conversation>();
        for (var conversation : _conversations.values()) {
            if (conversation._workspaceRoot.equals(workspaceRoot)) {
                sessions.add(conversation);
            }
        }
        sessions.sort(Comparator.comparingLong(conversation -> conversation._createdAtMillis));
        if (sessions.isEmpty()) {
            return "No Nemo sessions.";
        }

        var lines = new ArrayList<String>();
        lines.add("Sessions:");
        for (var session : sessions) {
            String marker = session._id.equals(_activeSessionId) ? "*" : "-";
            String status = session._pending ? "running " + elapsedSeconds(session) + "s" : "idle";
            lines.add(marker + " " + session._id + " | " + session._title + " | " + status + " | turns=" + session._turns.size());
        }
        return String.join("\n", lines);
    }

    private String formatWorkers() {
        var workers = new ArrayList<Conversation>();
        for (var conversation : _conversations.values()) {
            if (conversation._pending) {
                workers.add(conversation);
            }
        }
        workers.sort(Comparator.comparingLong(conversation -> conversation._pendingStartedAtMillis));
        if (workers.isEmpty()) {
            return "No Nemo workers running.";
        }

        var lines = new ArrayList<String>();
        lines.add("Workers:");
        for (var worker : workers) {
            lines.add("- " + worker._id + " | " + worker._title + " | " + elapsedSeconds(worker) + "s");
        }
        return String.join("\n", lines);
    }

    private static long elapsedSeconds(Conversation conversation) {
        if (!conversation._pending || conversation._pendingStartedAtMillis == 0) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - conversation._pendingStartedAtMillis) / 1000);
    }

    private boolean abortConversation(Conversation conversation) {
        if (!conversation._pending || conversation._worker == null) {
            return false;
        }
        stopWorker(conversation);
        return true;
    }

    private void stopWorker(Conversation conversation) {
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._activeRequestId = 0;
        Thread worker = conversation._worker;
        conversation._worker = null;
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(conversation._contextUsagePercent);
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private synchronized void handleResponse(Conversation conversation, long requestId, String response, Integer contextUsagePercent) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._contextUsagePercent = contextUsagePercent;
        conversation._activeRequestId = 0;
        conversation._worker = null;
        appendTurn(conversation, new ChatTurn("nemo", response));
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(contextUsagePercent);
        }
    }

    private synchronized void handleFailure(Conversation conversation, long requestId, String response) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._contextUsagePercent = null;
        conversation._activeRequestId = 0;
        conversation._worker = null;
        appendAssistantNote(conversation, response);
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(null);
        }
    }

    private void showMessage(String message) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            if (Window.getInstance() != null) {
                Window.getInstance().getCommandView().setMessage(message);
            }
        }));
    }
}
