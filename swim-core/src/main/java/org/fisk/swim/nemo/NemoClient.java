package org.fisk.swim.nemo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.help.HelpDocument;
import org.fisk.swim.api.SwimNemoToolDescriptor;
import org.fisk.swim.api.SwimNemoToolInvocation;
import org.fisk.swim.api.SwimNemoToolRegistry;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.CommandView.CommandMenuState;
import org.fisk.swim.ui.CommandView.CommandSpec;
import org.fisk.swim.ui.HostApprovalOverlayView;
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
    private static final String _zaiDefaultBaseUrl = "https://open.bigmodel.cn/";
    private static final String _defaultProvider = "openai";
    private static final String _defaultSystemPrompt = String.join("\n",
            "You are Nemo, an AI assistant inside the SWIM text editor.",
            "Answer concisely and take action when the user asks for a fix or change.",
            "Use the current file first, but work across the workspace when the task requires it.",
            "Avoid unnecessary questions. Make reasonable decisions unless the intent is genuinely ambiguous.",
            "When applicable, follow instructions from relevant SKILLS.md files in the workspace.");
    private static final int _defaultMaxResults = 200;
    private static final int _defaultMaxOutputChars = 12_000;
    private static final int _defaultCommandTimeoutSeconds = 20;
    private static final int _diffContextLines = 3;
    private static final String _defaultCommandPolicy = "restricted";
    private static final String _defaultPermissionMode = "workspace_write";
    private static final String _defaultOsSandbox = "auto";
    private static final String _defaultApprovalPolicy = "on_escalation";
    private static final String _sandboxAvailabilityOverrideProperty = "swim.nemo.os_sandbox_available";
    private static final int _defaultTimeoutSeconds = 60;
    private static final int _defaultMaxRetries = 2;
    private static final int _defaultSkillsMaxFiles = 8;
    private static final int _defaultSkillsMaxChars = 12_000;
    private static final long _temporaryShellCommandApprovalMillis = Duration.ofMinutes(10).toMillis();
    private static final int _asyncShellMaxBufferedChars = 256_000;
    private static final boolean _defaultToolWebSearch = true;
    private static final boolean _defaultToolDelegateTask = true;
    private static final boolean _defaultToolScreenSnapshot = true;
    private static final boolean _defaultToolDriveEditor = true;
    private static final int _defaultWebSearchMaxResults = 5;
    private static final int _maxWebSearchResults = 10;
    private static final Pattern _duckDuckGoResultLinkPattern = Pattern.compile(
            "(?is)<a\\b[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern _duckDuckGoSnippetPattern = Pattern.compile(
            "(?is)<(?:a|div)\\b[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div)>");
    private static final Pattern _htmlTagPattern = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern _htmlEntityPattern = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final NemoClient _instance = new NemoClient();

    private enum OsSandboxBackend {
        NONE,
        MACOS_SANDBOX_EXEC,
        LINUX_BUBBLEWRAP
    }

    private final NemoLangChain4jClient _langChain4jClient = new NemoLangChain4jClient();
    private final NemoResponsesClient _responsesClient = new NemoResponsesClient();
    private final NemoMcpClient _mcpClient = new NemoMcpClient();
    private static final HttpClient _webSearchHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final AtomicReference<OsSandboxBackend> _osSandboxBackend = new AtomicReference<>();
    private final Map<String, Conversation> _conversations = new LinkedHashMap<>();
    private final Map<String, String> _workspaceSessionIds = new LinkedHashMap<>();
    private final Map<String, PendingApproval> _pendingApprovals = new LinkedHashMap<>();
    private final Map<String, AsyncShell> _asyncShells = new LinkedHashMap<>();
    private final List<ApprovalRule> _approvalRules = new ArrayList<>();
    private final List<ApprovedShellLine> _approvedShellLines = new ArrayList<>();
    private EditorControlLease _editorControlLease;
    private boolean _sessionsLoaded;
    private boolean _approvalsLoaded;
    private String _activeSessionId;
    private long _nextSessionNumber = 1;
    private long _nextApprovalNumber = 1;
    private long _nextApprovalRuleNumber = 1;
    private long _nextAsyncShellNumber = 1;
    private long _temporaryShellCommandApprovalExpiresAtMillis;

    private NemoClient() {
    }

    public static NemoClient getInstance() {
        return _instance;
    }

    synchronized void resetForTests() {
        for (var conversation : _conversations.values()) {
            stopWorker(conversation);
        }
        _mcpClient.shutdownAll();
        resetMacOsSandboxAvailabilityForTests();
        _conversations.clear();
        _workspaceSessionIds.clear();
        _pendingApprovals.clear();
        stopAsyncShellsForTests();
        _asyncShells.clear();
        _approvalRules.clear();
        _approvedShellLines.clear();
        _editorControlLease = null;
        _sessionsLoaded = false;
        _approvalsLoaded = false;
        _activeSessionId = null;
        _nextSessionNumber = 1;
        _nextApprovalNumber = 1;
        _nextApprovalRuleNumber = 1;
        _nextAsyncShellNumber = 1;
        _temporaryShellCommandApprovalExpiresAtMillis = 0;
    }

    record ChatTurn(String speaker, String text, boolean includeInPrompt) {
        ChatTurn(String speaker, String text) {
            this(speaker, text, true);
        }
    }

    record ToolCall(String callId, String name, JsonObject arguments) {
    }

    record WebSearchResult(String title, String url, String snippet) {
    }

    private record WorkerSnapshot(String id, String title, boolean pending, long elapsedSeconds,
            Integer contextUsagePercent, List<String> pendingApprovalIds, List<ChatTurn> turns) {
    }

    private record DuckDuckGoResultAnchor(int start, int end, String href, String titleHtml) {
    }

    record ToolExecutionResult(String output, String displayPatch) {
        ToolExecutionResult(String output) {
            this(output, "");
        }
    }

    record ToolTrace(String text, String displayText) {
        ToolTrace(String text) {
            this(text, text);
        }
    }

    record ToolProgress(ToolTrace startTrace, boolean reported) {
    }

    record ResponseResult(String text, Integer contextUsagePercent, List<ToolTrace> toolTraces) {
        ResponseResult(String text, Integer contextUsagePercent) {
            this(text, contextUsagePercent, List.of());
        }
    }

    record ToolApprovalRequest(String toolName, String reason, String summary, String signature, boolean persistable,
            boolean hostOnly) {
        ToolApprovalRequest(String toolName, String reason, String summary, String signature, boolean persistable) {
            this(toolName, reason, summary, signature, persistable, false);
        }
    }

    record ApprovalResult(boolean approved, boolean persisted) {
    }

    interface ToolExecutionSession {
        ApprovalResult requestApproval(Path workspaceRoot, ToolApprovalRequest request) throws InterruptedException;

        default List<ChatTurn> drainQueuedTurns() {
            return List.of();
        }

        default boolean reportToolTrace(ToolTrace trace) {
            return false;
        }
    }

    private record ApprovalRule(String id, String workspaceRoot, String toolName, String signature, long createdAtMillis) {
    }

    private record ApprovedShellLine(String workspaceRoot, String name, String cwd, String command, long createdAtMillis) {
    }

    private record EditorControlLease(String ownerId, String ownerTitle, String workspaceRoot, long startedAtMillis) {
    }

    private static final class PendingApproval {
        private final String _id;
        private final String _workspaceRoot;
        private final String _conversationId;
        private final long _requestId;
        private final ToolApprovalRequest _request;
        private final CountDownLatch _latch = new CountDownLatch(1);
        private boolean _approved;
        private boolean _persist;

        private PendingApproval(String id, String workspaceRoot, String conversationId, long requestId,
                ToolApprovalRequest request) {
            _id = id;
            _workspaceRoot = workspaceRoot;
            _conversationId = conversationId;
            _requestId = requestId;
            _request = request;
        }

        private void resolve(boolean approved, boolean persist) {
            _approved = approved;
            _persist = persist;
            _latch.countDown();
        }
    }

    private static final class AsyncShell {
        private final String _id;
        private final Path _workspaceRoot;
        private final Path _cwd;
        private final String _command;
        private final boolean _sandboxed;
        private final Process _process;
        private final long _startedAtMillis = System.currentTimeMillis();
        private final StringBuilder _stdout = new StringBuilder();
        private final StringBuilder _stderr = new StringBuilder();
        private boolean _stdoutTruncated;
        private boolean _stderrTruncated;
        private boolean _finished;
        private Integer _exitCode;
        private long _finishedAtMillis;

        private AsyncShell(String id, Path workspaceRoot, Path cwd, String command, boolean sandboxed, Process process) {
            _id = id;
            _workspaceRoot = workspaceRoot;
            _cwd = cwd;
            _command = command;
            _sandboxed = sandboxed;
            _process = process;
        }

        private synchronized void appendStdout(String text) {
            _stdoutTruncated = appendBounded(_stdout, text) || _stdoutTruncated;
        }

        private synchronized void appendStderr(String text) {
            _stderrTruncated = appendBounded(_stderr, text) || _stderrTruncated;
        }

        private boolean appendBounded(StringBuilder builder, String text) {
            builder.append(text);
            int overflow = builder.length() - _asyncShellMaxBufferedChars;
            if (overflow > 0) {
                builder.delete(0, overflow);
                return true;
            }
            return false;
        }

        private synchronized void finish(int exitCode) {
            _exitCode = exitCode;
            _finished = true;
            _finishedAtMillis = System.currentTimeMillis();
        }

        private synchronized ShellSnapshot snapshot(int maxOutputChars) {
            return new ShellSnapshot(
                    _id,
                    _workspaceRoot,
                    _cwd,
                    _command,
                    _sandboxed,
                    _finished,
                    _exitCode,
                    _startedAtMillis,
                    _finishedAtMillis,
                    truncateText(_stdout.toString(), maxOutputChars),
                    truncateText(_stderr.toString(), maxOutputChars),
                    _stdoutTruncated,
                    _stderrTruncated);
        }
    }

    private record ShellSnapshot(String id, Path workspaceRoot, Path cwd, String command, boolean sandboxed,
            boolean finished, Integer exitCode, long startedAtMillis, long finishedAtMillis, String stdout,
            String stderr, boolean stdoutTruncated, boolean stderrTruncated) {
    }

    private static final class Conversation {
        private final String _id;
        private final Path _workspaceRoot;
        private final long _createdAtMillis;
        private final List<ChatTurn> _turns = new ArrayList<>();
        private final List<ChatTurn> _queuedUserTurns = new ArrayList<>();
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

    private final class ConversationToolExecutionSession implements ToolExecutionSession {
        private final Conversation _conversation;
        private final long _requestId;

        private ConversationToolExecutionSession(Conversation conversation, long requestId) {
            _conversation = conversation;
            _requestId = requestId;
        }

        @Override
        public ApprovalResult requestApproval(Path workspaceRoot, ToolApprovalRequest request) throws InterruptedException {
            return requestToolApproval(_conversation, _requestId, workspaceRoot, request);
        }

        @Override
        public List<ChatTurn> drainQueuedTurns() {
            return drainQueuedUserTurns(_conversation, _requestId);
        }

        @Override
        public boolean reportToolTrace(ToolTrace trace) {
            return NemoClient.this.reportToolTrace(_conversation, _requestId, trace);
        }
    }

    public void run(BufferContext context, String question) {
        run(context, question, false);
    }

    public void runWorkspace(BufferContext context, String question) {
        run(context, question, true);
    }

    private void run(BufferContext context, String question, boolean workspaceMode) {
        question = question.trim();
        if (mailVisibleToNemo()) {
            Window window = Window.getInstance();
            if (window != null && window.getCommandView() != null) {
                window.getCommandView().setMessage("Nemo is unavailable while mail is visible");
            }
            return;
        }
        Configuration configuration = loadConfiguration(getConfigPath());
        var conversation = ensureConversation(context, configuration, workspaceMode);
        if (question.startsWith(":")) {
            handleCommand(conversation, question);
        } else if (!question.equals("")) {
            submit(conversation, question);
        }
    }

    static final class Configuration {
        private final String _provider;
        private final String _apiKey;
        private final String _model;
        private final String _baseUrl;
        private final boolean _baseUrlExplicit;
        private final String _organization;
        private final String _project;
        private final Map<String, String> _headers;
        private final Map<String, String> _queryParameters;
        private final Map<String, Object> _customParameters;
        private final Path _workspaceRoot;
        private final String _systemPrompt;
        private final Integer _contextWindowTokens;
        private final Integer _maxOutputTokens;
        private final List<NemoMcpServerConfig> _mcpServers;
        private final Double _temperature;
        private final Double _topP;
        private final String _reasoningEffort;
        private final int _timeoutSeconds;
        private final int _maxRetries;
        private final boolean _logRequests;
        private final boolean _logResponses;
        private final boolean _toolWebSearch;
        private final boolean _toolDelegateTask;
        private final boolean _toolScreenSnapshot;
        private final boolean _toolDriveEditor;
        private final boolean _toolListFiles;
        private final boolean _toolReadFile;
        private final boolean _toolSearchFiles;
        private final boolean _toolRunCommand;
        private final boolean _toolWriteFile;
        private final boolean _toolApplyPatch;
        private final boolean _toolGitStatus;
        private final boolean _toolGitDiff;
        private final boolean _toolGitAdd;
        private final boolean _toolGitCommit;
        private final String _toolCommandPolicy;
        private final String _toolPermissionMode;
        private final String _toolOsSandbox;
        private final String _toolApprovalPolicy;
        private final boolean _skillsEnabled;
        private final int _skillsMaxFiles;
        private final int _skillsMaxChars;
        private final boolean _strictTools;
        private final boolean _parallelToolCalls;
        private final boolean _returnThinking;
        private final boolean _sendThinking;
        private final String _thinkingFieldName;
        private final int _toolMaxResults;
        private final int _toolMaxOutputChars;
        private final int _toolCommandTimeoutSeconds;

        Configuration(
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
            this(builder()
                    .provider("openai-compatible")
                    .apiKey(apiKey)
                    .model(model)
                    .baseUrl(responsesUri == null ? _defaultBaseUrl : responsesBaseToChatBase(responsesUri))
                    .headers(stripReservedHeaders(headers))
                    .workspaceRoot(workspaceRoot)
                    .toolWebSearch(toolWebSearch)
                    .toolListFiles(toolListFiles)
                    .toolReadFile(toolReadFile)
                    .toolSearchFiles(toolSearchFiles)
                    .toolRunCommand(toolRunCommand)
                    .toolWriteFile(toolWriteFile)
                    .toolApplyPatch(toolApplyPatch)
                    .toolGitStatus(toolGitStatus)
                    .toolGitDiff(toolGitDiff)
                    .toolGitAdd(toolGitAdd)
                    .toolGitCommit(toolGitCommit)
                    .toolMaxResults(toolMaxResults)
                    .toolMaxOutputChars(toolMaxOutputChars)
                    .toolCommandTimeoutSeconds(toolCommandTimeoutSeconds));
        }

        private Configuration(Builder builder) {
            _provider = builder._provider;
            _apiKey = builder._apiKey;
            _model = builder._model;
            _baseUrl = builder._baseUrl;
            _baseUrlExplicit = builder._baseUrlExplicit;
            _organization = builder._organization;
            _project = builder._project;
            _headers = Map.copyOf(builder._headers);
            _queryParameters = Map.copyOf(builder._queryParameters);
            _customParameters = Map.copyOf(builder._customParameters);
            _workspaceRoot = builder._workspaceRoot;
            _systemPrompt = builder._systemPrompt;
            _contextWindowTokens = builder._contextWindowTokens;
            _maxOutputTokens = builder._maxOutputTokens;
            _mcpServers = List.copyOf(builder._mcpServers);
            _temperature = builder._temperature;
            _topP = builder._topP;
            _reasoningEffort = builder._reasoningEffort;
            _timeoutSeconds = builder._timeoutSeconds;
            _maxRetries = builder._maxRetries;
            _logRequests = builder._logRequests;
            _logResponses = builder._logResponses;
            _toolWebSearch = builder._toolWebSearch;
            _toolDelegateTask = builder._toolDelegateTask;
            _toolScreenSnapshot = builder._toolScreenSnapshot;
            _toolDriveEditor = builder._toolDriveEditor;
            _toolListFiles = builder._toolListFiles;
            _toolReadFile = builder._toolReadFile;
            _toolSearchFiles = builder._toolSearchFiles;
            _toolRunCommand = builder._toolRunCommand;
            _toolWriteFile = builder._toolWriteFile;
            _toolApplyPatch = builder._toolApplyPatch;
            _toolGitStatus = builder._toolGitStatus;
            _toolGitDiff = builder._toolGitDiff;
            _toolGitAdd = builder._toolGitAdd;
            _toolGitCommit = builder._toolGitCommit;
            _toolCommandPolicy = builder._toolCommandPolicy;
            _toolPermissionMode = builder._toolPermissionMode;
            _toolOsSandbox = builder._toolOsSandbox;
            _toolApprovalPolicy = builder._toolApprovalPolicy;
            _skillsEnabled = builder._skillsEnabled;
            _skillsMaxFiles = builder._skillsMaxFiles;
            _skillsMaxChars = builder._skillsMaxChars;
            _strictTools = builder._strictTools;
            _parallelToolCalls = builder._parallelToolCalls;
            _returnThinking = builder._returnThinking;
            _sendThinking = builder._sendThinking;
            _thinkingFieldName = builder._thinkingFieldName;
            _toolMaxResults = builder._toolMaxResults;
            _toolMaxOutputChars = builder._toolMaxOutputChars;
            _toolCommandTimeoutSeconds = builder._toolCommandTimeoutSeconds;
        }

        static Builder builder() {
            return new Builder();
        }

        Configuration withToolPermissionMode(String toolPermissionMode) {
            return new Builder(this)
                    .toolPermissionMode(toolPermissionMode)
                    .build();
        }

        Configuration withToolOsSandbox(String toolOsSandbox) {
            return new Builder(this)
                    .toolOsSandbox(toolOsSandbox)
                    .build();
        }

        Configuration withToolCommandPolicy(String toolCommandPolicy) {
            return new Builder(this)
                    .toolCommandPolicy(toolCommandPolicy)
                    .build();
        }

        Configuration forSubAgent() {
            return new Builder(this)
                    .toolDelegateTask(false)
                    .build();
        }

        static final class Builder {
            private String _provider = _defaultProvider;
            private String _apiKey = "";
            private String _model = _defaultModel;
            private String _baseUrl = _defaultBaseUrl;
            private boolean _baseUrlExplicit;
            private String _organization = "";
            private String _project = "";
            private Map<String, String> _headers = new LinkedHashMap<>();
            private Map<String, String> _queryParameters = new LinkedHashMap<>();
            private Map<String, Object> _customParameters = new LinkedHashMap<>();
            private Path _workspaceRoot;
            private String _systemPrompt = _defaultSystemPrompt;
            private Integer _contextWindowTokens;
            private Integer _maxOutputTokens;
            private List<NemoMcpServerConfig> _mcpServers = List.of();
            private Double _temperature;
            private Double _topP;
            private String _reasoningEffort = "";
            private int _timeoutSeconds = _defaultTimeoutSeconds;
            private int _maxRetries = _defaultMaxRetries;
            private boolean _logRequests;
            private boolean _logResponses;
            private boolean _toolWebSearch = _defaultToolWebSearch;
            private boolean _toolDelegateTask = _defaultToolDelegateTask;
            private boolean _toolScreenSnapshot = _defaultToolScreenSnapshot;
            private boolean _toolDriveEditor = _defaultToolDriveEditor;
            private boolean _toolListFiles = true;
            private boolean _toolReadFile = true;
            private boolean _toolSearchFiles = true;
            private boolean _toolRunCommand = true;
            private boolean _toolWriteFile = true;
            private boolean _toolApplyPatch = true;
            private boolean _toolGitStatus = true;
            private boolean _toolGitDiff = true;
            private boolean _toolGitAdd = true;
            private boolean _toolGitCommit = true;
            private String _toolCommandPolicy = _defaultCommandPolicy;
            private String _toolPermissionMode = _defaultPermissionMode;
            private String _toolOsSandbox = _defaultOsSandbox;
            private String _toolApprovalPolicy = _defaultApprovalPolicy;
            private boolean _skillsEnabled = true;
            private int _skillsMaxFiles = _defaultSkillsMaxFiles;
            private int _skillsMaxChars = _defaultSkillsMaxChars;
            private boolean _strictTools;
            private boolean _parallelToolCalls;
            private boolean _returnThinking;
            private boolean _sendThinking;
            private String _thinkingFieldName = "reasoning_content";
            private int _toolMaxResults = _defaultMaxResults;
            private int _toolMaxOutputChars = _defaultMaxOutputChars;
            private int _toolCommandTimeoutSeconds = _defaultCommandTimeoutSeconds;

            Builder() {
            }

            Builder(Configuration source) {
                _provider = source._provider;
                _apiKey = source._apiKey;
                _model = source._model;
                _baseUrl = source._baseUrl;
                _baseUrlExplicit = source._baseUrlExplicit;
                _organization = source._organization;
                _project = source._project;
                _headers = new LinkedHashMap<>(source._headers);
                _queryParameters = new LinkedHashMap<>(source._queryParameters);
                _customParameters = new LinkedHashMap<>(source._customParameters);
                _workspaceRoot = source._workspaceRoot;
                _systemPrompt = source._systemPrompt;
                _contextWindowTokens = source._contextWindowTokens;
                _maxOutputTokens = source._maxOutputTokens;
                _mcpServers = new ArrayList<>(source._mcpServers);
                _temperature = source._temperature;
                _topP = source._topP;
                _reasoningEffort = source._reasoningEffort;
                _timeoutSeconds = source._timeoutSeconds;
                _maxRetries = source._maxRetries;
                _logRequests = source._logRequests;
                _logResponses = source._logResponses;
                _toolWebSearch = source._toolWebSearch;
                _toolDelegateTask = source._toolDelegateTask;
                _toolScreenSnapshot = source._toolScreenSnapshot;
                _toolDriveEditor = source._toolDriveEditor;
                _toolListFiles = source._toolListFiles;
                _toolReadFile = source._toolReadFile;
                _toolSearchFiles = source._toolSearchFiles;
                _toolRunCommand = source._toolRunCommand;
                _toolWriteFile = source._toolWriteFile;
                _toolApplyPatch = source._toolApplyPatch;
                _toolGitStatus = source._toolGitStatus;
                _toolGitDiff = source._toolGitDiff;
                _toolGitAdd = source._toolGitAdd;
                _toolGitCommit = source._toolGitCommit;
                _toolCommandPolicy = source._toolCommandPolicy;
                _toolPermissionMode = source._toolPermissionMode;
                _toolOsSandbox = source._toolOsSandbox;
                _toolApprovalPolicy = source._toolApprovalPolicy;
                _skillsEnabled = source._skillsEnabled;
                _skillsMaxFiles = source._skillsMaxFiles;
                _skillsMaxChars = source._skillsMaxChars;
                _strictTools = source._strictTools;
                _parallelToolCalls = source._parallelToolCalls;
                _returnThinking = source._returnThinking;
                _sendThinking = source._sendThinking;
                _thinkingFieldName = source._thinkingFieldName;
                _toolMaxResults = source._toolMaxResults;
                _toolMaxOutputChars = source._toolMaxOutputChars;
                _toolCommandTimeoutSeconds = source._toolCommandTimeoutSeconds;
            }

            Builder provider(String provider) {
                _provider = normalizeProvider(provider);
                return this;
            }

            Builder apiKey(String apiKey) {
                _apiKey = apiKey == null ? "" : apiKey.trim();
                return this;
            }

            Builder model(String model) {
                if (model != null && !model.isBlank()) {
                    _model = model.trim();
                }
                return this;
            }

            Builder baseUrl(String baseUrl) {
                if (baseUrl != null && !baseUrl.isBlank()) {
                    _baseUrl = baseUrl.trim();
                    _baseUrlExplicit = true;
                }
                return this;
            }

            Builder organization(String organization) {
                _organization = organization == null ? "" : organization.trim();
                return this;
            }

            Builder project(String project) {
                _project = project == null ? "" : project.trim();
                return this;
            }

            Builder headers(Map<String, String> headers) {
                _headers = new LinkedHashMap<>(headers);
                return this;
            }

            Builder queryParameters(Map<String, String> queryParameters) {
                _queryParameters = new LinkedHashMap<>(queryParameters);
                return this;
            }

            Builder customParameters(Map<String, Object> customParameters) {
                _customParameters = new LinkedHashMap<>(customParameters);
                return this;
            }

            Builder workspaceRoot(Path workspaceRoot) {
                _workspaceRoot = workspaceRoot;
                return this;
            }

            Builder systemPrompt(String systemPrompt) {
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    _systemPrompt = systemPrompt.trim();
                }
                return this;
            }

            Builder contextWindowTokens(Integer contextWindowTokens) {
                _contextWindowTokens = contextWindowTokens;
                return this;
            }

            Builder maxOutputTokens(Integer maxOutputTokens) {
                _maxOutputTokens = maxOutputTokens;
                return this;
            }

            Builder mcpServers(List<NemoMcpServerConfig> mcpServers) {
                _mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
                return this;
            }

            Builder temperature(Double temperature) {
                _temperature = temperature;
                return this;
            }

            Builder topP(Double topP) {
                _topP = topP;
                return this;
            }

            Builder reasoningEffort(String reasoningEffort) {
                _reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
                return this;
            }

            Builder timeoutSeconds(int timeoutSeconds) {
                _timeoutSeconds = timeoutSeconds;
                return this;
            }

            Builder maxRetries(int maxRetries) {
                _maxRetries = maxRetries;
                return this;
            }

            Builder logRequests(boolean logRequests) {
                _logRequests = logRequests;
                return this;
            }

            Builder logResponses(boolean logResponses) {
                _logResponses = logResponses;
                return this;
            }

            Builder toolWebSearch(boolean toolWebSearch) {
                _toolWebSearch = toolWebSearch;
                return this;
            }

            Builder toolDelegateTask(boolean toolDelegateTask) {
                _toolDelegateTask = toolDelegateTask;
                return this;
            }

            Builder toolScreenSnapshot(boolean toolScreenSnapshot) {
                _toolScreenSnapshot = toolScreenSnapshot;
                return this;
            }

            Builder toolDriveEditor(boolean toolDriveEditor) {
                _toolDriveEditor = toolDriveEditor;
                return this;
            }

            Builder toolListFiles(boolean toolListFiles) {
                _toolListFiles = toolListFiles;
                return this;
            }

            Builder toolReadFile(boolean toolReadFile) {
                _toolReadFile = toolReadFile;
                return this;
            }

            Builder toolSearchFiles(boolean toolSearchFiles) {
                _toolSearchFiles = toolSearchFiles;
                return this;
            }

            Builder toolRunCommand(boolean toolRunCommand) {
                _toolRunCommand = toolRunCommand;
                return this;
            }

            Builder toolWriteFile(boolean toolWriteFile) {
                _toolWriteFile = toolWriteFile;
                return this;
            }

            Builder toolApplyPatch(boolean toolApplyPatch) {
                _toolApplyPatch = toolApplyPatch;
                return this;
            }

            Builder toolGitStatus(boolean toolGitStatus) {
                _toolGitStatus = toolGitStatus;
                return this;
            }

            Builder toolGitDiff(boolean toolGitDiff) {
                _toolGitDiff = toolGitDiff;
                return this;
            }

            Builder toolGitAdd(boolean toolGitAdd) {
                _toolGitAdd = toolGitAdd;
                return this;
            }

            Builder toolGitCommit(boolean toolGitCommit) {
                _toolGitCommit = toolGitCommit;
                return this;
            }

            Builder toolCommandPolicy(String toolCommandPolicy) {
                if (toolCommandPolicy != null && !toolCommandPolicy.isBlank()) {
                    String normalized = toolCommandPolicy.trim().toLowerCase();
                    _toolCommandPolicy = "trusted".equals(normalized) ? "trusted" : _defaultCommandPolicy;
                }
                return this;
            }

            Builder toolPermissionMode(String toolPermissionMode) {
                _toolPermissionMode = normalizeToolPermissionMode(toolPermissionMode);
                return this;
            }

            Builder toolOsSandbox(String toolOsSandbox) {
                _toolOsSandbox = normalizeToolOsSandbox(toolOsSandbox);
                return this;
            }

            Builder toolApprovalPolicy(String toolApprovalPolicy) {
                _toolApprovalPolicy = normalizeToolApprovalPolicy(toolApprovalPolicy);
                return this;
            }

            Builder skillsEnabled(boolean skillsEnabled) {
                _skillsEnabled = skillsEnabled;
                return this;
            }

            Builder skillsMaxFiles(int skillsMaxFiles) {
                _skillsMaxFiles = skillsMaxFiles;
                return this;
            }

            Builder skillsMaxChars(int skillsMaxChars) {
                _skillsMaxChars = skillsMaxChars;
                return this;
            }

            Builder strictTools(boolean strictTools) {
                _strictTools = strictTools;
                return this;
            }

            Builder parallelToolCalls(boolean parallelToolCalls) {
                _parallelToolCalls = parallelToolCalls;
                return this;
            }

            Builder returnThinking(boolean returnThinking) {
                _returnThinking = returnThinking;
                return this;
            }

            Builder sendThinking(boolean sendThinking) {
                _sendThinking = sendThinking;
                return this;
            }

            Builder thinkingFieldName(String thinkingFieldName) {
                if (thinkingFieldName != null && !thinkingFieldName.isBlank()) {
                    _thinkingFieldName = thinkingFieldName.trim();
                }
                return this;
            }

            Builder toolMaxResults(int toolMaxResults) {
                _toolMaxResults = toolMaxResults;
                return this;
            }

            Builder toolMaxOutputChars(int toolMaxOutputChars) {
                _toolMaxOutputChars = toolMaxOutputChars;
                return this;
            }

            Builder toolCommandTimeoutSeconds(int toolCommandTimeoutSeconds) {
                _toolCommandTimeoutSeconds = toolCommandTimeoutSeconds;
                return this;
            }

            Configuration build() {
                if (!_baseUrlExplicit) {
                    _baseUrl = defaultBaseUrlForProvider(_provider);
                }
                return new Configuration(this);
            }
        }

        String provider() { return _provider; }
        String apiKey() { return _apiKey; }
        String model() { return _model; }
        String baseUrl() { return _baseUrl; }
        boolean isZaiProvider() { return "zai".equals(_provider); }
        boolean isGeminiProvider() { return "gemini".equals(_provider); }
        String organization() { return _organization; }
        String project() { return _project; }
        Map<String, String> headers() { return _headers; }
        Map<String, String> queryParameters() { return _queryParameters; }
        Map<String, Object> customParameters() { return _customParameters; }
        Path workspaceRoot() { return _workspaceRoot; }
        String systemPrompt() { return _systemPrompt; }
        Integer contextWindowTokens() { return _contextWindowTokens; }
        Integer maxOutputTokens() { return _maxOutputTokens; }
        List<NemoMcpServerConfig> mcpServers() { return _mcpServers; }
        Double temperature() { return _temperature; }
        Double topP() { return _topP; }
        String reasoningEffort() { return _reasoningEffort; }
        int timeoutSeconds() { return _timeoutSeconds; }
        int maxRetries() { return _maxRetries; }
        boolean logRequests() { return _logRequests; }
        boolean logResponses() { return _logResponses; }
        boolean toolWebSearch() { return _toolWebSearch; }
        boolean toolDelegateTask() { return _toolDelegateTask; }
        boolean toolScreenSnapshot() { return _toolScreenSnapshot; }
        boolean toolDriveEditor() { return _toolDriveEditor; }
        boolean toolListFiles() { return _toolListFiles; }
        boolean toolReadFile() { return _toolReadFile; }
        boolean toolSearchFiles() { return _toolSearchFiles; }
        boolean toolRunCommand() { return _toolRunCommand; }
        boolean toolWriteFile() { return _toolWriteFile; }
        boolean toolApplyPatch() { return _toolApplyPatch; }
        boolean toolGitStatus() { return _toolGitStatus; }
        boolean toolGitDiff() { return _toolGitDiff; }
        boolean toolGitAdd() { return _toolGitAdd; }
        boolean toolGitCommit() { return _toolGitCommit; }
        boolean toolGitEnabled() { return _toolGitStatus || _toolGitDiff || _toolGitAdd || _toolGitCommit; }
        String toolCommandPolicy() { return _toolCommandPolicy; }
        String toolPermissionMode() { return _toolPermissionMode; }
        String toolOsSandbox() { return _toolOsSandbox; }
        String toolApprovalPolicy() { return _toolApprovalPolicy; }
        boolean skillsEnabled() { return _skillsEnabled; }
        int skillsMaxFiles() { return _skillsMaxFiles; }
        int skillsMaxChars() { return _skillsMaxChars; }
        boolean strictTools() { return _strictTools; }
        boolean parallelToolCalls() { return _parallelToolCalls; }
        boolean returnThinking() { return _returnThinking; }
        boolean sendThinking() { return _sendThinking; }
        String thinkingFieldName() { return _thinkingFieldName; }
        int toolMaxResults() { return _toolMaxResults; }
        int toolMaxOutputChars() { return _toolMaxOutputChars; }
        int toolCommandTimeoutSeconds() { return _toolCommandTimeoutSeconds; }
        boolean requiresApiKey() {
            return "openai".equals(_provider)
                    || "gemini".equals(_provider)
                    || "zai".equals(_provider)
                    || "chatgpt".equals(_provider)
                    || "responses".equals(_provider)
                    || "openai-responses".equals(_provider);
        }

        boolean usesResponsesApi() {
            return "chatgpt".equals(_provider)
                    || "responses".equals(_provider)
                    || "openai-responses".equals(_provider);
        }

        static String normalizeToolPermissionMode(String toolPermissionMode) {
            if (toolPermissionMode == null || toolPermissionMode.isBlank()) {
                return _defaultPermissionMode;
            }
            String normalized = toolPermissionMode.trim().toLowerCase().replace('-', '_');
            return switch (normalized) {
            case "read_only", "workspace_write", "full_access" -> normalized;
            default -> _defaultPermissionMode;
            };
        }

        static String normalizeToolOsSandbox(String toolOsSandbox) {
            if (toolOsSandbox == null || toolOsSandbox.isBlank()) {
                return _defaultOsSandbox;
            }
            String normalized = toolOsSandbox.trim().toLowerCase().replace('-', '_');
            return switch (normalized) {
            case "auto", "required", "disabled" -> normalized;
            default -> _defaultOsSandbox;
            };
        }

        static String normalizeToolApprovalPolicy(String toolApprovalPolicy) {
            if (toolApprovalPolicy == null || toolApprovalPolicy.isBlank()) {
                return _defaultApprovalPolicy;
            }
            String normalized = toolApprovalPolicy.trim().toLowerCase().replace('-', '_');
            return switch (normalized) {
            case "never", "on_request", "on_escalation" -> normalized;
            default -> _defaultApprovalPolicy;
            };
        }

        private static String normalizeProvider(String provider) {
            if (provider == null || provider.isBlank()) {
                return _defaultProvider;
            }
            String normalized = provider.trim().toLowerCase().replace('_', '-');
            return switch (normalized) {
            case "gemini", "google", "google-ai", "googleai", "google-ai-studio", "google-ai-studio-api",
                    "ai-studio", "aistudio", "google-gemini" -> "gemini";
            case "z.ai", "z-ai", "zai", "zipuai", "zipu-ai", "zhipuai", "zhipu-ai", "zhipu", "bigmodel",
                    "bigmodel.cn" -> "zai";
            default -> normalized;
            };
        }

        private static String defaultBaseUrlForProvider(String provider) {
            return switch (provider) {
            case "gemini" -> "";
            case "zai" -> _zaiDefaultBaseUrl;
            default -> _defaultBaseUrl;
            };
        }

        private static String responsesBaseToChatBase(URI responsesUri) {
            String raw = responsesUri.toString();
            if (raw.endsWith("/responses")) {
                return raw.substring(0, raw.length() - "/responses".length());
            }
            return raw;
        }

        private static Map<String, String> stripReservedHeaders(Map<String, String> headers) {
            var copy = new LinkedHashMap<String, String>();
            if (headers == null) {
                return copy;
            }
            for (var entry : headers.entrySet()) {
                String key = entry.getKey();
                if ("Authorization".equalsIgnoreCase(key)
                        || "Content-Type".equalsIgnoreCase(key)
                        || "OpenAI-Organization".equalsIgnoreCase(key)
                        || "OpenAI-Project".equalsIgnoreCase(key)) {
                    continue;
                }
                copy.put(key, entry.getValue());
            }
            return copy;
        }
    }

    static Path getConfigDirectory() {
        return Paths.get(System.getProperty("user.home"), ".swim", "nemo");
    }

    static Path getConfigPath() {
        return getConfigDirectory().resolve("nemo.conf");
    }

    static Path getLegacyConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".swim", "nemo.conf");
    }

    static Path getStatePath() {
        return getConfigDirectory().resolve("sessions.json");
    }

    static Path getApprovalsPath() {
        return getConfigDirectory().resolve("approvals.json");
    }

    static String buildInput(BufferContext context, String question) {
        return buildInput(context, List.of(new ChatTurn("me", question)));
    }

    static String buildInput(BufferContext context, List<ChatTurn> turns) {
        return NemoPromptBuilder.buildInput(context, turns, Configuration.builder().build(), List.of());
    }

    static Configuration loadConfiguration(Path configPath) {
        configPath = migrateLegacyConfigIfNeeded(configPath);
        if (!Files.isRegularFile(configPath)) {
            return Configuration.builder().build();
        }

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return Configuration.builder().build();
            }
            return trimmed.startsWith("{")
                    ? loadJsonConfiguration(trimmed)
                    : loadPropertiesConfiguration(raw);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read Nemo config " + configPath, e);
        }
    }

    private static Path migrateLegacyConfigIfNeeded(Path configPath) {
        Path legacyConfigPath = getLegacyConfigPath();
        if (configPath.equals(legacyConfigPath)) {
            return configPath;
        }
        if (Files.isRegularFile(configPath) || !Files.isRegularFile(legacyConfigPath)) {
            return configPath;
        }
        try {
            Files.createDirectories(configPath.getParent());
            try {
                Files.move(legacyConfigPath, configPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(legacyConfigPath, configPath);
            }
            return configPath;
        } catch (IOException e) {
            _log.warn("Unable to migrate Nemo config from {} to {}", legacyConfigPath, configPath, e);
            return legacyConfigPath;
        }
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

    private static Configuration loadPropertiesConfiguration(String raw) throws IOException {
        var properties = new Properties();
        try (var input = new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))) {
            properties.load(input);
        }
        String apiKey = resolveSecret(
                property(properties, "api_key"),
                property(properties, "api_key_env"),
                property(properties, "api_key_command"));
        var headers = propertiesWithPrefix(properties, "header.");
        if (!apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", "application/json");
        String organization = property(properties, "organization");
        if (!organization.isBlank()) {
            headers.put("OpenAI-Organization", organization);
        }
        String project = property(properties, "project");
        if (!project.isBlank()) {
            headers.put("OpenAI-Project", project);
        }
        var builder = Configuration.builder()
                .provider(property(properties, "provider"))
                .apiKey(apiKey)
                .model(property(properties, "model"))
                .baseUrl(firstNonBlank(
                        property(properties, "base_url"),
                        baseUrlFromResponsesUrl(property(properties, "responses_url"))))
                .organization(organization)
                .project(project)
                .workspaceRoot(pathOrNull(property(properties, "workspace_root")))
                .systemPrompt(property(properties, "system_prompt"))
                .contextWindowTokens(nullableIntegerProperty(properties, "context_window_tokens"))
                .maxOutputTokens(nullableIntegerProperty(properties, "max_output_tokens"))
                .mcpServers(mcpServersFromProperties(properties))
                .temperature(nullableDoubleProperty(properties, "temperature"))
                .topP(nullableDoubleProperty(properties, "top_p"))
                .reasoningEffort(property(properties, "reasoning_effort"))
                .timeoutSeconds(intProperty(properties, "timeout_seconds", _defaultTimeoutSeconds))
                .maxRetries(intProperty(properties, "max_retries", _defaultMaxRetries))
                .logRequests(booleanProperty(properties, "log_requests", false))
                .logResponses(booleanProperty(properties, "log_responses", false))
                .toolWebSearch(booleanProperty(properties, "tool.web_search", _defaultToolWebSearch))
                .toolDelegateTask(booleanProperty(properties, "tool.delegate_task", _defaultToolDelegateTask))
                .toolScreenSnapshot(booleanProperty(properties, "tool.screen_snapshot", _defaultToolScreenSnapshot))
                .toolDriveEditor(booleanProperty(properties, "tool.drive_editor", _defaultToolDriveEditor))
                .toolListFiles(booleanProperty(properties, "tool.list_files", true))
                .toolReadFile(booleanProperty(properties, "tool.read_file", true))
                .toolSearchFiles(booleanProperty(properties, "tool.search_files", true))
                .toolRunCommand(booleanProperty(properties, "tool.run_command", true))
                .toolWriteFile(booleanProperty(properties, "tool.write_file", true))
                .toolApplyPatch(booleanProperty(properties, "tool.apply_patch", true))
                .toolGitStatus(booleanProperty(properties, "tool.git_status", true))
                .toolGitDiff(booleanProperty(properties, "tool.git_diff", true))
                .toolGitAdd(booleanProperty(properties, "tool.git_add", true))
                .toolGitCommit(booleanProperty(properties, "tool.git_commit", true))
                .toolCommandPolicy(property(properties, "tool.command_policy"))
                .toolPermissionMode(property(properties, "tool.permission_mode"))
                .toolOsSandbox(property(properties, "tool.os_sandbox"))
                .toolApprovalPolicy(property(properties, "tool.approval_policy"))
                .skillsEnabled(booleanProperty(properties, "skills.enabled", true))
                .skillsMaxFiles(intProperty(properties, "skills.max_files", _defaultSkillsMaxFiles))
                .skillsMaxChars(intProperty(properties, "skills.max_chars", _defaultSkillsMaxChars))
                .strictTools(booleanProperty(properties, "strict_tools", false))
                .parallelToolCalls(booleanProperty(properties, "parallel_tool_calls", false))
                .returnThinking(booleanProperty(properties, "return_thinking", false))
                .sendThinking(booleanProperty(properties, "send_thinking", false))
                .thinkingFieldName(property(properties, "thinking_field_name"))
                .toolMaxResults(intProperty(properties, "tool.max_results", _defaultMaxResults))
                .toolMaxOutputChars(intProperty(properties, "tool.max_output_chars", _defaultMaxOutputChars))
                .toolCommandTimeoutSeconds(intProperty(properties, "tool.command_timeout_seconds", _defaultCommandTimeoutSeconds));
        builder.headers(headers);
        builder.queryParameters(propertiesWithPrefix(properties, "query."));
        builder.customParameters(objectPropertiesWithPrefix(properties, "param."));
        return builder.build();
    }

    private static Configuration loadJsonConfiguration(String raw) throws IOException {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject tools = objectMember(root, "tools");
        JsonObject skills = objectMember(root, "skills");
        JsonObject mcp = objectMember(root, "mcp");
        var builder = Configuration.builder()
                .provider(firstNonBlank(stringMember(root, "provider"), stringMember(root, "vendor")))
                .apiKey(resolveSecret(
                        firstNonBlank(stringMember(root, "apiKey"), stringMember(root, "api_key")),
                        firstNonBlank(stringMember(root, "apiKeyEnv"), stringMember(root, "api_key_env")),
                        firstNonBlank(stringMember(root, "apiKeyCommand"), stringMember(root, "api_key_command"))))
                .model(stringMember(root, "model"))
                .baseUrl(firstNonBlank(
                        stringMember(root, "baseUrl"),
                        stringMember(root, "base_url"),
                        baseUrlFromResponsesUrl(firstNonBlank(stringMember(root, "responsesUrl"), stringMember(root, "responses_url")))))
                .organization(stringMember(root, "organization"))
                .project(stringMember(root, "project"))
                .workspaceRoot(pathOrNull(firstNonBlank(stringMember(root, "workspaceRoot"), stringMember(root, "workspace_root"))))
                .systemPrompt(firstNonBlank(stringMember(root, "systemPrompt"), stringMember(root, "system_prompt")))
                .contextWindowTokens(integerMember(root, "contextWindowTokens", "context_window_tokens"))
                .maxOutputTokens(integerMember(root, "maxOutputTokens", "max_output_tokens"))
                .mcpServers(mcpServersFromJson(mcp))
                .temperature(doubleMember(root, "temperature"))
                .topP(doubleMember(root, "topP", "top_p"))
                .reasoningEffort(firstNonBlank(stringMember(root, "reasoningEffort"), stringMember(root, "reasoning_effort")))
                .timeoutSeconds(firstNonNull(integerMember(root, "timeoutSeconds", "timeout_seconds"), _defaultTimeoutSeconds))
                .maxRetries(firstNonNull(integerMember(root, "maxRetries", "max_retries"), _defaultMaxRetries))
                .logRequests(booleanMember(root, false, "logRequests", "log_requests"))
                .logResponses(booleanMember(root, false, "logResponses", "log_responses"))
                .toolWebSearch(booleanMember(tools, _defaultToolWebSearch, "webSearch", "web_search"))
                .toolDelegateTask(booleanMember(tools, _defaultToolDelegateTask, "delegateTask", "delegate_task"))
                .toolScreenSnapshot(booleanMember(tools, _defaultToolScreenSnapshot, "screenSnapshot", "screen_snapshot"))
                .toolDriveEditor(booleanMember(tools, _defaultToolDriveEditor, "driveEditor", "drive_editor"))
                .toolListFiles(booleanMember(tools, true, "listFiles", "list_files"))
                .toolReadFile(booleanMember(tools, true, "readFile", "read_file"))
                .toolSearchFiles(booleanMember(tools, true, "searchFiles", "search_files"))
                .toolRunCommand(booleanMember(tools, true, "runCommand", "run_command"))
                .toolWriteFile(booleanMember(tools, true, "writeFile", "write_file"))
                .toolApplyPatch(booleanMember(tools, true, "applyPatch", "apply_patch"))
                .toolGitStatus(booleanMember(tools, true, "gitStatus", "git_status"))
                .toolGitDiff(booleanMember(tools, true, "gitDiff", "git_diff"))
                .toolGitAdd(booleanMember(tools, true, "gitAdd", "git_add"))
                .toolGitCommit(booleanMember(tools, true, "gitCommit", "git_commit"))
                .toolCommandPolicy(firstNonBlank(stringMember(tools, "commandPolicy"), stringMember(tools, "command_policy")))
                .toolPermissionMode(firstNonBlank(stringMember(tools, "permissionMode"), stringMember(tools, "permission_mode")))
                .toolOsSandbox(firstNonBlank(stringMember(tools, "osSandbox"), stringMember(tools, "os_sandbox")))
                .toolApprovalPolicy(firstNonBlank(stringMember(tools, "approvalPolicy"), stringMember(tools, "approval_policy")))
                .skillsEnabled(booleanMember(skills, true, "enabled"))
                .skillsMaxFiles(firstNonNull(integerMember(skills, "maxFiles", "max_files"), _defaultSkillsMaxFiles))
                .skillsMaxChars(firstNonNull(integerMember(skills, "maxChars", "max_chars"), _defaultSkillsMaxChars))
                .strictTools(booleanMember(root, false, "strictTools", "strict_tools"))
                .parallelToolCalls(booleanMember(root, false, "parallelToolCalls", "parallel_tool_calls"))
                .returnThinking(booleanMember(root, false, "returnThinking", "return_thinking"))
                .sendThinking(booleanMember(root, false, "sendThinking", "send_thinking"))
                .thinkingFieldName(firstNonBlank(stringMember(root, "thinkingFieldName"), stringMember(root, "thinking_field_name")))
                .toolMaxResults(firstNonNull(integerMember(tools, "maxResults", "max_results"), _defaultMaxResults))
                .toolMaxOutputChars(firstNonNull(integerMember(tools, "maxOutputChars", "max_output_chars"), _defaultMaxOutputChars))
                .toolCommandTimeoutSeconds(firstNonNull(integerMember(tools, "commandTimeoutSeconds", "command_timeout_seconds"),
                        _defaultCommandTimeoutSeconds));
        builder.headers(stringMapMember(root, "headers"));
        builder.queryParameters(stringMapMember(root, "queryParameters", "query_parameters"));
        builder.customParameters(objectMapMember(root, "customParameters", "custom_parameters"));
        return builder.build();
    }

    private static List<NemoMcpServerConfig> mcpServersFromJson(JsonObject mcp) {
        JsonObject servers = objectMember(mcp, "servers");
        if (servers == null) {
            servers = objectMember(mcp, "mcpServers", "mcp_servers");
        }
        if (servers == null) {
            return List.of();
        }
        var configs = new ArrayList<NemoMcpServerConfig>();
        for (var entry : servers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject server = entry.getValue().getAsJsonObject();
            String name = firstNonBlank(stringMember(server, "name"), entry.getKey());
            String command = stringMember(server, "command");
            configs.add(new NemoMcpServerConfig(
                    name,
                    booleanMember(server, true, "enabled"),
                    command,
                    stringArrayMember(server, "args", "arguments"),
                    stringMapMember(server, "env", "environment"),
                    pathOrNull(firstNonBlank(stringMember(server, "cwd"), stringMember(server, "workingDirectory"),
                            stringMember(server, "working_directory"))),
                    firstNonNull(integerMember(server, "timeoutSeconds", "timeout_seconds"), _defaultCommandTimeoutSeconds)));
        }
        return configs;
    }

    private static List<NemoMcpServerConfig> mcpServersFromProperties(Properties properties) {
        var names = new LinkedHashSet<String>();
        for (String key : properties.stringPropertyNames()) {
            String name = mcpServerNameFromProperty(key, "mcp.servers.");
            if (name.isBlank()) {
                name = mcpServerNameFromProperty(key, "mcp.server.");
            }
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        var configs = new ArrayList<NemoMcpServerConfig>();
        for (String name : names) {
            String prefix = properties.stringPropertyNames().stream().anyMatch(key -> key.startsWith("mcp.servers." + name + "."))
                    ? "mcp.servers." + name + "."
                    : "mcp.server." + name + ".";
            configs.add(new NemoMcpServerConfig(
                    name,
                    booleanProperty(properties, prefix + "enabled", true),
                    property(properties, prefix + "command"),
                    splitShellWords(property(properties, prefix + "args")),
                    propertiesWithPrefix(properties, prefix + "env."),
                    pathOrNull(firstNonBlank(property(properties, prefix + "cwd"),
                            property(properties, prefix + "working_directory"),
                            property(properties, prefix + "workingDirectory"))),
                    intProperty(properties, prefix + "timeout_seconds", _defaultCommandTimeoutSeconds)));
        }
        return configs;
    }

    private static String mcpServerNameFromProperty(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            return "";
        }
        int start = prefix.length();
        int end = key.indexOf('.', start);
        return end < 0 ? "" : key.substring(start, end);
    }

    private static List<String> stringArrayMember(JsonObject object, String... names) {
        if (object == null) {
            return List.of();
        }
        for (String name : names) {
            if (!object.has(name)) {
                continue;
            }
            JsonElement value = object.get(name);
            if (value.isJsonArray()) {
                var values = new ArrayList<String>();
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        values.add(element.getAsString());
                    }
                }
                return values;
            }
            if (value.isJsonPrimitive()) {
                return splitShellWords(value.getAsString());
            }
        }
        return List.of();
    }

    private static List<String> splitShellWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var words = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (escaped) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return words;
    }

    private static String resolveSecret(String inlineValue, String envName, String command) throws IOException {
        String value = firstNonBlank(inlineValue, envValue(envName));
        if (!value.isBlank()) {
            return value;
        }
        if (command == null || command.isBlank()) {
            return "";
        }
        var process = new ProcessBuilder("zsh", "-lc", command)
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out running api_key_command");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running api_key_command", e);
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private static String envValue(String envName) {
        if (envName == null || envName.isBlank()) {
            return "";
        }
        return System.getenv().getOrDefault(envName.trim(), "");
    }

    private static Integer nullableIntegerProperty(Properties properties, String key) {
        String value = property(properties, key);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double nullableDoubleProperty(Properties properties, String key) {
        String value = property(properties, key);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Map<String, String> propertiesWithPrefix(Properties properties, String prefix) {
        var values = new LinkedHashMap<String, String>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String value = property(properties, name);
            if (!value.isBlank()) {
                values.put(name.substring(prefix.length()), value);
            }
        }
        return values;
    }

    private static Map<String, Object> objectPropertiesWithPrefix(Properties properties, String prefix) {
        var values = new LinkedHashMap<String, Object>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String value = property(properties, name);
            if (!value.isBlank()) {
                values.put(name.substring(prefix.length()), value);
            }
        }
        return values;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String baseUrlFromResponsesUrl(String responsesUrl) {
        if (responsesUrl == null || responsesUrl.isBlank()) {
            return "";
        }
        return Configuration.responsesBaseToChatBase(URI.create(responsesUrl));
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static String stringMember(JsonObject object, String... names) {
        if (object == null) {
            return "";
        }
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                return object.get(name).getAsString().trim();
            }
        }
        return "";
    }

    private static Integer integerMember(JsonObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsInt();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static Double doubleMember(JsonObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsDouble();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean booleanMember(JsonObject object, boolean fallback, String... names) {
        if (object == null) {
            return fallback;
        }
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsBoolean();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return fallback;
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonObject()
                ? object.getAsJsonObject(name)
                : null;
    }

    private static JsonObject objectMember(JsonObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            JsonObject member = objectMember(object, name);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    private static Map<String, String> stringMapMember(JsonObject object, String... names) {
        var values = new LinkedHashMap<String, String>();
        JsonObject member = null;
        for (String name : names) {
            member = objectMember(object, name);
            if (member != null) {
                break;
            }
        }
        if (member == null) {
            return values;
        }
        for (var entry : member.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return values;
    }

    private static Map<String, Object> objectMapMember(JsonObject object, String... names) {
        var values = new LinkedHashMap<String, Object>();
        JsonObject member = null;
        for (String name : names) {
            member = objectMember(object, name);
            if (member != null) {
                break;
            }
        }
        if (member == null) {
            return values;
        }
        for (var entry : member.entrySet()) {
            values.put(entry.getKey(), _gson.fromJson(entry.getValue(), Object.class));
        }
        return values;
    }

    static String headerName(String rawName) {
        return rawName.toLowerCase().replace('_', '-');
    }

    private ResponseResult request(Configuration configuration, BufferContext context, List<ChatTurn> turns,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        if (mailVisibleToNemo()) {
            return new ResponseResult("Nemo is unavailable while mail is visible; switch away from mail and retry.",
                    null, List.of());
        }
        if (configuration.usesResponsesApi()) {
            return _responsesClient.request(configuration, context, turns, executionSession);
        }
        return _langChain4jClient.request(configuration, context, turns, executionSession);
    }

    private static boolean mailVisibleToNemo() {
        Window window = Window.getInstance();
        return window != null && window.isMailVisibleToGuest();
    }

    static List<ChatTurn> drainQueuedTurns(ToolExecutionSession executionSession) {
        return executionSession == null ? List.of() : executionSession.drainQueuedTurns();
    }

    static String queuedWorkerMessage(List<ChatTurn> turns) {
        var lines = new ArrayList<String>();
        lines.add("Additional user message(s) arrived while this worker was already running.");
        lines.add("Adjust course if relevant, and explicitly account for them in your next response.");
        for (ChatTurn turn : turns) {
            lines.add(turn.speaker() + "> " + turn.text());
        }
        return String.join("\n", lines);
    }


    static String executeToolSafely(Configuration configuration, BufferContext context, ToolCall call)
            throws InterruptedException {
        return executeToolSafely(configuration, context, call, null);
    }

    static String executeToolSafely(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws InterruptedException {
        return executeToolDetailedSafely(configuration, context, call, executionSession).output();
    }

    static ToolExecutionResult executeToolDetailedSafely(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws InterruptedException {
        try {
            return executeToolDetailed(configuration, context, call, executionSession);
        } catch (IOException e) {
            _log.warn("Nemo tool {} failed", call.name(), e);
            return new ToolExecutionResult(formatToolError(call, e));
        }
    }

    static ToolTrace toolTrace(ToolCall call, String output) {
        return toolTrace(call, new ToolExecutionResult(output));
    }

    static ToolTrace toolStartTrace(ToolCall call) {
        ToolTrace trace = toolTrace(call, new ToolExecutionResult(""));
        return new ToolTrace(trace.text() + " ...", trace.displayText() + " ...");
    }

    static ToolProgress reportToolStart(ToolExecutionSession executionSession, ToolCall call) {
        ToolTrace trace = toolStartTrace(call);
        return new ToolProgress(trace, reportToolTrace(executionSession, trace));
    }

    static void reportOrCollectToolCompletion(ToolExecutionSession executionSession, List<ToolTrace> fallback,
            ToolProgress progress, ToolTrace completionTrace) {
        if (!progress.reported()) {
            fallback.add(completionTrace);
            return;
        }
        if (!toolCompletionAddsVisibleDetail(progress.startTrace(), completionTrace)) {
            return;
        }
        executionSession.reportToolTrace(completionTrace);
    }

    private static boolean reportToolTrace(ToolExecutionSession executionSession, ToolTrace trace) {
        return executionSession != null && executionSession.reportToolTrace(trace);
    }

    private static boolean toolCompletionAddsVisibleDetail(ToolTrace startTrace, ToolTrace completionTrace) {
        String startText = stripRunningSuffix(startTrace.displayText());
        return !completionTrace.displayText().equals(startText);
    }

    private static String stripRunningSuffix(String text) {
        return text.endsWith(" ...") ? text.substring(0, text.length() - 4) : text;
    }

    static ToolTrace toolTrace(ToolCall call, ToolExecutionResult result) {
        String output = result.output();
        String detail;
        if (isMcpToolName(call.name()) || isPluginToolName(call.name())) {
            detail = withOutputStatus(argumentSummary(call.arguments()), output);
        } else {
            detail = switch (call.name()) {
        case "delegate_task" -> withOutputStatus(delegateTaskSummary(call.arguments()), output);
        case "worker_status" -> argumentSummary(call.arguments(), "session_id");
        case "read_worker" -> argumentSummary(call.arguments(), "session_id", "max_turns");
        case "join_worker" -> withOutputStatus(argumentSummary(call.arguments(), "session_id", "timeout_seconds"), output);
        case "message_worker" -> withOutputStatus(argumentSummary(call.arguments(), "session_id", "message"), output);
        case "start_editor_control" -> firstOutputLine(output);
        case "screen_snapshot" -> withOutputStatus("", output);
        case "drive_editor" -> withOutputStatus(argumentSummary(call.arguments(), "input", "max_events"), output);
        case "finish_editor_control" -> firstOutputLine(output);
        case "current_editor_context" -> firstOutputLine(output);
        case "list_files" -> argumentSummary(call.arguments(), "path", "directory", "max_results");
        case "find" -> argumentSummary(call.arguments(), "query", "directory", "max_results");
        case "read_file" -> argumentSummary(call.arguments(), "path", "start_line", "end_line");
        case "search_files" -> argumentSummary(call.arguments(), "query", "path", "directory", "max_results");
        case "run_command" -> withOutputStatus(argumentSummary(call.arguments(), "command", "cwd"), output);
        case "shell_start" -> withOutputStatus(argumentSummary(call.arguments(), "command", "cwd"), output);
        case "shell_poll" -> argumentSummary(call.arguments(), "shell_id", "max_output_chars", "forget_if_finished");
        case "shell_stop" -> withOutputStatus(argumentSummary(call.arguments(), "shell_id"), output);
        case "shell_save" -> withOutputStatus(argumentSummary(call.arguments(), "name", "command", "cwd"), output);
        case "shell_list" -> firstOutputLine(output);
        case "shell_run" -> withOutputStatus(argumentSummary(call.arguments(), "name", "async"), output);
        case "mvn" -> withOutputStatus(mvnSummary(call.arguments()), output);
        case "write_file" -> writeFileSummary(call.arguments());
        case "search_replace" -> searchReplaceSummary(call.arguments());
        case "apply_patch" -> "patch=" + stringArgument(call.arguments(), "patch", "").length() + " chars";
        case "git" -> gitSummary(call.arguments());
        case "git_status", "git_diff" -> argumentSummary(call.arguments(), "path");
        case "git_add" -> gitAddSummary(call.arguments());
        case "git_commit" -> argumentSummary(call.arguments(), "message");
        default -> argumentSummary(call.arguments());
        };
        }
        String text = detail.isBlank() ? call.name() : call.name() + ": " + detail;
        String displayPatch = result.displayPatch() == null ? "" : result.displayPatch().stripTrailing();
        return displayPatch.isBlank()
                ? new ToolTrace(text)
                : new ToolTrace(text, text + "\n" + displayPatch);
    }

    private static String withOutputStatus(String detail, String output) {
        String status = firstOutputLine(output);
        if (status.isBlank()) {
            return detail;
        }
        return detail.isBlank() ? status : detail + " -> " + status;
    }

    private static String firstOutputLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        int newline = output.indexOf('\n');
        return newline < 0 ? output.strip() : output.substring(0, newline).strip();
    }

    private static String writeFileSummary(JsonObject arguments) {
        String path = stringArgument(arguments, "path", "");
        int chars = stringArgument(arguments, "content", "").length();
        return joinSummary(List.of(
                path.isBlank() ? "" : "path=" + path,
                "content=" + chars + " chars"));
    }

    private static String searchReplaceSummary(JsonObject arguments) {
        return joinSummary(List.of(
                argumentSummary(arguments, "directory", "path"),
                "search=" + compactArgumentValue(arguments.get("search")),
                "replace=" + compactArgumentValue(arguments.get("replace")),
                arguments.has("regex") ? "regex=" + arguments.get("regex").getAsBoolean() : "",
                arguments.has("replace_all") ? "replace_all=" + arguments.get("replace_all").getAsBoolean() : ""));
    }

    private static String searchReplaceDisplayPath(JsonObject arguments) {
        String directory = stringArgument(arguments, "directory", "");
        String path = stringArgument(arguments, "path", "");
        if (directory.isBlank()) {
            return path;
        }
        if (path.isBlank()) {
            return directory;
        }
        return directory + "/" + path;
    }

    private static String mvnSummary(JsonObject arguments) {
        MavenInvocation invocation = mavenInvocation(arguments);
        return joinSummary(List.of(
                "directory=" + mavenDisplayDirectory(invocation.directory()),
                "mvn " + String.join(" ", invocation.arguments())));
    }

    private static String gitSummary(JsonObject arguments) {
        return joinSummary(List.of(
                argumentSummary(arguments, "directory"),
                "git " + String.join(" ", gitArguments(arguments))));
    }

    private static String delegateTaskSummary(JsonObject arguments) {
        return joinSummary(List.of(
                argumentSummary(arguments, "title"),
                "task=" + compactArgumentValue(arguments.get("task"))));
    }

    private static String gitAddSummary(JsonObject arguments) {
        if (arguments.has("paths") && arguments.get("paths").isJsonArray()) {
            var values = new ArrayList<String>();
            for (JsonElement element : arguments.getAsJsonArray("paths")) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    values.add(element.getAsString());
                }
            }
            if (!values.isEmpty()) {
                return "paths=" + String.join(",", values);
            }
        }
        return argumentSummary(arguments, "path");
    }

    private static String argumentSummary(JsonObject arguments, String... names) {
        var parts = new ArrayList<String>();
        if (names.length == 0) {
            for (var entry : arguments.entrySet()) {
                parts.add(entry.getKey() + "=" + compactArgumentValue(entry.getValue()));
            }
            return joinSummary(parts);
        }
        for (String name : names) {
            if (arguments.has(name)) {
                parts.add(name + "=" + compactArgumentValue(arguments.get(name)));
            }
        }
        return joinSummary(parts);
    }

    private static String compactArgumentValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        String text;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            text = value.getAsString();
        } else {
            text = value.toString();
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    private static String joinSummary(List<String> parts) {
        return parts.stream()
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
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

    static List<NemoMcpClient.ToolDescriptor> mcpToolDescriptors(Configuration configuration) {
        if (!isMcpAllowed(configuration)) {
            return List.of();
        }
        return _instance._mcpClient.listTools(configuration.mcpServers());
    }

    static List<SwimNemoToolDescriptor> pluginToolDescriptors(Configuration configuration) {
        return SwimNemoToolRegistry.listTools().stream()
                .filter(tool -> !"read_only".equals(configuration.toolPermissionMode()) || tool.availableInReadOnly())
                .toList();
    }

    static boolean isMcpAllowed(Configuration configuration) {
        return !configuration.mcpServers().isEmpty() && !"read_only".equals(configuration.toolPermissionMode());
    }

    static String pluginToolDescription(SwimNemoToolDescriptor tool) {
        var parts = new ArrayList<String>();
        parts.add("Plugin tool " + tool.toolName() + " from plugin " + tool.pluginId() + ".");
        if (!tool.description().isBlank()) {
            parts.add(tool.description());
        }
        if (tool.availableInReadOnly()) {
            parts.add("This tool is available in read-only mode.");
        } else {
            parts.add("This tool is hidden in read-only mode.");
        }
        if (tool.requiresApproval()) {
            parts.add("Plugin tools run plugin code and require approval unless the session is full-access.");
        }
        return String.join(" ", parts);
    }

    static JsonObject pluginToolSchema(SwimNemoToolDescriptor tool) {
        String schemaJson = tool.inputSchemaJson();
        if (schemaJson != null && !schemaJson.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(schemaJson);
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException e) {
                _log.warn("Ignoring invalid Nemo plugin tool schema for {}", tool.exposedName(), e);
            }
        }
        return schema(List.of());
    }

    private static JsonObject schema(List<Map.Entry<String, JsonObject>> properties) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var propertyObject = new JsonObject();
        for (var entry : properties) {
            propertyObject.add(entry.getKey(), entry.getValue());
        }
        schema.add("properties", propertyObject);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    static String executeTool(Configuration configuration, BufferContext context, ToolCall call) throws IOException, InterruptedException {
        return executeTool(configuration, context, call, null);
    }

    static String executeTool(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        return executeToolDetailed(configuration, context, call, executionSession).output();
    }

    static ToolExecutionResult executeToolDetailed(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        String permissionBlock = permissionBlock(configuration, call.name());
        if (permissionBlock != null) {
            return new ToolExecutionResult(permissionBlock);
        }
        String approvalBlock = requestActionApprovalIfNeeded(configuration, context, call, executionSession);
        if (approvalBlock != null) {
            return new ToolExecutionResult(approvalBlock);
        }
        if (isMcpToolName(call.name())) {
            return new ToolExecutionResult(_instance.callMcpTool(configuration, context, call, executionSession));
        }
        if (isPluginToolName(call.name())) {
            return new ToolExecutionResult(_instance.callPluginTool(configuration, context, call, executionSession));
        }
        return switch (call.name()) {
        case "web_search" -> new ToolExecutionResult(webSearch(call.arguments()));
        case "delegate_task" -> new ToolExecutionResult(_instance.delegateTask(configuration, context, call.arguments()));
        case "worker_status" -> new ToolExecutionResult(_instance.workerStatus(configuration, context, call.arguments()));
        case "read_worker" -> new ToolExecutionResult(_instance.readWorker(configuration, context, call.arguments()));
        case "join_worker" -> new ToolExecutionResult(_instance.joinWorker(configuration, context, call.arguments()));
        case "message_worker" -> new ToolExecutionResult(_instance.messageWorker(configuration, context, call.arguments()));
        case "start_editor_control" -> new ToolExecutionResult(startEditorControl(configuration, context, executionSession));
        case "screen_snapshot" -> new ToolExecutionResult(screenSnapshot(configuration, context, call.arguments(), executionSession));
        case "drive_editor" -> new ToolExecutionResult(driveEditor(configuration, context, call.arguments(), executionSession));
        case "finish_editor_control" -> new ToolExecutionResult(finishEditorControl(executionSession));
        case "swim_help" -> new ToolExecutionResult(HelpDocument.renderForNemo(stringArgument(call.arguments(), "topic", "")));
        case "current_editor_context" -> new ToolExecutionResult(currentEditorContext(configuration, context));
        case "list_files" -> new ToolExecutionResult(listFiles(configuration, context, call.arguments()));
        case "find" -> new ToolExecutionResult(findFiles(configuration, context, call.arguments()));
        case "read_file" -> new ToolExecutionResult(readFile(configuration, context, call.arguments()));
        case "search_files" -> new ToolExecutionResult(searchFiles(configuration, context, call.arguments()));
        case "run_command" -> new ToolExecutionResult(runCommand(configuration, context, call.arguments(), executionSession));
        case "shell_start" -> new ToolExecutionResult(shellStart(configuration, context, call.arguments(), executionSession));
        case "shell_poll" -> new ToolExecutionResult(_instance.shellPoll(configuration, call.arguments()));
        case "shell_stop" -> new ToolExecutionResult(_instance.shellStop(call.arguments()));
        case "shell_save" -> new ToolExecutionResult(_instance.shellSave(configuration, context, call.arguments(), executionSession));
        case "shell_list" -> new ToolExecutionResult(_instance.shellList(configuration, context));
        case "shell_run" -> new ToolExecutionResult(_instance.shellRun(configuration, context, call.arguments(), executionSession));
        case "mvn" -> new ToolExecutionResult(mvn(configuration, context, call.arguments(), executionSession));
        case "write_file" -> writeFileDetailed(configuration, context, call.arguments());
        case "search_replace" -> searchReplaceDetailed(configuration, context, call.arguments());
        case "apply_patch" -> applyPatchDetailed(configuration, context, call.arguments(), executionSession);
        case "git" -> new ToolExecutionResult(git(configuration, context, call.arguments()));
        case "git_status" -> new ToolExecutionResult(gitStatus(configuration, context, call.arguments()));
        case "git_diff" -> new ToolExecutionResult(gitDiff(configuration, context, call.arguments()));
        case "git_add" -> new ToolExecutionResult(gitAdd(configuration, context, call.arguments()));
        case "git_commit" -> new ToolExecutionResult(gitCommit(configuration, context, call.arguments()));
        default -> new ToolExecutionResult("Unknown tool: " + call.name());
        };
    }

    private static String requestActionApprovalIfNeeded(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws InterruptedException {
        if (!"on_request".equals(configuration.toolApprovalPolicy()) || !requiresActionApproval(call.name())) {
            return null;
        }
        if (isShellExecutionTool(call.name()) && !shellCommandPreapprovalRequired(configuration)) {
            return null;
        }
        if (isTemporaryShellCommandApprovalTool(call.name()) && _instance.hasTemporaryShellCommandApproval()) {
            return null;
        }
        if (executionSession == null) {
            return "Tool " + call.name() + " blocked by Nemo approval: approval policy requires user approval.";
        }
        Path root = resolveWorkspaceRoot(configuration, context);
        var request = new ToolApprovalRequest(
                call.name(),
                "tool request",
                actionApprovalSummary(call),
                "request:" + call.name() + ":" + actionApprovalSignature(call),
                true);
        ApprovalResult approval = executionSession.requestApproval(root, request);
        return approval.approved()
                ? null
                : "Tool " + call.name() + " blocked by Nemo approval: user denied the request.";
    }

    private static boolean requiresActionApproval(String toolName) {
        return List.of("run_command", "shell_start", "mvn", "write_file", "search_replace", "apply_patch")
                .contains(toolName);
    }

    private static boolean isShellExecutionTool(String toolName) {
        return List.of("run_command", "shell_start", "shell_run", "mvn").contains(toolName);
    }

    private static boolean shellCommandPreapprovalRequired(Configuration configuration) {
        return !"full_access".equals(configuration.toolPermissionMode())
                && "disabled".equals(configuration.toolOsSandbox());
    }

    private static String actionApprovalSummary(ToolCall call) {
        return switch (call.name()) {
        case "run_command" -> "run command: " + stringArgument(call.arguments(), "command", "");
        case "shell_start" -> "start async shell command: " + stringArgument(call.arguments(), "command", "");
        case "mvn" -> {
            MavenInvocation invocation = mavenInvocation(call.arguments());
            yield "run Maven in " + mavenDisplayDirectory(invocation.directory())
                    + ": mvn " + String.join(" ", invocation.arguments());
        }
        case "write_file" -> "write " + stringArgument(call.arguments(), "content", "").length()
                + " chars to " + stringArgument(call.arguments(), "path", "");
        case "search_replace" -> "replace " + compactArgumentValue(call.arguments().get("search"))
                + " with " + compactArgumentValue(call.arguments().get("replace"))
                + " in " + searchReplaceDisplayPath(call.arguments());
        case "apply_patch" -> "apply patch (" + stringArgument(call.arguments(), "patch", "").length() + " chars)";
        case "git_add" -> "stage changes: " + gitAddSummary(call.arguments());
        case "git_commit" -> "commit changes: " + stringArgument(call.arguments(), "message", "");
        default -> call.name();
        };
    }

    private static String actionApprovalSignature(ToolCall call) {
        return call.name() + ":" + compactArgumentValue(call.arguments());
    }

    static boolean isToolAllowedByPermission(Configuration configuration, String toolName) {
        return permissionBlock(configuration, toolName) == null;
    }

    private static String permissionBlock(Configuration configuration, String toolName) {
        if (!"read_only".equals(configuration.toolPermissionMode())) {
            return null;
        }
        if (isMcpToolName(toolName)) {
            return "Tool " + toolName + " blocked by Nemo permissions: read_only mode does not allow MCP tools. "
                    + "Use :permissions workspace-write to allow configured MCP servers.";
        }
        if (isPluginToolName(toolName)) {
            SwimNemoToolDescriptor descriptor = SwimNemoToolRegistry.findTool(toolName);
            if (descriptor != null && descriptor.availableInReadOnly()) {
                return null;
            }
            return "Tool " + toolName + " blocked by Nemo permissions: read_only mode does not allow plugin tools "
                    + "unless the plugin marks them read-only. Use :permissions workspace-write to allow plugin tools.";
        }
        if (List.of("run_command", "shell_start", "shell_poll", "shell_stop", "shell_save", "shell_list", "shell_run", "mvn", "write_file", "search_replace", "apply_patch", "git_add", "git_commit", "drive_editor")
                .contains(toolName)) {
            return "Tool " + toolName + " blocked by Nemo permissions: read_only mode allows inspection only. "
                    + "Use :permissions workspace-write to allow workspace changes.";
        }
        return null;
    }

    static Path resolveWorkspaceRoot(Configuration configuration, BufferContext context) {
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

    static String webSearch(JsonObject arguments) {
        String query = stringArgument(arguments, "query", "").trim();
        if (query.isBlank()) {
            return "Web search failed: query is blank.";
        }
        int maxResults = Math.max(1, Math.min(_maxWebSearchResults,
                intArgument(arguments, "max_results", _defaultWebSearchMaxResults)));
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("https://duckduckgo.com/html/?q=" + encodedQuery))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SWIM Nemo/1.0")
                .header("Accept", "text/html")
                .GET()
                .build();
        try {
            HttpResponse<String> response = _webSearchHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Web search failed: HTTP " + response.statusCode() + " for query: " + query;
            }
            var results = parseDuckDuckGoResults(response.body(), maxResults);
            if (results.isEmpty()) {
                return "No web search results for: " + query;
            }
            return formatWebSearchResults(query, results);
        } catch (IOException e) {
            return "Web search failed for query: " + query + " (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Web search interrupted for query: " + query;
        } catch (RuntimeException e) {
            return "Web search failed for query: " + query + " (" + e.getMessage() + ")";
        }
    }

    static boolean isMcpToolName(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    static boolean isPluginToolName(String toolName) {
        return toolName != null && toolName.startsWith("plugin__");
    }

    private static String startEditorControl(Configuration configuration, BufferContext context,
            ToolExecutionSession executionSession) throws InterruptedException {
        if (!configuration.toolScreenSnapshot() && !configuration.toolDriveEditor()) {
            return "Tool start_editor_control is disabled in this session.";
        }
        if (executionSession == null) {
            return "start_editor_control blocked by Nemo approval: editor control requires an active Nemo session.";
        }
        Path root = resolveWorkspaceRoot(configuration, context);
        String visibilityBlock = editorControlVisibilityBlock("start_editor_control");
        if (visibilityBlock != null) {
            return visibilityBlock;
        }
        String ownerId = editorControlOwnerId(executionSession);
        String ownerTitle = editorControlOwnerTitle(executionSession);
        String existingBlock = _instance.editorControlLeaseBlock(ownerId);
        if (existingBlock == null) {
            return runOnEditorThread(() -> {
                Window window = Window.getInstance();
                return "editor control already active for this session.\n\n"
                        + (window == null ? "No active editor window." : window.guestScreenSnapshot());
            }, "editor control already active for this session.");
        }
        if (!_instance.editorControlLeaseAvailableFor(ownerId)) {
            return existingBlock;
        }
        ApprovalResult approval = executionSession.requestApproval(root, new ToolApprovalRequest(
                "start_editor_control",
                "editor control session",
                "Allow " + ownerTitle
                        + " to observe the host-filtered editor screen and send sandboxed editor input until finish_editor_control releases the lock. Only one Nemo session can hold editor control at a time.",
                "editor-control-session:" + ownerId + ":" + System.nanoTime(),
                false,
                true));
        if (!approval.approved()) {
            return "Tool start_editor_control blocked by Nemo approval: user denied editor control.";
        }
        String acquireBlock = _instance.acquireEditorControlLease(ownerId, ownerTitle, root);
        if (acquireBlock != null) {
            return acquireBlock;
        }
        return runOnEditorThread(() -> {
            Window window = Window.getInstance();
            if (window == null) {
                return "editor control started for " + ownerTitle + ".\n\nNo active editor window.";
            }
            if (executionSession instanceof ConversationToolExecutionSession conversationSession) {
                _instance.showConversation(conversationSession._conversation);
            }
            return "editor control started for " + ownerTitle + ".\n\n"
                    + window.guestScreenSnapshot();
        }, "editor control started for " + ownerTitle + ".");
    }

    private static String screenSnapshot(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws InterruptedException {
        if (!configuration.toolScreenSnapshot()) {
            return "Tool screen_snapshot is disabled in this session.";
        }
        String leaseBlock = _instance.editorControlLeaseBlock(editorControlOwnerId(executionSession));
        if (leaseBlock != null) {
            return "Tool screen_snapshot blocked by editor control: " + leaseBlock;
        }
        String visibilityBlock = editorControlVisibilityBlock("screen_snapshot");
        if (visibilityBlock != null) {
            return visibilityBlock;
        }
        return runOnEditorThread(() -> {
            Window window = Window.getInstance();
            return window == null ? "No active editor window." : window.guestScreenSnapshot();
        }, "No active editor window.");
    }

    private static String driveEditor(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws InterruptedException {
        if (!configuration.toolDriveEditor()) {
            return "Tool drive_editor is disabled in this session.";
        }
        String input = stringArgument(arguments, "input", "");
        if (input.isEmpty()) {
            return "drive_editor failed: input is required.";
        }
        int maxEvents = Math.max(1, Math.min(500, intArgument(arguments, "max_events", 200)));
        Path root = resolveWorkspaceRoot(configuration, context);
        String leaseBlock = _instance.editorControlLeaseBlock(editorControlOwnerId(executionSession));
        if (leaseBlock != null) {
            return "Tool drive_editor blocked by editor control: " + leaseBlock;
        }
        String visibilityBlock = editorControlVisibilityBlock("drive_editor");
        if (visibilityBlock != null) {
            return visibilityBlock;
        }
        Window.EditorDriveResult result = runOnEditorThread(() -> {
            Window window = Window.getInstance();
            return window == null
                    ? new Window.EditorDriveResult(false, "No active editor window.", 0, "", "")
                    : window.driveEditorInput(input, maxEvents, root, true);
        }, new Window.EditorDriveResult(false, "No active editor window.", 0, "", ""));
        return truncateOutput(configuration, formatEditorDriveResult(result));
    }

    private static String finishEditorControl(ToolExecutionSession executionSession) throws InterruptedException {
        String releaseMessage = _instance.releaseEditorControlLease(editorControlOwnerId(executionSession));
        if (!(executionSession instanceof ConversationToolExecutionSession conversationSession)) {
            return releaseMessage == null
                    ? "finish_editor_control unavailable: no invoking Nemo conversation is active."
                    : releaseMessage;
        }
        Conversation conversation = conversationSession._conversation;
        return runOnEditorThread(() -> {
            Window window = Window.getInstance();
            if (window == null) {
                return "finish_editor_control failed: no active editor window.";
            }
            _instance.showConversation(conversation);
            return (releaseMessage == null ? "No active editor-control session was owned by this request."
                    : releaseMessage)
                    + "\nOpened Nemo chat " + conversation._id + " (" + conversation._title + ").";
        }, "finish_editor_control failed: no active editor window.");
    }

    private static String editorControlOwnerId(ToolExecutionSession executionSession) {
        if (executionSession instanceof ConversationToolExecutionSession conversationSession) {
            return conversationSession._conversation._id + ":" + conversationSession._requestId;
        }
        return executionSession == null
                ? null
                : "tool-session:" + Integer.toHexString(System.identityHashCode(executionSession));
    }

    private static String editorControlOwnerTitle(ToolExecutionSession executionSession) {
        if (executionSession instanceof ConversationToolExecutionSession conversationSession) {
            Conversation conversation = conversationSession._conversation;
            return conversation._id + " | " + conversation._title;
        }
        return executionSession == null ? "unknown Nemo session" : "Nemo tool session";
    }

    private static String editorControlVisibilityBlock(String toolName) throws InterruptedException {
        return runOnEditorThread(() -> {
            Window window = Window.getInstance();
            if (window == null) {
                return null;
            }
            String reason = window.guestScreenSnapshotBlockReason();
            return reason == null ? null : "Tool " + toolName + " blocked by Nemo permissions: " + reason;
        }, null);
    }

    private synchronized boolean editorControlLeaseAvailableFor(String ownerId) {
        return _editorControlLease == null
                || (ownerId != null && _editorControlLease.ownerId().equals(ownerId));
    }

    private synchronized String editorControlLeaseBlock(String ownerId) {
        if (_editorControlLease == null) {
            return "no active editor-control session; call start_editor_control first.";
        }
        if (ownerId != null && _editorControlLease.ownerId().equals(ownerId)) {
            return null;
        }
        return "editor control is locked by " + _editorControlLease.ownerTitle() + ".";
    }

    private synchronized String acquireEditorControlLease(String ownerId, String ownerTitle, Path workspaceRoot) {
        if (ownerId == null) {
            return "Tool start_editor_control blocked by editor control: no invoking Nemo session is active.";
        }
        String block = editorControlLeaseBlock(ownerId);
        if (block == null) {
            return null;
        }
        if (_editorControlLease != null) {
            return "Tool start_editor_control blocked by editor control: " + block;
        }
        _editorControlLease = new EditorControlLease(
                ownerId,
                ownerTitle == null || ownerTitle.isBlank() ? ownerId : ownerTitle,
                workspaceRoot == null ? "" : workspaceRoot.toAbsolutePath().normalize().toString(),
                System.currentTimeMillis());
        return null;
    }

    private synchronized String releaseEditorControlLease(String ownerId) {
        if (_editorControlLease == null || ownerId == null) {
            return null;
        }
        if (!_editorControlLease.ownerId().equals(ownerId)) {
            return "editor control remains locked by " + _editorControlLease.ownerTitle() + ".";
        }
        long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - _editorControlLease.startedAtMillis()) / 1000);
        String message = "editor control finished for " + _editorControlLease.ownerTitle()
                + " after " + elapsedSeconds + "s.";
        _editorControlLease = null;
        return message;
    }

    private synchronized void releaseEditorControlLease(Conversation conversation, long requestId) {
        if (conversation != null) {
            releaseEditorControlLease(conversation._id + ":" + requestId);
        }
    }

    private static String formatEditorDriveResult(Window.EditorDriveResult result) {
        var lines = new ArrayList<String>();
        lines.add("accepted: " + result.accepted());
        lines.add("message: " + result.message());
        lines.add("events_processed: " + result.eventsProcessed());
        if (result.beforeSnapshot() != null && !result.beforeSnapshot().isBlank()) {
            lines.add("before:");
            lines.add(result.beforeSnapshot());
        }
        if (result.afterSnapshot() != null && !result.afterSnapshot().isBlank()) {
            lines.add("after:");
            lines.add(result.afterSnapshot());
        }
        return String.join("\n", lines);
    }

    private static String currentEditorContext(Configuration configuration, BufferContext context)
            throws InterruptedException {
        Window.CurrentEditingSnapshot snapshot = runOnEditorThread(() -> {
            Window window = Window.getInstance();
            return window == null ? null : window.currentEditingSnapshot();
        }, null);
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        Path currentFile = snapshot == null ? context.getBuffer().getPath() : snapshot.currentFile();
        currentFile = currentFile == null ? null : currentFile.toAbsolutePath().normalize();
        Path projectRoot = snapshot == null ? null : snapshot.projectRoot();
        if (projectRoot == null) {
            projectRoot = ProjectPaths.getProjectRootPath(currentFile == null ? workspaceRoot : currentFile);
        }
        projectRoot = projectRoot == null ? workspaceRoot : projectRoot.toAbsolutePath().normalize();
        String workspaceKind = snapshot == null ? "buffer" : snapshot.workspaceKind();

        var lines = new ArrayList<String>();
        lines.add("workspace: " + workspaceKind);
        lines.add("workspace_root: " + workspaceRoot);
        if (currentFile == null) {
            lines.add("current_file: (none)");
        } else {
            lines.add("current_file: " + currentFile);
            String relativeToWorkspace = relativePathIfInside(workspaceRoot, currentFile);
            if (relativeToWorkspace != null) {
                lines.add("current_file_relative_to_workspace: " + relativeToWorkspace);
            }
        }
        if (projectRoot == null) {
            lines.add("project_root: (none)");
        } else {
            lines.add("project_root: " + projectRoot);
            if (currentFile != null) {
                String relativeToProject = relativePathIfInside(projectRoot, currentFile);
                if (relativeToProject != null) {
                    lines.add("current_file_relative_to_project: " + relativeToProject);
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String relativePathIfInside(Path root, Path path) {
        if (root == null || path == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedPath).toString()
                : null;
    }

    private static <T> T runOnEditorThread(Callable<T> callable, T fallback) throws InterruptedException {
        EventThread eventThread = EventThread.getInstance();
        if (!eventThread.isAlive()
                || Thread.currentThread() == eventThread
                || Thread.currentThread().getName().equals("event-thread-worker")) {
            try {
                return callable.call();
            } catch (Exception e) {
                _log.warn("Nemo editor tool failed", e);
                return fallback;
            }
        }
        var latch = new CountDownLatch(1);
        var result = new AtomicReference<T>(fallback);
        eventThread.enqueue(new RunnableEvent(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                _log.warn("Nemo editor tool failed", e);
            } finally {
                latch.countDown();
            }
        }));
        if (!latch.await(5, TimeUnit.SECONDS)) {
            return fallback;
        }
        return result.get();
    }

    private String callMcpTool(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        if (!isMcpAllowed(configuration)) {
            return "MCP tool " + call.name() + " is unavailable in this session.";
        }
        if (!"full_access".equals(configuration.toolPermissionMode())) {
            Path root = resolveWorkspaceRoot(configuration, context);
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    root,
                    new ToolApprovalRequest(
                            call.name(),
                            "MCP tool call",
                            "call configured MCP tool " + call.name() + "\nArguments: " + compactArgumentValue(call.arguments()),
                            "mcp:" + call.name() + ":" + compactArgumentValue(call.arguments()),
                            true),
                    "Tool " + call.name() + " blocked by Nemo approval: MCP tool calls require approval.");
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }
        return _mcpClient.callTool(configuration.mcpServers(), call.name(), call.arguments(),
                configuration.toolMaxOutputChars());
    }

    private String callPluginTool(Configuration configuration, BufferContext context, ToolCall call,
            ToolExecutionSession executionSession) throws InterruptedException {
        SwimNemoToolDescriptor descriptor = SwimNemoToolRegistry.findTool(call.name());
        if (descriptor == null) {
            return "Plugin tool " + call.name() + " is unavailable in this session.";
        }
        if (!"full_access".equals(configuration.toolPermissionMode()) && descriptor.requiresApproval()) {
            Path root = resolveWorkspaceRoot(configuration, context);
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    root,
                    new ToolApprovalRequest(
                            call.name(),
                            "plugin tool call",
                            "call plugin tool " + descriptor.toolName() + " from plugin " + descriptor.pluginId()
                                    + "\nArguments: " + compactArgumentValue(call.arguments()),
                            "plugin:" + call.name() + ":" + compactArgumentValue(call.arguments()),
                            true),
                    "Tool " + call.name() + " blocked by Nemo approval: plugin tool calls require approval.");
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }
        Path root = resolveWorkspaceRoot(configuration, context);
        Path currentPath = context.getBuffer().getPath();
        var invocation = new SwimNemoToolInvocation(
                call.name(),
                descriptor.pluginId(),
                descriptor.toolName(),
                call.arguments().toString(),
                currentPath,
                root);
        try {
            String output = SwimNemoToolRegistry.execute(call.name(), invocation);
            return truncateOutput(configuration, output == null ? "" : output);
        } catch (Exception e) {
            _log.warn("Nemo plugin tool {} failed", call.name(), e);
            String message = e.getMessage();
            return "Plugin tool " + call.name() + " failed" + (message == null || message.isBlank() ? "." : ": " + message);
        }
    }

    private String delegateTask(Configuration configuration, BufferContext context, JsonObject arguments) {
        if (!configuration.toolDelegateTask()) {
            return "Sub-agent delegation is disabled for this request.";
        }
        String task = stringArgument(arguments, "task", "").trim();
        if (task.isBlank()) {
            return "Sub-agent delegation failed: task is blank.";
        }
        String title = stringArgument(arguments, "title", "").trim();
        var focusPaths = stringArrayArgument(arguments, "focus_paths");
        Conversation worker = startDelegatedWorker(configuration, context, title, task, focusPaths);
        return "Started sub-agent worker " + worker._id + " (" + worker._title + "). "
                + "It is running in parallel. Use :workers to watch it or :switch " + worker._id + " to inspect it.";
    }

    private Conversation startDelegatedWorker(Configuration configuration, BufferContext context, String title,
            String task, List<String> focusPaths) {
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        Conversation worker;
        synchronized (this) {
            ensureSessionsLoaded();
            worker = createConversation(workspaceRoot, delegatedTitle(title, task));
            bindConversation(worker, context, configuration.forSubAgent());
            persistSessions();
        }
        submit(worker, subAgentPrompt(title, task, focusPaths));
        return worker;
    }

    private static String delegatedTitle(String title, String task) {
        if (!title.isBlank()) {
            return title;
        }
        String oneLineTask = oneLine(task);
        if (oneLineTask.isBlank()) {
            return "Delegated task";
        }
        return oneLineTask.length() <= 48 ? oneLineTask : oneLineTask.substring(0, 45) + "...";
    }

    private static String subAgentPrompt(String title, String task, List<String> focusPaths) {
        var builder = new StringBuilder();
        builder.append("You are a Nemo sub-agent delegated by another Nemo request.\n")
                .append("Work independently on the task below using workspace tools as needed.\n")
                .append("Do not ask the user questions. If blocked, report exactly what blocked you and what you checked.\n")
                .append("Return a concise report with findings, changes made, tests run, and remaining risks.\n")
                .append("Do not delegate further; sub-agent delegation is disabled inside this request.\n");
        if (!title.isBlank()) {
            builder.append("\nTitle: ").append(title).append("\n");
        }
        builder.append("\nTask:\n").append(task).append("\n");
        if (!focusPaths.isEmpty()) {
            builder.append("\nFocus paths:\n");
            for (String path : focusPaths) {
                builder.append("- ").append(path).append("\n");
            }
        }
        return builder.toString();
    }

    private String workerStatus(Configuration configuration, BufferContext context, JsonObject arguments) {
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String sessionId = stringArgument(arguments, "session_id", "").trim();
        synchronized (this) {
            ensureSessionsLoaded();
            if (!sessionId.isBlank()) {
                Conversation worker = findConversation(sessionId, workspaceRoot);
                return worker == null
                        ? "Unknown worker/session: " + sessionId
                        : formatWorkerStatus(worker);
            }
            return formatConversations(workspaceRoot);
        }
    }

    private String readWorker(Configuration configuration, BufferContext context, JsonObject arguments) {
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String sessionId = stringArgument(arguments, "session_id", "").trim();
        if (sessionId.isBlank()) {
            return "Worker read failed: session_id is required.";
        }
        int maxTurns = Math.max(1, intArgument(arguments, "max_turns", 20));
        int maxOutputChars = Math.max(1, intArgument(arguments, "max_output_chars", configuration.toolMaxOutputChars()));
        WorkerSnapshot snapshot = workerSnapshot(workspaceRoot, sessionId);
        if (snapshot == null) {
            return "Unknown worker/session: " + sessionId;
        }
        return truncateText(formatWorkerSnapshot(snapshot, maxTurns), maxOutputChars);
    }

    private String joinWorker(Configuration configuration, BufferContext context, JsonObject arguments)
            throws InterruptedException {
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String sessionId = stringArgument(arguments, "session_id", "").trim();
        if (sessionId.isBlank()) {
            return "Worker join failed: session_id is required.";
        }
        int timeoutSeconds = Math.max(0, Math.min(300, intArgument(arguments, "timeout_seconds", 30)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (workerPending(workspaceRoot, sessionId) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        return readWorker(configuration, context, arguments);
    }

    private String messageWorker(Configuration configuration, BufferContext context, JsonObject arguments) {
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String sessionId = stringArgument(arguments, "session_id", "").trim();
        String message = stringArgument(arguments, "message", "").trim();
        if (sessionId.isBlank()) {
            return "Worker message failed: session_id is required.";
        }
        if (message.isBlank()) {
            return "Worker message failed: message is blank.";
        }
        return sendWorkerMessage(workspaceRoot, sessionId, message);
    }

    private synchronized String sendWorkerMessage(Path workspaceRoot, String sessionId, String message) {
        ensureSessionsLoaded();
        Conversation worker = findConversation(sessionId, workspaceRoot);
        if (worker == null) {
            return "Unknown worker/session: " + sessionId;
        }
        if (worker._pending) {
            queueUserMessage(worker, message);
            return "Queued message for " + worker._id + " (" + worker._title + ").";
        }
        submit(worker, message);
        return "Sent message to " + worker._id + " (" + worker._title + ").";
    }

    private synchronized WorkerSnapshot workerSnapshot(Path workspaceRoot, String sessionId) {
        ensureSessionsLoaded();
        Conversation worker = findConversation(sessionId, workspaceRoot);
        if (worker == null) {
            return null;
        }
        return new WorkerSnapshot(
                worker._id,
                worker._title,
                worker._pending,
                elapsedSeconds(worker),
                worker._contextUsagePercent,
                pendingApprovalIdsForConversation(worker),
                List.copyOf(worker._turns));
    }

    private synchronized boolean workerPending(Path workspaceRoot, String sessionId) {
        ensureSessionsLoaded();
        Conversation worker = findConversation(sessionId, workspaceRoot);
        return worker != null && worker._pending;
    }

    private synchronized String formatWorkerStatus(Conversation worker) {
        String status = worker._pending ? "running " + elapsedSeconds(worker) + "s" : "idle";
        var approvals = pendingApprovalIdsForConversation(worker);
        String approvalStatus = approvals.isEmpty() ? "" : " | waiting for approval " + String.join(",", approvals);
        return worker._id + " | " + worker._title + " | " + status + approvalStatus + " | turns=" + worker._turns.size();
    }

    private static String formatWorkerSnapshot(WorkerSnapshot snapshot, int maxTurns) {
        var lines = new ArrayList<String>();
        String status = snapshot.pending() ? "running " + snapshot.elapsedSeconds() + "s" : "idle";
        lines.add("Worker " + snapshot.id() + " | " + snapshot.title() + " | " + status);
        if (!snapshot.pendingApprovalIds().isEmpty()) {
            lines.add("pending approvals: " + String.join(",", snapshot.pendingApprovalIds()));
        }
        if (snapshot.contextUsagePercent() != null) {
            lines.add("context: " + snapshot.contextUsagePercent() + "%");
        }
        lines.add("Transcript:");
        int start = Math.max(0, snapshot.turns().size() - maxTurns);
        for (int i = start; i < snapshot.turns().size(); i++) {
            ChatTurn turn = snapshot.turns().get(i);
            lines.add(turn.speaker() + "> " + turn.text());
        }
        return String.join("\n", lines);
    }

    private synchronized List<String> pendingApprovalIdsForConversation(Conversation conversation) {
        var ids = new ArrayList<String>();
        for (PendingApproval pending : _pendingApprovals.values()) {
            if (pending._conversationId.equals(conversation._id)) {
                ids.add(pending._id);
            }
        }
        return ids;
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }

    static List<WebSearchResult> parseDuckDuckGoResults(String html, int maxResults) {
        if (html == null || html.isBlank() || maxResults <= 0) {
            return List.of();
        }
        var anchors = new ArrayList<DuckDuckGoResultAnchor>();
        var matcher = _duckDuckGoResultLinkPattern.matcher(html);
        while (matcher.find()) {
            anchors.add(new DuckDuckGoResultAnchor(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(1),
                    matcher.group(2)));
        }
        var results = new ArrayList<WebSearchResult>();
        for (int i = 0; i < anchors.size() && results.size() < maxResults; i++) {
            var anchor = anchors.get(i);
            String title = htmlToText(anchor.titleHtml());
            String url = decodeDuckDuckGoUrl(anchor.href());
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            int blockEnd = i + 1 < anchors.size() ? anchors.get(i + 1).start() : html.length();
            String block = html.substring(anchor.end(), blockEnd);
            String snippet = "";
            var snippetMatcher = _duckDuckGoSnippetPattern.matcher(block);
            if (snippetMatcher.find()) {
                snippet = htmlToText(snippetMatcher.group(1));
            }
            results.add(new WebSearchResult(title, url, snippet));
        }
        return results;
    }

    private static String formatWebSearchResults(String query, List<WebSearchResult> results) {
        var builder = new StringBuilder("Web search results for: ").append(query);
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            builder.append("\n\n").append(i + 1).append(". ").append(result.title())
                    .append("\n   ").append(result.url());
            if (!result.snippet().isBlank()) {
                builder.append("\n   ").append(result.snippet());
            }
        }
        return builder.toString();
    }

    private static String decodeDuckDuckGoUrl(String href) {
        String decodedHref = decodeHtmlEntities(href == null ? "" : href.trim());
        if (decodedHref.isBlank()) {
            return "";
        }
        int uddgIndex = decodedHref.indexOf("uddg=");
        if (uddgIndex >= 0) {
            int valueStart = uddgIndex + "uddg=".length();
            int valueEnd = decodedHref.indexOf('&', valueStart);
            String encoded = valueEnd >= 0
                    ? decodedHref.substring(valueStart, valueEnd)
                    : decodedHref.substring(valueStart);
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        if (decodedHref.startsWith("//")) {
            return "https:" + decodedHref;
        }
        return decodedHref;
    }

    private static String htmlToText(String html) {
        String withoutTags = _htmlTagPattern.matcher(html == null ? "" : html).replaceAll(" ");
        return decodeHtmlEntities(withoutTags).replaceAll("\\s+", " ").trim();
    }

    private static String decodeHtmlEntities(String value) {
        String text = value == null ? "" : value;
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        var matcher = _htmlEntityPattern.matcher(text);
        var buffer = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            int radix = raw.startsWith("x") || raw.startsWith("X") ? 16 : 10;
            String digits = radix == 16 ? raw.substring(1) : raw;
            try {
                matcher.appendReplacement(buffer,
                        java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(digits, radix))));
            } catch (NumberFormatException e) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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

    private static Path resolvePathInsideWorkspace(Path workspaceRoot, String rawDirectory, String rawPath) throws IOException {
        if (rawDirectory == null || rawDirectory.isBlank()) {
            return resolvePathInsideWorkspace(workspaceRoot, rawPath);
        }
        Path base = requireDirectory(resolvePathInsideWorkspace(workspaceRoot, rawDirectory), rawDirectory);
        if (rawPath == null || rawPath.isBlank()) {
            return base;
        }
        Path requested = Path.of(rawPath);
        Path path = requested.isAbsolute()
                ? requested.normalize()
                : base.resolve(requested).normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw new IOException("Path escapes workspace root: " + rawPath);
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
        String rawDirectory = stringArgument(arguments, "directory", "");
        String rawPath = rawDirectory.isBlank() ? stringArgument(arguments, "path", "") : rawDirectory;
        Path start = resolvePathInsideWorkspace(root, rawPath);
        int maxResults = intArgument(arguments, "max_results", configuration.toolMaxResults());
        var files = new ArrayList<String>();
        for (Path path : regularFilesUnder(start)) {
            files.add(root.relativize(path).toString());
            if (files.size() >= maxResults) {
                break;
            }
        }
        if (files.isEmpty()) {
            return "(no files)";
        }
        return truncateOutput(configuration, String.join("\n", files));
    }

    private static String findFiles(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawDirectory = stringArgument(arguments, "directory", "");
        Path start = requireDirectory(resolvePathInsideWorkspace(root, rawDirectory), rawDirectory);
        String query = stringArgument(arguments, "query", "").trim();
        if (query.isBlank()) {
            return "Find failed: query is blank.";
        }
        int maxResults = intArgument(arguments, "max_results", configuration.toolMaxResults());
        var matches = new ArrayList<String>();
        PathMatcher globMatcher = findGlobMatcher(query);
        String normalizedQuery = normalizeFindText(query);
        for (Path path : regularFilesUnder(start)) {
            if (matches.size() >= maxResults) {
                break;
            }
            String relative = root.relativize(path).toString();
            String fileName = path.getFileName() == null ? relative : path.getFileName().toString();
            if (findFileMatches(relative, fileName, normalizedQuery, globMatcher)) {
                matches.add(relative);
            }
        }
        if (matches.isEmpty()) {
            return "(no files found)";
        }
        return truncateOutput(configuration, String.join("\n", matches));
    }

    private static List<Path> regularFilesUnder(Path start) throws IOException {
        var files = new ArrayList<Path>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return isGitInternal(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !isGitInternal(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static boolean isGitInternal(Path path) {
        Path name = path.getFileName();
        return (name != null && ".git".equals(name.toString()))
                || path.toString().contains(File.separator + ".git" + File.separator);
    }

    private static PathMatcher findGlobMatcher(String query) throws IOException {
        if (!isGlobQuery(query)) {
            return null;
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + normalizeFindText(query));
        } catch (RuntimeException e) {
            throw new IOException("Invalid find glob: " + e.getMessage(), e);
        }
    }

    private static boolean isGlobQuery(String query) {
        return query.indexOf('*') >= 0
                || query.indexOf('?') >= 0
                || query.indexOf('[') >= 0
                || query.indexOf('{') >= 0;
    }

    private static boolean findFileMatches(String relativePath, String fileName, String normalizedQuery,
            PathMatcher globMatcher) {
        String relative = normalizeFindText(relativePath);
        String name = normalizeFindText(fileName);
        if (globMatcher != null) {
            return globMatcher.matches(Path.of(relative)) || globMatcher.matches(Path.of(name));
        }
        return relative.contains(normalizedQuery) || name.contains(normalizedQuery);
    }

    private static String normalizeFindText(String value) {
        return value == null ? "" : value.replace(File.separatorChar, '/').toLowerCase(java.util.Locale.ROOT);
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
        String rawDirectory = stringArgument(arguments, "directory", "");
        String rawPath = rawDirectory.isBlank() ? stringArgument(arguments, "path", "") : rawDirectory;
        Path start = resolvePathInsideWorkspace(root, rawPath);
        String query = stringArgument(arguments, "query", "");
        int maxResults = intArgument(arguments, "max_results", configuration.toolMaxResults());
        var matches = new ArrayList<String>();
        for (Path path : regularFilesUnder(start)) {
            if (matches.size() >= maxResults) {
                break;
            }
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
        if (matches.isEmpty()) {
            return "(no matches)";
        }
        return truncateOutput(configuration, String.join("\n", matches));
    }

    private static String runCommand(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawCwd = stringArgument(arguments, "cwd", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawCwd), rawCwd);
        String command = stringArgument(arguments, "command", "");
        String policyBlock = shellCommandPreapprovalRequired(configuration)
                ? commandPolicyBlock(configuration, command)
                : null;
        if (policyBlock != null) {
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    root,
                    new ToolApprovalRequest(
                            "run_command",
                            "command policy escalation",
                            "run blocked command: " + command + "\nReason: " + policyBlock,
                            "policy:run_command:" + cwd.toAbsolutePath().normalize() + ":" + command + ":" + policyBlock,
                            true),
                    "Tool run_command blocked by Nemo policy: " + policyBlock);
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }
        String mavenHint = mavenAlsoMakeHint(command);
        if (mavenHint != null) {
            return mavenHint;
        }

        return runShellCommand(configuration, root, cwd, command, executionSession);
    }

    private static String shellStart(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawCwd = stringArgument(arguments, "cwd", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawCwd), rawCwd);
        String command = stringArgument(arguments, "command", "");
        String policyBlock = shellCommandPreapprovalRequired(configuration)
                ? commandPolicyBlock(configuration, command)
                : null;
        if (policyBlock != null) {
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    root,
                    new ToolApprovalRequest(
                            "shell_start",
                            "command policy escalation",
                            "start blocked async shell command: " + command + "\nReason: " + policyBlock,
                            "policy:shell_start:" + cwd.toAbsolutePath().normalize() + ":" + command + ":" + policyBlock,
                            true),
                    "Tool shell_start blocked by Nemo policy: " + policyBlock);
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }
        String mavenHint = mavenAlsoMakeHint(command);
        if (mavenHint != null) {
            return mavenHint;
        }

        return startAsyncShellProcess(configuration, root, cwd, command, executionSession);
    }

    private static String startAsyncShellProcess(Configuration configuration, Path root, Path cwd, String command,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        String sandboxBlock = osSandboxBlock(configuration, root, command, executionSession);
        if (sandboxBlock != null) {
            return sandboxBlock;
        }

        ShellInvocation invocation = shellInvocation(configuration, root, cwd, command);
        var processBuilder = new ProcessBuilder(invocation.command())
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        var process = processBuilder.start();
        AsyncShell shell = _instance.registerAsyncShell(root, cwd, command, invocation.sandboxed(), process);
        return formatShellStarted(shell.snapshot(configuration.toolMaxOutputChars()));
    }

    private synchronized AsyncShell registerAsyncShell(Path workspaceRoot, Path cwd, String command, boolean sandboxed,
            Process process) {
        String id = "shell-" + _nextAsyncShellNumber++;
        var shell = new AsyncShell(id, workspaceRoot.toAbsolutePath().normalize(), cwd.toAbsolutePath().normalize(),
                command, sandboxed, process);
        _asyncShells.put(id, shell);
        startAsyncShellThreads(shell);
        return shell;
    }

    private static void startAsyncShellThreads(AsyncShell shell) {
        Thread stdout = new Thread(() -> readAsyncShellStream(shell._process.getInputStream(), shell::appendStdout),
                "swim-nemo-" + shell._id + "-stdout");
        Thread stderr = new Thread(() -> readAsyncShellStream(shell._process.getErrorStream(), shell::appendStderr),
                "swim-nemo-" + shell._id + "-stderr");
        Thread waiter = new Thread(() -> {
            try {
                shell.finish(shell._process.waitFor());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "swim-nemo-" + shell._id);
        stdout.setDaemon(true);
        stderr.setDaemon(true);
        waiter.setDaemon(true);
        stdout.start();
        stderr.start();
        waiter.start();
    }

    private static void readAsyncShellStream(InputStream input, java.util.function.Consumer<String> append) {
        byte[] buffer = new byte[4096];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    append.accept(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            // Process stream readers are best-effort; shell_poll still reports process status.
        }
    }

    private String shellPoll(Configuration configuration, JsonObject arguments) {
        String id = stringArgument(arguments, "shell_id", "").trim();
        if (id.isBlank()) {
            return "shell_poll failed: shell_id is required.";
        }
        AsyncShell shell;
        synchronized (this) {
            shell = _asyncShells.get(id);
        }
        if (shell == null) {
            return "Unknown shell: " + id;
        }
        int maxOutputChars = Math.max(0, intArgument(arguments, "max_output_chars", configuration.toolMaxOutputChars()));
        ShellSnapshot snapshot = shell.snapshot(maxOutputChars);
        if (snapshot.finished() && booleanArgument(arguments, "forget_if_finished", false)) {
            synchronized (this) {
                _asyncShells.remove(id);
            }
        }
        return truncateOutput(configuration, formatShellSnapshot(snapshot));
    }

    private String shellStop(JsonObject arguments) {
        String id = stringArgument(arguments, "shell_id", "").trim();
        if (id.isBlank()) {
            return "shell_stop failed: shell_id is required.";
        }
        AsyncShell shell;
        synchronized (this) {
            shell = _asyncShells.get(id);
        }
        if (shell == null) {
            return "Unknown shell: " + id;
        }
        if (!shell.snapshot(0).finished()) {
            shell._process.destroy();
            try {
                if (!shell._process.waitFor(2, TimeUnit.SECONDS)) {
                    shell._process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shell._process.destroyForcibly();
            }
        }
        return formatShellSnapshot(shell.snapshot(_defaultMaxOutputChars));
    }

    private String shellSave(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String name = normalizeShellLineName(stringArgument(arguments, "name", ""));
        String command = stringArgument(arguments, "command", "").trim();
        if (command.isBlank()) {
            return "shell_save failed: command is required.";
        }
        String rawCwd = stringArgument(arguments, "cwd", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawCwd), rawCwd);
        String cwdRelative = root.equals(cwd) ? "" : root.relativize(cwd).toString();

        if (shellCommandPreapprovalRequired(configuration)) {
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    root,
                    new ToolApprovalRequest(
                            "shell_save",
                            "approved shell line",
                            "save approved shell line '" + name + "'"
                                    + "\nCwd: " + (cwdRelative.isBlank() ? "." : cwdRelative)
                                    + "\nCommand: " + command,
                            "shell-save:" + root + ":" + name + ":" + cwdRelative + ":" + command,
                            false),
                    "Tool shell_save blocked by Nemo approval: saving an approved shell line requires user approval.");
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }

        synchronized (this) {
            ensureApprovalsLoaded();
            String normalizedRoot = root.toString();
            _approvedShellLines.removeIf(line -> line.workspaceRoot().equals(normalizedRoot)
                    && line.name().equals(name));
            _approvedShellLines.add(new ApprovedShellLine(normalizedRoot, name, cwdRelative, command,
                    System.currentTimeMillis()));
            persistApprovals();
        }
        return "saved shell line " + name + " in " + (cwdRelative.isBlank() ? "." : cwdRelative)
                + ": " + command;
    }

    private String shellList(Configuration configuration, BufferContext context) {
        Path root = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        List<ApprovedShellLine> lines;
        synchronized (this) {
            ensureApprovalsLoaded();
            lines = _approvedShellLines.stream()
                    .filter(line -> line.workspaceRoot().equals(root.toString()))
                    .sorted(Comparator.comparing(ApprovedShellLine::name))
                    .toList();
        }
        if (lines.isEmpty()) {
            return "(no saved shell lines)";
        }
        return lines.stream()
                .map(line -> line.name() + " | cwd=" + (line.cwd().isBlank() ? "." : line.cwd())
                        + " | " + line.command())
                .collect(Collectors.joining("\n"));
    }

    private String shellRun(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession)
            throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        String name = normalizeShellLineName(stringArgument(arguments, "name", ""));
        ApprovedShellLine shellLine = approvedShellLine(root, name);
        if (shellLine == null) {
            return "Unknown shell line: " + name;
        }
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, shellLine.cwd()), shellLine.cwd());
        Configuration trustedConfiguration = configuration.withToolCommandPolicy("trusted");
        if (booleanArgument(arguments, "async", false)) {
            return startAsyncShellProcess(trustedConfiguration, root, cwd, shellLine.command(), executionSession);
        }
        return runShellCommand(trustedConfiguration, root, cwd, shellLine.command(), executionSession);
    }

    private synchronized ApprovedShellLine approvedShellLine(Path workspaceRoot, String name) {
        ensureApprovalsLoaded();
        String normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString();
        for (ApprovedShellLine line : _approvedShellLines) {
            if (line.workspaceRoot().equals(normalizedRoot) && line.name().equals(name)) {
                return line;
            }
        }
        return null;
    }

    private static String normalizeShellLineName(String rawName) throws IOException {
        String name = rawName == null ? "" : rawName.trim();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("Invalid shell line name: use 1-64 letters, numbers, dots, underscores, or hyphens, starting with a letter or number.");
        }
        return name;
    }

    private synchronized void stopAsyncShellsForTests() {
        for (AsyncShell shell : _asyncShells.values()) {
            if (!shell.snapshot(0).finished()) {
                shell._process.destroyForcibly();
            }
        }
    }

    private static String formatShellStarted(ShellSnapshot snapshot) {
        return String.join("\n",
                "shell_id: " + snapshot.id(),
                "status: running",
                "cwd: " + snapshot.cwd(),
                "sandboxed: " + snapshot.sandboxed(),
                "command: " + snapshot.command());
    }

    private static String formatShellSnapshot(ShellSnapshot snapshot) {
        long end = snapshot.finished() ? snapshot.finishedAtMillis() : System.currentTimeMillis();
        long elapsedSeconds = Math.max(0, (end - snapshot.startedAtMillis()) / 1000);
        var lines = new ArrayList<String>();
        lines.add("shell_id: " + snapshot.id());
        lines.add("status: " + (snapshot.finished() ? "done" : "running"));
        lines.add("exit_code: " + (snapshot.exitCode() == null ? "running" : snapshot.exitCode()));
        lines.add("elapsed_seconds: " + elapsedSeconds);
        lines.add("cwd: " + snapshot.cwd());
        lines.add("sandboxed: " + snapshot.sandboxed());
        lines.add("stdout" + (snapshot.stdoutTruncated() ? " (truncated)" : "") + ":");
        lines.add(snapshot.stdout());
        lines.add("stderr" + (snapshot.stderrTruncated() ? " (truncated)" : "") + ":");
        lines.add(snapshot.stderr());
        return String.join("\n", lines);
    }

    private static String mvn(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        MavenInvocation invocation = mavenInvocation(arguments);
        String rawDirectory = invocation.directory();
        if (rawDirectory.isBlank()) {
            throw new IOException("Maven project path is required as the first argument; use \".\" for the workspace root.");
        }
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawDirectory), rawDirectory);
        List<String> args = invocation.arguments();
        for (String arg : args) {
            if (arg.indexOf('\0') >= 0) {
                throw new IOException("Maven argument contains NUL byte");
            }
        }
        String executable = mavenExecutable(root, cwd);
        String command = Stream.concat(Stream.of(executable), args.stream())
                .map(NemoClient::shellQuote)
                .collect(Collectors.joining(" "));
        String mavenHint = mavenAlsoMakeHint("mvn " + String.join(" ", args));
        if (mavenHint != null) {
            return mavenHint;
        }
        return runShellCommand(configuration, root, cwd, command, executionSession);
    }

    private static List<String> mavenArguments(JsonObject arguments) {
        return mavenInvocation(arguments).arguments();
    }

    private static MavenInvocation mavenInvocation(JsonObject arguments) {
        List<String> values = rawMavenArguments(arguments);
        String rawDirectory = stringArgument(arguments, "directory", stringArgument(arguments, "cwd", ""));
        if (!rawDirectory.isBlank()) {
            return new MavenInvocation(rawDirectory, values);
        }
        if (values.isEmpty()) {
            return new MavenInvocation("", List.of());
        }
        String directory = values.get(0);
        if (directory.startsWith("-")) {
            return new MavenInvocation("", values);
        }
        return new MavenInvocation(directory, values.subList(1, values.size()));
    }

    private record MavenInvocation(String directory, List<String> arguments) {
    }

    private static String mavenDisplayDirectory(String directory) {
        return directory == null || directory.isBlank() ? "." : directory;
    }

    private static List<String> rawMavenArguments(JsonObject arguments) {
        var values = new ArrayList<String>();
        if (arguments.has("arguments") && arguments.get("arguments").isJsonArray()) {
            values.addAll(stringArrayArgument(arguments, "arguments"));
        } else if (arguments.has("args") && arguments.get("args").isJsonArray()) {
            values.addAll(stringArrayArgument(arguments, "args"));
        } else if (arguments.has("arguments") && arguments.get("arguments").isJsonPrimitive()) {
            String text = arguments.get("arguments").getAsString().trim();
            if (!text.isBlank()) {
                values.addAll(List.of(text.split("\\s+")));
            }
        } else if (arguments.has("args") && arguments.get("args").isJsonPrimitive()) {
            String text = arguments.get("args").getAsString().trim();
            if (!text.isBlank()) {
                values.addAll(List.of(text.split("\\s+")));
            }
        }
        return values;
    }

    private static String mavenExecutable(Path root, Path cwd) {
        Path cwdWrapper = cwd.resolve("mvnw");
        if (Files.isRegularFile(cwdWrapper) && Files.isExecutable(cwdWrapper)) {
            return cwdWrapper.toAbsolutePath().normalize().toString();
        }
        Path rootWrapper = root.resolve("mvnw");
        if (Files.isRegularFile(rootWrapper) && Files.isExecutable(rootWrapper)) {
            return rootWrapper.toAbsolutePath().normalize().toString();
        }
        return "mvn";
    }

    private static String requestEscalationApprovalIfNeeded(Configuration configuration,
            ToolExecutionSession executionSession, Path workspaceRoot, ToolApprovalRequest request, String deniedMessage)
            throws InterruptedException {
        if (isTemporaryShellCommandApprovalTool(request.toolName()) && _instance.hasTemporaryShellCommandApproval()) {
            return null;
        }
        if (!approvalPolicyAllowsEscalationPrompt(configuration)) {
            return deniedMessage;
        }
        if (executionSession == null) {
            return deniedMessage + " Approval is required but no approval session is active.";
        }
        ApprovalResult approval = executionSession.requestApproval(workspaceRoot, request);
        return approval.approved() ? null : approvalDeniedMessage(deniedMessage, request);
    }

    private static String approvalDeniedMessage(String deniedMessage, ToolApprovalRequest request) {
        String message = "Nemo approval denied: user denied " + request.toolName() + " (" + request.reason() + ").";
        if (deniedMessage == null || deniedMessage.isBlank()) {
            return message;
        }
        if (deniedMessage.toLowerCase().contains("user denied")) {
            return deniedMessage;
        }
        return deniedMessage + (deniedMessage.endsWith("\n") ? "" : "\n") + message;
    }

    private static boolean approvalPolicyAllowsEscalationPrompt(Configuration configuration) {
        return "on_escalation".equals(configuration.toolApprovalPolicy())
                || "on_request".equals(configuration.toolApprovalPolicy());
    }

    private static String commandPolicyBlock(Configuration configuration, String command) {
        if ("trusted".equals(configuration.toolCommandPolicy()) || "full_access".equals(configuration.toolPermissionMode())) {
            return null;
        }
        if (command == null || command.isBlank()) {
            return "command is empty";
        }
        String trimmed = command.trim();
        String disallowedSyntax = disallowedShellSyntax(trimmed);
        if (disallowedSyntax != null) {
            return "restricted mode does not allow shell " + disallowedSyntax;
        }

        String executable = firstShellWord(trimmed);
        if (executable.isBlank()) {
            return "command is empty";
        }
        if (executable.startsWith("/") || executable.startsWith("~")) {
            return "restricted mode does not allow absolute executable paths";
        }
        if (List.of("cd", "sudo", "su", "rm", "chmod", "chown", "curl", "wget", "ssh", "scp", "rsync")
                .contains(executable)) {
            return "restricted mode does not allow " + executable;
        }
        return null;
    }

    private static String disallowedShellSyntax(String command) {
        if (command.contains("\n") || command.contains("\r")) {
            return "newlines";
        }
        for (String token : List.of("&&", "||", "$(", "`", "<(", ">(")) {
            if (command.contains(token)) {
                return token;
            }
        }
        for (int i = 0; i < command.length(); ++i) {
            char c = command.charAt(i);
            if (c == ';' || c == '|' || c == '>' || c == '<') {
                return Character.toString(c);
            }
        }
        return null;
    }

    private static String firstShellWord(String command) {
        var word = new StringBuilder();
        for (int i = 0; i < command.length(); ++i) {
            char c = command.charAt(i);
            if (Character.isWhitespace(c)) {
                break;
            }
            word.append(c);
        }
        return word.toString();
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

    private static String writeFile(Configuration configuration, BufferContext context, JsonObject arguments)
            throws IOException, InterruptedException {
        return writeFileDetailed(configuration, context, arguments).output();
    }

    private static ToolExecutionResult writeFileDetailed(Configuration configuration, BufferContext context, JsonObject arguments)
            throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        Path path = resolvePathInsideWorkspace(root, stringArgument(arguments, "path", ""));
        String content = stringArgument(arguments, "content", "");
        boolean existed = Files.exists(path);
        String before = readFileForDisplayDiff(context, path);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(path, content, StandardCharsets.UTF_8);
        boolean refreshedOpenBuffer = refreshOpenBuffersForPath(path, content);
        if (!refreshedOpenBuffer && isCurrentBufferPath(context, path)) {
            writeOpenBuffer(context, content);
        }

        String output = truncateOutput(configuration, "wrote " + content.length() + " chars to " + root.relativize(path));
        String displayPatch = unifiedDiff(root.relativize(path), before, content, existed, true);
        return new ToolExecutionResult(output, truncateOutput(configuration, displayPatch));
    }

    private static ToolExecutionResult searchReplaceDetailed(Configuration configuration, BufferContext context,
            JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawDirectory = stringArgument(arguments, "directory", "");
        String rawPath = stringArgument(arguments, "path", "");
        if (rawPath.isBlank()) {
            throw new IOException("path is required");
        }
        Path path = resolvePathInsideWorkspace(root, rawDirectory, rawPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a file: " + path);
        }

        String search = stringArgument(arguments, "search", "");
        if (search.isEmpty()) {
            throw new IOException("search is required");
        }
        String replace = stringArgument(arguments, "replace", "");
        boolean regex = booleanArgument(arguments, "regex", false);
        boolean replaceAll = booleanArgument(arguments, "replace_all", true);
        Pattern pattern;
        try {
            pattern = regex ? Pattern.compile(search) : Pattern.compile(Pattern.quote(search));
        } catch (RuntimeException e) {
            throw new IOException("Invalid search pattern: " + e.getMessage(), e);
        }

        String before = readFileForDisplayDiff(context, path);
        Matcher countMatcher = pattern.matcher(before);
        int matches = 0;
        while (countMatcher.find()) {
            matches++;
        }
        Path relativePath = root.relativize(path);
        if (matches == 0) {
            return new ToolExecutionResult("no matches in " + relativePath);
        }

        String replacement = regex ? replace : Matcher.quoteReplacement(replace);
        String after;
        try {
            Matcher replaceMatcher = pattern.matcher(before);
            after = replaceAll ? replaceMatcher.replaceAll(replacement) : replaceMatcher.replaceFirst(replacement);
        } catch (RuntimeException e) {
            throw new IOException("Invalid replacement: " + e.getMessage(), e);
        }
        if (before.equals(after)) {
            return new ToolExecutionResult("matched " + matches + " in " + relativePath + " but replacement made no changes");
        }

        Files.writeString(path, after, StandardCharsets.UTF_8);
        boolean refreshedOpenBuffer = refreshOpenBuffersForPath(path, after);
        if (!refreshedOpenBuffer && isCurrentBufferPath(context, path)) {
            writeOpenBuffer(context, after);
        }

        int replaced = replaceAll ? matches : 1;
        String count = replaceAll ? String.valueOf(replaced) : replaced + " of " + matches;
        String noun = replaceAll && replaced == 1 ? "match" : "matches";
        String output = truncateOutput(configuration, "replaced " + count + " " + noun + " in " + relativePath);
        String displayPatch = unifiedDiff(relativePath, before, after, true, true);
        return new ToolExecutionResult(output, truncateOutput(configuration, displayPatch));
    }

    private static String applyPatch(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        return applyPatchDetailed(configuration, context, arguments, executionSession).output();
    }

    private static ToolExecutionResult applyPatchDetailed(Configuration configuration, BufferContext context, JsonObject arguments,
            ToolExecutionSession executionSession) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String patch = stringArgument(arguments, "patch", "");
        if (patch.isBlank()) {
            throw new IOException("patch is required");
        }
        Path marker = Files.createTempFile(root, "nemo-patch-", ".diff");
        Files.writeString(marker, patch, StandardCharsets.UTF_8);
        try {
            String output = runShellCommand(configuration, root, root,
                    "git apply --whitespace=nowarn " + shellQuote(root.relativize(marker).toString()),
                    executionSession);
            String displayPatch = firstOutputLine(output).contains("exit_code: 0")
                    ? truncateOutput(configuration, patch)
                    : "";
            return new ToolExecutionResult(output, displayPatch);
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    private static String readFileForDisplayDiff(BufferContext context, Path path) throws IOException {
        if (isCurrentBufferPath(context, path)) {
            return context.getBuffer().getString();
        }
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    static String unifiedDiff(Path relativePath, String before, String after, boolean existedBefore, boolean existsAfter) {
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        if (before.equals(after)) {
            return "";
        }
        String displayPath = relativePath == null ? "file" : relativePath.toString();
        RawText oldText = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText newText = new RawText(after.getBytes(StandardCharsets.UTF_8));
        var edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, oldText, newText);
        if (edits.isEmpty()) {
            return "";
        }

        var builder = new StringBuilder();
        builder.append("diff --git a/").append(displayPath).append(" b/").append(displayPath).append('\n');
        builder.append(existedBefore ? "--- a/" + displayPath : "--- /dev/null").append('\n');
        builder.append(existsAfter ? "+++ b/" + displayPath : "+++ /dev/null").append('\n');
        int editIndex = 0;
        while (editIndex < edits.size()) {
            var hunkEdits = new ArrayList<Edit>();
            Edit first = edits.get(editIndex++);
            hunkEdits.add(first);
            int startA = Math.max(0, first.getBeginA() - _diffContextLines);
            int startB = Math.max(0, first.getBeginB() - _diffContextLines);
            int endA = Math.min(oldText.size(), first.getEndA() + _diffContextLines);
            int endB = Math.min(newText.size(), first.getEndB() + _diffContextLines);
            while (editIndex < edits.size()) {
                Edit next = edits.get(editIndex);
                if (next.getBeginA() > endA + _diffContextLines
                        || next.getBeginB() > endB + _diffContextLines) {
                    break;
                }
                hunkEdits.add(next);
                endA = Math.min(oldText.size(), next.getEndA() + _diffContextLines);
                endB = Math.min(newText.size(), next.getEndB() + _diffContextLines);
                editIndex++;
            }
            appendUnifiedHunk(builder, oldText, newText, hunkEdits, startA, endA, startB, endB);
        }
        return builder.toString().stripTrailing();
    }

    private static void appendUnifiedHunk(StringBuilder builder, RawText oldText, RawText newText, List<Edit> edits,
            int startA, int endA, int startB, int endB) {
        builder.append("@@ -")
                .append(diffRange(startA, endA - startA))
                .append(" +")
                .append(diffRange(startB, endB - startB))
                .append(" @@\n");
        int oldLine = startA;
        int newLine = startB;
        for (Edit edit : edits) {
            while (oldLine < edit.getBeginA() && newLine < edit.getBeginB()) {
                appendDiffLine(builder, ' ', oldText.getString(oldLine));
                oldLine++;
                newLine++;
            }
            while (oldLine < edit.getEndA()) {
                appendDiffLine(builder, '-', oldText.getString(oldLine));
                oldLine++;
            }
            while (newLine < edit.getEndB()) {
                appendDiffLine(builder, '+', newText.getString(newLine));
                newLine++;
            }
        }
        while (oldLine < endA && newLine < endB) {
            appendDiffLine(builder, ' ', oldText.getString(oldLine));
            oldLine++;
            newLine++;
        }
    }

    private static String diffRange(int start, int count) {
        int lineNumber = count == 0 ? start : start + 1;
        return lineNumber + "," + count;
    }

    private static void appendDiffLine(StringBuilder builder, char prefix, String line) {
        builder.append(prefix).append(line == null ? "" : line).append('\n');
    }

    private static String git(Configuration configuration, BufferContext context, JsonObject arguments)
            throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawDirectory = stringArgument(arguments, "directory", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawDirectory), rawDirectory);
        List<String> args = gitArguments(arguments);
        String block = gitPermissionBlock(configuration, args);
        if (block != null) {
            return block;
        }
        return runGitProcess(configuration, root, cwd, args);
    }

    private static String gitStatus(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawPath = stringArgument(arguments, "path", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawPath), rawPath);
        return runGitProcess(configuration, root, cwd, List.of("status", "--short", "--branch"));
    }

    private static String gitDiff(Configuration configuration, BufferContext context, JsonObject arguments) throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String rawPath = stringArgument(arguments, "path", "");
        Path cwd = requireDirectory(resolvePathInsideWorkspace(root, rawPath), rawPath);
        return runGitProcess(configuration, root, cwd, List.of("diff", "--", "."));
    }

    private static String gitAdd(Configuration configuration, BufferContext context, JsonObject arguments)
            throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        var paths = gitAddPathspecs(root, arguments);
        if (paths.isEmpty()) {
            paths = List.of(".");
        }
        var args = new ArrayList<String>();
        args.add("add");
        args.add("--");
        args.addAll(paths);
        return runGitProcess(configuration, root, root, args);
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
        String pathspec = root.equals(path) ? "." : root.relativize(path).toString();
        return pathspec.replace(File.separatorChar, '/');
    }

    private static String gitCommit(Configuration configuration, BufferContext context, JsonObject arguments)
            throws IOException, InterruptedException {
        Path root = resolveWorkspaceRoot(configuration, context);
        String message = stringArgument(arguments, "message", "");
        if (message.isBlank()) {
            throw new IOException("message is required");
        }
        return runGitProcess(configuration, root, root, List.of("commit", "-m", message));
    }

    private static List<String> gitArguments(JsonObject arguments) {
        return stringArrayArgument(arguments, "arguments");
    }

    private static String gitPermissionBlock(Configuration configuration, List<String> args) throws IOException {
        if (args.isEmpty()) {
            throw new IOException("git arguments are required");
        }
        for (String arg : args) {
            if (arg.indexOf('\0') >= 0) {
                throw new IOException("Git argument contains NUL byte");
            }
        }
        String subcommand = args.get(0);
        if (subcommand.startsWith("-")) {
            return "Tool git blocked by Nemo permissions: global git options are not allowed; use the directory argument for cwd.";
        }
        if (!gitSubcommandEnabled(configuration, subcommand)) {
            return "Tool git blocked by Nemo configuration: git " + subcommand + " is disabled.";
        }
        if ("read_only".equals(configuration.toolPermissionMode()) && gitMutatesWorkspace(subcommand)) {
            return "Tool git blocked by Nemo permissions: read_only mode allows inspection only. "
                    + "Use :permissions workspace-write to allow workspace changes.";
        }
        if (!gitAllowedSubcommand(subcommand)) {
            return "Tool git blocked by Nemo permissions: unsupported git subcommand '" + subcommand + "'. "
                    + "Allowed subcommands are status, diff, log, show, rev-parse, ls-files, add, and commit.";
        }
        return null;
    }

    private static boolean gitSubcommandEnabled(Configuration configuration, String subcommand) {
        return switch (subcommand) {
        case "status" -> configuration.toolGitStatus();
        case "diff" -> configuration.toolGitDiff();
        case "add" -> configuration.toolGitAdd();
        case "commit" -> configuration.toolGitCommit();
        case "log", "show", "rev-parse", "ls-files" -> configuration.toolGitStatus() || configuration.toolGitDiff();
        default -> configuration.toolGitEnabled();
        };
    }

    private static boolean gitAllowedSubcommand(String subcommand) {
        return List.of("status", "diff", "log", "show", "rev-parse", "ls-files", "add", "commit")
                .contains(subcommand);
    }

    private static boolean gitMutatesWorkspace(String subcommand) {
        return List.of("add", "commit").contains(subcommand);
    }

    private static String runGitProcess(Configuration configuration, Path workspaceRoot, Path cwd, List<String> args)
            throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(args);
        var processBuilder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
        processBuilder.environment().put("GIT_PAGER", "cat");
        Path parent = workspaceRoot.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            processBuilder.environment().put("GIT_CEILING_DIRECTORIES", parent.toString());
        }
        if ("read_only".equals(configuration.toolPermissionMode())) {
            processBuilder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        }
        var process = processBuilder.start();
        try {
            if (!process.waitFor(configuration.toolCommandTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellResult("timeout", "", "git exceeded " + configuration.toolCommandTimeoutSeconds() + " seconds", false)
                        .format(configuration);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ShellResult(String.valueOf(process.exitValue()), stdout, stderr, false)
                .format(configuration);
    }

    private static String runShellCommand(Configuration configuration, Path workspaceRoot, Path cwd, String command,
            ToolExecutionSession executionSession)
            throws IOException, InterruptedException {
        String sandboxBlock = osSandboxBlock(configuration, workspaceRoot, command, executionSession);
        if (sandboxBlock != null) {
            return sandboxBlock;
        }

        ShellResult result = runShellProcess(configuration, workspaceRoot, cwd, command);
        if (shouldRequestSandboxDenialApproval(configuration, result, executionSession)) {
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    workspaceRoot,
                    new ToolApprovalRequest(
                            "run_command",
                            "OS sandbox blocked filesystem write",
                            "rerun without the OS filesystem sandbox after this sandbox denial: " + command
                                    + "\nStderr: " + oneLine(result.stderr()),
                            "sandbox-denied:" + workspaceRoot.toAbsolutePath().normalize() + ":" + command
                                    + ":" + compactRawBody(result.stderr()),
                            true),
                    result.format(configuration));
            if (approvalBlock == null) {
                result = runShellProcess(configuration.withToolOsSandbox("disabled"), workspaceRoot, cwd, command);
            } else {
                return approvalBlock;
            }
        }
        return result.format(configuration);
    }

    private static ShellResult runShellProcess(Configuration configuration, Path workspaceRoot, Path cwd, String command)
            throws IOException, InterruptedException {
        ShellInvocation invocation = shellInvocation(configuration, workspaceRoot, cwd, command);
        var processBuilder = new ProcessBuilder(invocation.command())
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        if ("read_only".equals(configuration.toolPermissionMode())) {
            processBuilder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        }
        var process = processBuilder.start();
        try {
            if (!process.waitFor(configuration.toolCommandTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellResult("timeout", "", "command exceeded " + configuration.toolCommandTimeoutSeconds() + " seconds",
                        invocation.sandboxed());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ShellResult(String.valueOf(process.exitValue()), stdout, stderr, invocation.sandboxed());
    }

    private record ShellInvocation(List<String> command, boolean sandboxed) {
    }

    private record ShellResult(String exitCode, String stdout, String stderr, boolean sandboxed) {
        private String format(Configuration configuration) {
            return truncateOutput(configuration, String.join("\n",
                    "exit_code: " + exitCode,
                    "stdout:",
                    stdout,
                    "stderr:",
                    stderr));
        }
    }

    private static ShellInvocation shellInvocation(Configuration configuration, Path workspaceRoot, Path cwd, String command)
            throws IOException {
        if ("full_access".equals(configuration.toolPermissionMode()) || "disabled".equals(configuration.toolOsSandbox())) {
            return new ShellInvocation(List.of("zsh", "-lc", command), false);
        }
        OsSandboxBackend backend = osSandboxBackend();
        return switch (backend) {
        case MACOS_SANDBOX_EXEC -> {
            String profile = macOsSandboxProfile(configuration, workspaceRoot);
            yield new ShellInvocation(List.of("/usr/bin/sandbox-exec", "-p", profile, "zsh", "-lc", command), true);
        }
        case LINUX_BUBBLEWRAP -> new ShellInvocation(linuxBubblewrapCommand(configuration, workspaceRoot, cwd, command), true);
        case NONE -> new ShellInvocation(List.of("zsh", "-lc", command), false);
        };
    }

    private static boolean shouldRequestSandboxDenialApproval(Configuration configuration, ShellResult result,
            ToolExecutionSession executionSession) {
        return executionSession != null
                && result.sandboxed()
                && !"required".equals(configuration.toolOsSandbox())
                && !"0".equals(result.exitCode())
                && isSandboxWriteDenial(result.stderr());
    }

    private static boolean isSandboxWriteDenial(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return false;
        }
        String lower = stderr.toLowerCase();
        return lower.contains("operation not permitted")
                || lower.contains("sandbox")
                || lower.contains("read-only file system");
    }

    private static String osSandboxBlock(Configuration configuration, Path workspaceRoot, String command,
            ToolExecutionSession executionSession) throws InterruptedException {
        if ("full_access".equals(configuration.toolPermissionMode()) || "disabled".equals(configuration.toolOsSandbox())) {
            return null;
        }
        OsSandboxBackend backend = osSandboxBackend();
        if ("required".equals(configuration.toolOsSandbox()) && backend == OsSandboxBackend.NONE) {
            return String.join("\n",
                    "exit_code: sandbox_unavailable",
                    "stdout:",
                    "",
                    "stderr:",
                    "Nemo OS sandbox is required, but no supported OS sandbox backend is available or usable.");
        }
        if ("auto".equals(configuration.toolOsSandbox()) && backend == OsSandboxBackend.NONE) {
            String unavailableMessage = String.join("\n",
                    "exit_code: sandbox_unavailable",
                    "stdout:",
                    "",
                    "stderr:",
                    "Nemo OS sandbox is unavailable and the unsandboxed command was not approved.");
            if (executionSession == null) {
                return unavailableMessage;
            }
            String approvalBlock = requestEscalationApprovalIfNeeded(
                    configuration,
                    executionSession,
                    workspaceRoot,
                    new ToolApprovalRequest(
                            "run_command",
                            "OS sandbox unavailable",
                            "run without an OS filesystem sandbox: " + command,
                            "sandbox-unavailable:" + workspaceRoot.toAbsolutePath().normalize() + ":" + command,
                            true),
                    unavailableMessage);
            if (approvalBlock != null) {
                return approvalBlock;
            }
        }
        return null;
    }

    static boolean isMacOsSandboxAvailable() {
        return osSandboxBackend() == OsSandboxBackend.MACOS_SANDBOX_EXEC;
    }

    static String osSandboxBackendName() {
        return osSandboxBackend().name().toLowerCase();
    }

    private static OsSandboxBackend osSandboxBackend() {
        String override = System.getProperty(_sandboxAvailabilityOverrideProperty);
        if (override != null && !override.isBlank()) {
            if (!Boolean.parseBoolean(override.trim())) {
                return OsSandboxBackend.NONE;
            }
        }
        OsSandboxBackend cached = _osSandboxBackend.get();
        if (cached != null) {
            return cached;
        }
        OsSandboxBackend backend = detectOsSandboxBackend();
        _osSandboxBackend.compareAndSet(null, backend);
        return _osSandboxBackend.get();
    }

    private static OsSandboxBackend detectOsSandboxBackend() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") && probeMacOsSandbox()) {
            return OsSandboxBackend.MACOS_SANDBOX_EXEC;
        }
        if (osName.contains("linux") && probeLinuxBubblewrap()) {
            return OsSandboxBackend.LINUX_BUBBLEWRAP;
        }
        return OsSandboxBackend.NONE;
    }

    private static boolean probeMacOsSandbox() {
        Path sandboxExec = Path.of("/usr/bin/sandbox-exec");
        if (!Files.isExecutable(sandboxExec)) {
            return false;
        }
        try {
            var process = new ProcessBuilder(sandboxExec.toString(), "-p", "(version 1)\n(allow default)", "/usr/bin/true")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean probeLinuxBubblewrap() {
        Path bwrap = findExecutable("bwrap");
        if (bwrap == null) {
            return false;
        }
        try {
            var process = new ProcessBuilder(
                    bwrap.toString(),
                    "--ro-bind", "/", "/",
                    "/usr/bin/true")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> linuxBubblewrapCommand(Configuration configuration, Path workspaceRoot, Path cwd, String command)
            throws IOException {
        Path bwrap = findExecutable("bwrap");
        if (bwrap == null) {
            return List.of("zsh", "-lc", command);
        }
        var invocation = new ArrayList<String>();
        invocation.add(bwrap.toString());
        invocation.add("--die-with-parent");
        invocation.add("--ro-bind");
        invocation.add("/");
        invocation.add("/");
        invocation.add("--dev-bind");
        invocation.add("/dev");
        invocation.add("/dev");
        invocation.add("--proc");
        invocation.add("/proc");
        invocation.add("--tmpfs");
        invocation.add("/tmp");
        if ("workspace_write".equals(configuration.toolPermissionMode())) {
            for (Path root : sandboxWritableRoots(workspaceRoot)) {
                invocation.add("--bind");
                invocation.add(root.toString());
                invocation.add(root.toString());
            }
        }
        invocation.add("--chdir");
        invocation.add(cwd.toAbsolutePath().normalize().toString());
        invocation.add("zsh");
        invocation.add("-lc");
        invocation.add(command);
        return invocation;
    }

    private static Path findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static void resetMacOsSandboxAvailabilityForTests() {
        _osSandboxBackend.set(null);
    }

    static String macOsSandboxProfile(Configuration configuration, Path workspaceRoot) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("(version 1)");
        lines.add("(allow default)");
        lines.add("(deny file-write*)");
        lines.add("(allow file-write* (literal \"/dev/null\"))");
        if ("workspace_write".equals(configuration.toolPermissionMode())) {
            for (Path root : sandboxWritableRoots(workspaceRoot)) {
                lines.add("(allow file-write* (subpath " + sandboxString(root.toString()) + "))");
            }
        }
        return String.join("\n", lines);
    }

    private static List<Path> sandboxWritableRoots(Path workspaceRoot) throws IOException {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        var roots = new ArrayList<Path>();
        roots.add(normalized);
        try {
            Path real = normalized.toRealPath();
            if (!real.equals(normalized)) {
                roots.add(real);
            }
        } catch (IOException e) {
            if (!Files.exists(normalized)) {
                throw e;
            }
        }
        return roots;
    }

    private static String sandboxString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\''") + "'";
    }

    private static boolean isCurrentBufferPath(BufferContext context, Path path) {
        Path currentPath = context.getBuffer().getPath();
        return currentPath != null
                && currentPath.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
    }

    private static boolean refreshOpenBuffersForPath(Path path, String content) throws InterruptedException {
        return runOnEditorThread(() -> {
            Window window = Window.getInstance();
            return window != null && window.refreshOpenBuffersForPath(path, content);
        }, false);
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

    private static boolean booleanArgument(JsonObject arguments, String name, boolean fallback) {
        return arguments.has(name) ? arguments.get(name).getAsBoolean() : fallback;
    }

    private static List<String> stringArrayArgument(JsonObject arguments, String name) {
        if (!arguments.has(name) || !arguments.get(name).isJsonArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (var element : arguments.getAsJsonArray(name)) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
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
                            ChatTurn turn = new ChatTurn(
                                    turnObject.get("speaker").getAsString(),
                                    turnObject.get("text").getAsString(),
                                    !turnObject.has("include_in_prompt")
                                            || turnObject.get("include_in_prompt").getAsBoolean());
                            conversation._turns.add(turn);
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

    private synchronized void ensureApprovalsLoaded() {
        if (_approvalsLoaded) {
            return;
        }
        _approvalsLoaded = true;
        _approvalRules.clear();
        _approvedShellLines.clear();
        _nextApprovalRuleNumber = 1;

        Path approvalsPath = getApprovalsPath();
        if (!Files.isRegularFile(approvalsPath)) {
            return;
        }

        try {
            JsonObject root = parseJsonObject(Files.readString(approvalsPath, StandardCharsets.UTF_8));
            if (root.has("next_rule_number")) {
                _nextApprovalRuleNumber = Math.max(1L, root.get("next_rule_number").getAsLong());
            }
            JsonArray rules = root.getAsJsonArray("rules");
            long highestRuleNumber = 0;
            if (rules != null) {
                for (JsonElement element : rules) {
                    JsonObject rule = element.getAsJsonObject();
                    String id = rule.get("id").getAsString();
                    _approvalRules.add(new ApprovalRule(
                            id,
                            rule.get("workspace_root").getAsString(),
                            rule.get("tool_name").getAsString(),
                            rule.get("signature").getAsString(),
                            rule.has("created_at_millis")
                                    ? rule.get("created_at_millis").getAsLong()
                                    : System.currentTimeMillis()));
                    highestRuleNumber = Math.max(highestRuleNumber, approvalRuleNumber(id));
                }
            }
            _nextApprovalRuleNumber = Math.max(_nextApprovalRuleNumber, highestRuleNumber + 1);
            JsonArray shellLines = root.getAsJsonArray("shell_lines");
            if (shellLines != null) {
                for (JsonElement element : shellLines) {
                    JsonObject shellLine = element.getAsJsonObject();
                    _approvedShellLines.add(new ApprovedShellLine(
                            shellLine.get("workspace_root").getAsString(),
                            shellLine.get("name").getAsString(),
                            shellLine.has("cwd") ? shellLine.get("cwd").getAsString() : "",
                            shellLine.get("command").getAsString(),
                            shellLine.has("created_at_millis")
                                    ? shellLine.get("created_at_millis").getAsLong()
                                    : System.currentTimeMillis()));
                }
            }
        } catch (Exception e) {
            _log.error("Unable to load Nemo approvals from {}", approvalsPath, e);
            _approvalRules.clear();
            _approvedShellLines.clear();
            _nextApprovalRuleNumber = 1;
        }
    }

    private synchronized void persistApprovals() {
        var root = new JsonObject();
        root.addProperty("next_rule_number", _nextApprovalRuleNumber);
        var rules = new JsonArray();
        for (ApprovalRule rule : _approvalRules) {
            var object = new JsonObject();
            object.addProperty("id", rule.id());
            object.addProperty("workspace_root", rule.workspaceRoot());
            object.addProperty("tool_name", rule.toolName());
            object.addProperty("signature", rule.signature());
            object.addProperty("created_at_millis", rule.createdAtMillis());
            rules.add(object);
        }
        root.add("rules", rules);
        var shellLines = new JsonArray();
        for (ApprovedShellLine shellLine : _approvedShellLines) {
            var object = new JsonObject();
            object.addProperty("workspace_root", shellLine.workspaceRoot());
            object.addProperty("name", shellLine.name());
            object.addProperty("cwd", shellLine.cwd());
            object.addProperty("command", shellLine.command());
            object.addProperty("created_at_millis", shellLine.createdAtMillis());
            shellLines.add(object);
        }
        root.add("shell_lines", shellLines);

        Path approvalsPath = getApprovalsPath();
        try {
            Files.createDirectories(approvalsPath.getParent());
            Path tempPath = Files.createTempFile(approvalsPath.getParent(), "approvals-", ".json.tmp");
            Files.writeString(tempPath, _gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, approvalsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempPath, approvalsPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            _log.error("Unable to persist Nemo approvals to {}", approvalsPath, e);
        }
    }

    private static long approvalRuleNumber(String id) {
        int separator = id.lastIndexOf('-');
        if (separator < 0 || separator + 1 >= id.length()) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(separator + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ApprovalResult requestToolApproval(Conversation conversation, long requestId, Path workspaceRoot,
            ToolApprovalRequest request) throws InterruptedException {
        String normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString();
        PendingApproval pending;
        synchronized (this) {
            ensureApprovalsLoaded();
            if (isTemporaryShellCommandApprovalTool(request.toolName()) && hasTemporaryShellCommandApprovalLocked()) {
                return new ApprovalResult(true, false);
            }
            if (hasApprovalRuleLocked(normalizedRoot, request)) {
                return new ApprovalResult(true, true);
            }
            if (conversation._activeRequestId != requestId || !conversation._pending) {
                return new ApprovalResult(false, false);
            }
            String id = "approval-" + _nextApprovalNumber++;
            pending = new PendingApproval(id, normalizedRoot, conversation._id, requestId, request);
            _pendingApprovals.put(id, pending);
        }

        boolean hostPromptVisible = request.hostOnly() && presentHostApprovalPrompt(pending);
        if (!hostPromptVisible) {
            appendApprovalPrompt(conversation, pending);
        }
        try {
            pending._latch.await();
        } finally {
            synchronized (this) {
                _pendingApprovals.remove(pending._id);
            }
            if (request.hostOnly()) {
                refreshHostApprovalOverlay(normalizedRoot);
            }
        }

        if (pending._approved && pending._persist && request.persistable()) {
            addApprovalRule(normalizedRoot, request);
        }
        return new ApprovalResult(pending._approved, pending._persist);
    }

    private static boolean isTemporaryShellCommandApprovalTool(String toolName) {
        return "run_command".equals(toolName) || "shell_start".equals(toolName) || "mvn".equals(toolName);
    }

    private synchronized boolean hasTemporaryShellCommandApproval() {
        return hasTemporaryShellCommandApprovalLocked();
    }

    private boolean hasTemporaryShellCommandApprovalLocked() {
        return temporaryShellCommandApprovalRemainingMillisLocked() > 0;
    }

    private long temporaryShellCommandApprovalRemainingMillisLocked() {
        long remaining = _temporaryShellCommandApprovalExpiresAtMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            _temporaryShellCommandApprovalExpiresAtMillis = 0;
            return 0;
        }
        return remaining;
    }

    private synchronized List<PendingApproval> grantTemporaryShellCommandApproval() {
        _temporaryShellCommandApprovalExpiresAtMillis = System.currentTimeMillis() + _temporaryShellCommandApprovalMillis;
        var approved = new ArrayList<PendingApproval>();
        var iterator = _pendingApprovals.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingApproval pending = iterator.next().getValue();
            if (!isTemporaryShellCommandApprovalTool(pending._request.toolName()) || pending._request.hostOnly()) {
                continue;
            }
            pending.resolve(true, false);
            approved.add(pending);
            iterator.remove();
        }
        return approved;
    }

    private synchronized boolean hasApprovalRuleLocked(String workspaceRoot, ToolApprovalRequest request) {
        for (ApprovalRule rule : _approvalRules) {
            if (rule.workspaceRoot().equals(workspaceRoot)
                    && rule.toolName().equals(request.toolName())
                    && rule.signature().equals(request.signature())) {
                return true;
            }
        }
        return false;
    }

    private synchronized void addApprovalRule(String workspaceRoot, ToolApprovalRequest request) {
        ensureApprovalsLoaded();
        if (hasApprovalRuleLocked(workspaceRoot, request)) {
            return;
        }
        _approvalRules.add(new ApprovalRule(
                "rule-" + _nextApprovalRuleNumber++,
                workspaceRoot,
                request.toolName(),
                request.signature(),
                System.currentTimeMillis()));
        persistApprovals();
    }

    private boolean presentHostApprovalPrompt(PendingApproval pending) {
        if (Window.getInstance() == null) {
            return false;
        }
        refreshHostApprovalOverlay(pending._workspaceRoot);
        return true;
    }

    private void refreshHostApprovalOverlay(String normalizedRoot) {
        Runnable refresh = () -> {
            Window window = Window.getInstance();
            if (window == null) {
                return;
            }
            List<HostApprovalOverlayView.Entry> entries = hostApprovalEntries(normalizedRoot);
            if (entries.isEmpty()) {
                window.hideHostApprovalOverlay();
                return;
            }
            window.showHostApprovalOverlay(entries, this::handleHostApprovalDecision);
        };
        EventThread eventThread = EventThread.getInstance();
        if (eventThread.isAlive()
                && Thread.currentThread() != eventThread
                && !Thread.currentThread().getName().equals("event-thread-worker")) {
            eventThread.enqueue(new RunnableEvent(refresh));
        } else {
            refresh.run();
        }
    }

    private synchronized List<HostApprovalOverlayView.Entry> hostApprovalEntries(String normalizedRoot) {
        var entries = new ArrayList<HostApprovalOverlayView.Entry>();
        for (PendingApproval pending : _pendingApprovals.values()) {
            if (!pending._workspaceRoot.equals(normalizedRoot) || !pending._request.hostOnly()) {
                continue;
            }
            Conversation owner = _conversations.get(pending._conversationId);
            String title = owner == null ? pending._id : owner._id + " | " + owner._title;
            entries.add(new HostApprovalOverlayView.Entry(
                    pending._id,
                    title,
                    pending._request.toolName(),
                    pending._request.reason(),
                    pending._request.summary(),
                    pending._request.persistable()));
        }
        return entries;
    }

    private void handleHostApprovalDecision(HostApprovalOverlayView.Decision decision) {
        PendingApproval pending;
        synchronized (this) {
            pending = _pendingApprovals.get(decision.id());
            if (pending == null || !pending._request.hostOnly()) {
                return;
            }
            pending.resolve(decision.approved(), decision.persist());
            _pendingApprovals.remove(decision.id());
        }
        Conversation owner = ownerConversation(pending);
        if (owner != null) {
            appendTurn(owner, new ChatTurn("approval",
                    (decision.approved() ? "Host approved " : "Host denied ") + pending._id + ".",
                    !decision.approved()));
        }
        refreshHostApprovalOverlay(pending._workspaceRoot);
    }

    private void appendApprovalPrompt(Conversation conversation, PendingApproval pending) {
        Runnable append = () -> {
            String prompt = formatApprovalPrompt(conversation, pending);
            appendTurn(conversation, new ChatTurn("approval", prompt, false));
            Conversation visibleConversation = visibleApprovalConversation(conversation);
            if (visibleConversation != null && visibleConversation != conversation) {
                appendTurn(visibleConversation, new ChatTurn("approval", prompt, false));
            }
            openApprovalMenuIfVisible(conversation);
            if (visibleConversation != null && visibleConversation != conversation) {
                openApprovalMenuIfVisible(visibleConversation);
            }
        };
        EventThread eventThread = EventThread.getInstance();
        if (eventThread.isAlive() && Thread.currentThread() != eventThread) {
            eventThread.enqueue(new RunnableEvent(append));
        } else {
            append.run();
        }
    }

    private synchronized Conversation visibleApprovalConversation(Conversation target) {
        if (_activeSessionId != null) {
            Conversation active = _conversations.get(_activeSessionId);
            if (active != null && active._workspaceRoot.equals(target._workspaceRoot) && isPanelVisible(active)) {
                return active;
            }
        }
        return isPanelVisible(target) ? target : null;
    }

    private static void openApprovalMenuIfVisible(Conversation conversation) {
        if (isPanelVisible(conversation)) {
            conversation._panelView.openCommandInputIfEmpty();
        }
    }

    private static String formatApprovalPrompt(Conversation conversation, PendingApproval pending) {
        var lines = new ArrayList<String>();
        lines.add("Approval required: " + pending._request.reason());
        lines.add("id: " + pending._id);
        lines.add("session: " + conversation._id + " | " + conversation._title);
        lines.add("tool: " + pending._request.toolName());
        lines.add("workspace: " + pending._workspaceRoot);
        lines.add(pending._request.summary());
        boolean commandApproval = isTemporaryShellCommandApprovalTool(pending._request.toolName());
        String options;
        if (commandApproval && pending._request.persistable()) {
            options = "approve once, approve shell commands for 10 minutes, approve always, or deny";
        } else if (commandApproval) {
            options = "approve once, approve shell commands for 10 minutes, or deny";
        } else {
            options = pending._request.persistable() ? "approve once, approve always, or deny" : "approve once or deny";
        }
        lines.add("Approval options open in the menu. Use arrows and Enter to choose " + options + ".");
        return String.join("\n", lines);
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
        return ensureConversation(context, configuration, false);
    }

    private synchronized Conversation ensureConversation(BufferContext context, Configuration configuration, boolean workspaceMode) {
        ensureSessionsLoaded();
        Path workspaceRoot = resolveWorkspaceRoot(configuration, context).toAbsolutePath().normalize();
        Conversation conversation = currentVisibleConversation();
        if (conversation != null && conversation._workspaceRoot.equals(workspaceRoot)) {
            bindConversation(conversation, context, configuration);
            if (workspaceMode) {
                showConversationWorkspace(conversation);
            }
            return conversation;
        }

        conversation = preferredConversationForWorkspace(workspaceRoot);
        if (conversation == null) {
            conversation = createConversation(workspaceRoot, "");
        }

        bindConversation(conversation, context, configuration);
        if (workspaceMode) {
            showConversationWorkspace(conversation);
        } else {
            showConversation(conversation);
        }
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
            conversation._panelView.activatePrompt();
            persistSessions();
            return;
        }

        if (window.isShowingPanel()) {
            window.hidePanel();
        }

        if (conversation._panelView == null) {
            conversation._panelView = createPanelView(conversation);
        }
        window.showPanel(conversation._panelView);
        replayConversationIntoVisiblePanel(conversation);
        _activeSessionId = conversation._id;
        _workspaceSessionIds.put(conversation._workspaceRoot.toString(), conversation._id);
        persistSessions();
    }

    private synchronized void showConversationWorkspace(Conversation conversation) {
        var window = Window.getInstance();
        if (window == null) {
            throw new IllegalStateException("No active window");
        }
        if (conversation._panelView == null) {
            conversation._panelView = createPanelView(conversation);
        }
        if (!window.showNemoWorkspace(conversation._panelView)) {
            showConversation(conversation);
            return;
        }
        replayConversationIntoVisiblePanel(conversation);
        _activeSessionId = conversation._id;
        _workspaceSessionIds.put(conversation._workspaceRoot.toString(), conversation._id);
        persistSessions();
    }

    private ChatPanelView createPanelView(Conversation conversation) {
        return new ChatPanelView(org.fisk.swim.ui.Rect.create(0, 0, 0, 0),
                formatPanelTitle(conversation),
                message -> submit(conversation, message),
                command -> handleCommand(conversation, command),
                ignored -> {},
                text -> nemoCommandMenuState(conversation, text));
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
        conversation._panelView.activatePrompt();
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
        if (question.equals("")) {
            return;
        }

        if (conversation._pending) {
            queueUserMessage(conversation, question);
            return;
        }

        appendTurn(conversation, new ChatTurn("me", question));
        startRequest(conversation, List.of());
    }

    private synchronized void startRequest(Conversation conversation, List<ChatTurn> extraPromptTurns) {
        if (conversation._configuration.requiresApiKey() && conversation._configuration.apiKey().isBlank()) {
            appendAssistantNote(conversation, "Set api_key in " + getConfigPath() + " to use :nemo");
            return;
        }

        conversation._queuedUserTurns.clear();
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
        promptTurns.addAll(extraPromptTurns);
        var requestConfiguration = conversation._configuration;
        var requestContext = conversation._context;
        var executionSession = new ConversationToolExecutionSession(conversation, requestId);
        var worker = new Thread(() -> {
            try {
                ResponseResult response = request(requestConfiguration, requestContext, promptTurns, executionSession);
                EventThread.getInstance().enqueue(
                        new RunnableEvent(() -> handleResponse(conversation, requestId, response)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                _log.error("Nemo request failed", e);
                EventThread.getInstance().enqueue(new RunnableEvent(
                        () -> handleFailure(conversation, requestId, "Nemo failed: " + exceptionSummary(e))));
            }
        }, "swim-nemo-" + conversation._id);
        worker.setDaemon(true);
        conversation._worker = worker;
        worker.start();
    }

    private synchronized void queueUserMessage(Conversation conversation, String message) {
        var turn = new ChatTurn("me", message);
        conversation._queuedUserTurns.add(turn);
        appendTurn(conversation, turn);
    }

    private static String exceptionSummary(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return e.getClass().getSimpleName();
    }

    private synchronized List<ChatTurn> drainQueuedUserTurns(Conversation conversation, long requestId) {
        if (conversation._activeRequestId != requestId || !conversation._pending || conversation._queuedUserTurns.isEmpty()) {
            return List.of();
        }
        var turns = List.copyOf(conversation._queuedUserTurns);
        conversation._queuedUserTurns.clear();
        return turns;
    }

    private boolean reportToolTrace(Conversation conversation, long requestId, ToolTrace trace) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> handleToolTrace(conversation, requestId, trace)));
        return true;
    }

    private static final List<CommandSpec> NEMO_COMMAND_SPECS = List.of(
            new CommandSpec("abort", List.of(), "[conversation-id|all]", "stop the current or selected worker"),
            new CommandSpec("conversations", List.of("chats"), "", "list Nemo conversations for this workspace"),
            new CommandSpec("workers", List.of(), "", "list active Nemo workers"),
            new CommandSpec("new", List.of(), "[title]", "create a new Nemo conversation"),
            new CommandSpec("switch", List.of(), "<conversation-id>", "switch to another Nemo conversation"),
            new CommandSpec("rename", List.of(), "<title>", "rename the current Nemo conversation"),
            new CommandSpec("clear", List.of(), "", "clear the current Nemo conversation"),
            new CommandSpec("reset", List.of(), "[conversation-id]", "clear a Nemo conversation without deleting it"),
            new CommandSpec("delete", List.of(), "[conversation-id]", "delete a Nemo conversation"),
            new CommandSpec("permissions", List.of(), "[read-only|workspace-write|full-access]", "show or change Nemo tool permissions"),
            new CommandSpec("mcp", List.of(), "", "list configured MCP servers and exposed tools"),
            new CommandSpec("tell", List.of(), "<conversation-id> <message>", "send a message to a worker without switching"),
            new CommandSpec("approve", List.of(), "<approval-id> [always|shell-10m]", "approve a pending Nemo tool request"),
            new CommandSpec("deny", List.of(), "<approval-id>", "deny a pending Nemo tool request"),
            new CommandSpec("approvals", List.of(), "", "list pending and saved Nemo approvals"),
            new CommandSpec("unapprove", List.of(), "<rule-id|all>", "remove saved Nemo approval rules"),
            new CommandSpec("swim-help", List.of(), "[topic]", "read SWIM editor help chapters"),
            new CommandSpec("help", List.of(), "", "list Nemo chat commands"),
            new CommandSpec("q", List.of("quit"), "", "close the Nemo pane"));

    private CommandMenuState nemoCommandMenuState(Conversation conversation, String text) {
        var approvalSpecs = pendingApprovalCommandSpecs(conversation);
        if (!approvalSpecs.isEmpty()) {
            return CommandMenuState.forCommandText(text, 0, approvalSpecs, pendingApprovalMenuTitle(conversation));
        }
        return CommandMenuState.forCommandText(text, 0, NEMO_COMMAND_SPECS);
    }

    private synchronized List<CommandSpec> pendingApprovalCommandSpecs(Conversation conversation) {
        var specs = new ArrayList<CommandSpec>();
        var pendingApprovals = pendingApprovalsForWorkspace(conversation._workspaceRoot).stream()
                .filter(pending -> !pending._request.hostOnly())
                .toList();
        boolean multiple = pendingApprovals.size() > 1;
        for (PendingApproval pending : pendingApprovals) {
            Conversation owner = _conversations.get(pending._conversationId);
            String ownerLabel = owner == null ? pending._conversationId : owner._id;
            String approveOnce = "approve " + pending._id;
            specs.add(new CommandSpec("approve", List.of(), pending._id,
                    approvalMenuDetail("Allow this request once", pending), approveOnce, true,
                    multiple ? "Approve once " + ownerLabel : "Approve once"));
            if (isTemporaryShellCommandApprovalTool(pending._request.toolName())) {
                String approveShell = approveOnce + " shell-10m";
                specs.add(new CommandSpec("approve", List.of(), pending._id + " shell-10m",
                        approvalMenuDetail("Approve all shell commands for 10 minutes", pending),
                        approveShell, true,
                        multiple ? "Approve shell 10m " + ownerLabel : "Approve shell 10m"));
            }
            if (pending._request.persistable()) {
                String approveAlways = approveOnce + " always";
                specs.add(new CommandSpec("approve", List.of(), pending._id + " always",
                        approvalMenuDetail("Save an exact approval rule", pending), approveAlways, true,
                        multiple ? "Approve always " + ownerLabel : "Approve always"));
            }
            String deny = "deny " + pending._id;
            specs.add(new CommandSpec("deny", List.of(), pending._id,
                    approvalMenuDetail("Do not run this request", pending), deny, true,
                    multiple ? "Deny " + ownerLabel : "Deny"));
        }
        return specs;
    }

    private static String approvalMenuDetail(String action, PendingApproval pending) {
        return action;
    }

    private synchronized String pendingApprovalMenuTitle(Conversation conversation) {
        var pendingApprovals = pendingApprovalsForWorkspace(conversation._workspaceRoot).stream()
                .filter(pending -> !pending._request.hostOnly())
                .toList();
        var lines = new ArrayList<String>();
        lines.add("approval options");
        for (PendingApproval pending : pendingApprovals) {
            Conversation owner = _conversations.get(pending._conversationId);
            String session = owner == null ? pending._conversationId : owner._id + " | " + owner._title;
            lines.add(pending._id + " | " + pending._request.toolName() + " | " + pending._request.reason());
            lines.add("session: " + session);
            lines.add("workspace: " + pending._workspaceRoot);
            for (String summaryLine : pending._request.summary().split("\\R", -1)) {
                if (!summaryLine.isBlank()) {
                    lines.add(summaryLine);
                }
            }
        }
        return String.join("\n", lines);
    }

    private synchronized List<PendingApproval> pendingApprovalsForWorkspace(Path workspaceRoot) {
        var approvals = new ArrayList<PendingApproval>();
        for (PendingApproval pending : _pendingApprovals.values()) {
            if (pendingApprovalMatchesWorkspace(pending, workspaceRoot)) {
                approvals.add(pending);
            }
        }
        return approvals;
    }

    private synchronized boolean pendingApprovalMatchesWorkspace(PendingApproval pending, Path workspaceRoot) {
        Conversation owner = _conversations.get(pending._conversationId);
        return owner != null && owner._workspaceRoot.equals(workspaceRoot.toAbsolutePath().normalize());
    }

    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
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
        case ":conversations":
        case ":chats":
            appendAssistantNote(conversation, formatConversations(conversation._workspaceRoot));
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
        case ":clear":
            handleClearCommand(conversation, argument);
            return;
        case ":reset":
            handleResetCommand(conversation, argument);
            return;
        case ":delete":
            handleDeleteCommand(conversation, argument);
            return;
        case ":permissions":
            handlePermissionsCommand(conversation, argument);
            return;
        case ":mcp":
            appendAssistantNote(conversation, _mcpClient.status(conversation._configuration.mcpServers()));
            return;
        case ":tell":
            handleTellCommand(conversation, argument);
            return;
        case ":approve":
            handleApproveCommand(conversation, argument);
            return;
        case ":deny":
            handleDenyCommand(conversation, argument);
            return;
        case ":approvals":
            appendAssistantNote(conversation, formatApprovals(conversation));
            return;
        case ":unapprove":
            handleUnapproveCommand(conversation, argument);
            return;
        case ":swim-help":
            appendAssistantNote(conversation, HelpDocument.renderForNemo(argument));
            return;
        case ":help":
            appendAssistantNote(conversation,
                    "Available commands: :conversations, :abort [conversation-id|all], :workers, :new [title], :switch <conversation-id>, :rename <title>, :clear, :reset [conversation-id], :delete [conversation-id], :permissions [read-only|workspace-write|full-access], :mcp, :tell <conversation-id> <message>, approval options from the : menu, :approvals, :unapprove <rule-id|all>, :swim-help [topic], :help, :q\n"
                            + "Input: Enter sends; Shift-Enter, Ctrl-Enter, Alt-Enter, and Ctrl-J insert newlines. Pasted multiline text stays in the draft. The swim_help tool and :swim-help command expose the editor manual to Nemo. current_editor_context reports the active workspace, project, and file path without reading contents. The web_search, delegate_task, start_editor_control, screen_snapshot, and drive_editor tools are enabled by default unless disabled in nemo.conf. screen_snapshot and drive_editor require an active editor-control session started with host approval, and private/non-buffer workspaces are blocked. Loaded plugin tools are exposed as plugin__plugin__tool and follow Nemo permissions and approvals. Delegated workers can be inspected with worker_status/read_worker, messaged with :tell or message_worker, and joined with bounded join_worker. Editor-control approvals appear in a host overlay Nemo cannot see or control; Esc in that overlay stops the request.");
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

    private void handlePermissionsCommand(Conversation conversation, String argument) {
        if (argument.isBlank()) {
            appendAssistantNote(conversation, formatPermissions(conversation._configuration));
            return;
        }

        String mode = Configuration.normalizeToolPermissionMode(argument);
        String requested = argument.trim().toLowerCase().replace('_', '-');
        if (!displayPermissionMode(mode).equals(requested)) {
            appendAssistantNote(conversation, "Usage: :permissions read-only|workspace-write|full-access");
            return;
        }

        conversation._configuration = conversation._configuration.withToolPermissionMode(mode);
        appendAssistantNote(conversation, formatPermissions(conversation._configuration));
    }

    private void handleTellCommand(Conversation conversation, String argument) {
        int split = firstWhitespace(argument);
        if (split < 0) {
            appendAssistantNote(conversation, "Usage: :tell <conversation-id> <message>");
            return;
        }
        String sessionId = argument.substring(0, split).trim();
        String message = argument.substring(split + 1).trim();
        if (sessionId.isBlank() || message.isBlank()) {
            appendAssistantNote(conversation, "Usage: :tell <conversation-id> <message>");
            return;
        }
        appendAssistantNote(conversation, sendWorkerMessage(conversation._workspaceRoot, sessionId, message));
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); ++i) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String formatPermissions(Configuration configuration) {
        var lines = new ArrayList<String>();
        lines.add("Permissions:");
        lines.add("- mode: " + displayPermissionMode(configuration.toolPermissionMode()));
        lines.add("- command policy: " + effectiveCommandPolicy(configuration));
        lines.add("- OS sandbox: " + formatOsSandbox(configuration));
        lines.add("- approval policy: " + configuration.toolApprovalPolicy().replace('_', '-'));
        lines.add("- MCP servers: " + configuration.mcpServers().size());
        int registeredPluginTools = SwimNemoToolRegistry.listTools().size();
        int availablePluginTools = pluginToolDescriptors(configuration).size();
        lines.add("- plugin tools: " + availablePluginTools
                + (registeredPluginTools == availablePluginTools ? "" : " available of " + registeredPluginTools + " registered"));
        lines.add("- read-only blocks: run_command, shell_start, shell_poll, shell_stop, mvn, write_file, search_replace, apply_patch, git mutating subcommands, drive_editor");
        return String.join("\n", lines);
    }

    private static String displayPermissionMode(String mode) {
        return mode.replace('_', '-');
    }

    private static String effectiveCommandPolicy(Configuration configuration) {
        if ("full_access".equals(configuration.toolPermissionMode())) {
            return "trusted (full-access)";
        }
        return configuration.toolCommandPolicy();
    }

    private static String formatOsSandbox(Configuration configuration) {
        if ("full_access".equals(configuration.toolPermissionMode())) {
            return configuration.toolOsSandbox() + " (bypassed by full-access)";
        }
        if ("disabled".equals(configuration.toolOsSandbox())) {
            return "disabled";
        }
        OsSandboxBackend backend = osSandboxBackend();
        return configuration.toolOsSandbox() + " (" + backend.name().toLowerCase().replace('_', '-') + ")";
    }

    private void handleApproveCommand(Conversation conversation, String argument) {
        String[] parts = argument.split("\\s+");
        String approvalId = parts.length == 0 ? "" : parts[0].trim();
        boolean persist = false;
        boolean temporaryShellCommands = false;
        for (int i = 1; i < parts.length; i++) {
            String option = parts[i].trim().toLowerCase();
            if ("always".equals(option)) {
                persist = true;
            } else if ("shell-10m".equals(option) || "shell10m".equals(option)
                    || "shell_10m".equals(option) || "10m".equals(option)) {
                temporaryShellCommands = true;
            }
        }
        if (approvalId.isBlank()) {
            approvalId = singlePendingApprovalId(conversation);
        }
        if (approvalId.isBlank()) {
            appendAssistantNote(conversation, "Usage: :approve <approval-id> [always|shell-10m]");
            return;
        }
        PendingApproval pending = resolveApproval(conversation, approvalId, true,
                persist && !temporaryShellCommands);
        if (pending == null) {
            appendAssistantNote(conversation, "Unknown approval: " + approvalId);
            return;
        }
        if (temporaryShellCommands && isTemporaryShellCommandApprovalTool(pending._request.toolName())) {
            List<PendingApproval> additionallyApproved = grantTemporaryShellCommandApproval();
            appendApprovalResolution(conversation, pending,
                    "Approved " + pending._id + " and shell commands for 10 minutes.");
            appendTemporaryApprovalResolutions(conversation, pending, additionallyApproved);
            return;
        }
        appendApprovalResolution(conversation, pending,
                "Approved " + pending._id + (persist && pending._request.persistable() ? " and saved exact rule." : "."));
    }

    private void handleDenyCommand(Conversation conversation, String argument) {
        String approvalId = argument.trim();
        if (approvalId.isBlank()) {
            approvalId = singlePendingApprovalId(conversation);
        }
        if (approvalId.isBlank()) {
            appendAssistantNote(conversation, "Usage: :deny <approval-id>");
            return;
        }
        PendingApproval pending = resolveApproval(conversation, approvalId, false, false);
        if (pending == null) {
            appendAssistantNote(conversation, "Unknown approval: " + approvalId);
            return;
        }
        appendApprovalResolution(conversation, pending, "Denied " + pending._id + ".");
    }

    private synchronized PendingApproval resolveApproval(Conversation conversation, String approvalId, boolean approved,
            boolean persist) {
        PendingApproval pending = _pendingApprovals.get(approvalId);
        if (pending == null || !pendingApprovalMatchesWorkspace(pending, conversation._workspaceRoot)) {
            return null;
        }
        pending.resolve(approved, persist && pending._request.persistable());
        _pendingApprovals.remove(approvalId);
        return pending;
    }

    private synchronized String singlePendingApprovalId(Conversation conversation) {
        String match = "";
        for (PendingApproval pending : pendingApprovalsForWorkspace(conversation._workspaceRoot)) {
            if (pending._request.hostOnly()) {
                continue;
            }
            if (!match.isBlank()) {
                return "";
            }
            match = pending._id;
        }
        return match;
    }

    private void appendApprovalResolution(Conversation conversation, PendingApproval pending, String text) {
        Conversation owner = ownerConversation(pending);
        boolean denied = text.startsWith("Denied ");
        String message = owner == null || owner == conversation
                ? text
                : appendSentenceSuffix(text, " for " + owner._id + " (" + owner._title + ")");
        appendTurn(conversation, new ChatTurn("approval", message, denied && owner == conversation));
        if (owner != null && owner != conversation) {
            appendTurn(owner, new ChatTurn("approval", text, denied));
        }
    }

    private void appendTemporaryApprovalResolutions(Conversation conversation, PendingApproval current,
            List<PendingApproval> additionallyApproved) {
        for (PendingApproval pending : additionallyApproved) {
            if (pending == current) {
                continue;
            }
            Conversation owner = ownerConversation(pending);
            String text = "Approved " + pending._id + " by 10-minute shell command approval.";
            if (owner == null || owner == conversation) {
                appendTurn(conversation, new ChatTurn("approval", text, false));
            } else {
                appendTurn(owner, new ChatTurn("approval", text, false));
            }
        }
    }

    private static String appendSentenceSuffix(String text, String suffix) {
        String stripped = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        return stripped + suffix + ".";
    }

    private synchronized Conversation ownerConversation(PendingApproval pending) {
        return _conversations.get(pending._conversationId);
    }

    private synchronized String formatApprovals(Conversation conversation) {
        ensureApprovalsLoaded();
        String workspaceRoot = conversation._workspaceRoot.toAbsolutePath().normalize().toString();
        var lines = new ArrayList<String>();
        lines.add("Approvals:");
        long temporaryRemainingMillis = temporaryShellCommandApprovalRemainingMillisLocked();
        if (temporaryRemainingMillis > 0) {
            lines.add("Temporary:");
            lines.add("- run_command,shell_start,mvn | all sessions | expires in "
                    + formatDuration(temporaryRemainingMillis));
        }
        boolean anyPending = false;
        for (PendingApproval pending : pendingApprovalsForWorkspace(conversation._workspaceRoot)) {
            if (!anyPending) {
                lines.add("Pending:");
                anyPending = true;
            }
            Conversation owner = _conversations.get(pending._conversationId);
            String session = owner == null ? pending._conversationId : owner._id + " | " + owner._title;
            lines.add("- " + pending._id + " | " + session + " | " + pending._request.toolName()
                    + " | " + pending._request.reason());
        }
        boolean anySaved = false;
        for (ApprovalRule rule : _approvalRules) {
            if (!rule.workspaceRoot().equals(workspaceRoot)) {
                continue;
            }
            if (!anySaved) {
                lines.add("Saved:");
                anySaved = true;
            }
            lines.add("- " + rule.id() + " | " + rule.toolName());
        }
        if (temporaryRemainingMillis <= 0 && !anyPending && !anySaved) {
            lines.add("(none)");
        }
        return String.join("\n", lines);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes <= 0) {
            return seconds + "s";
        }
        return remainingSeconds == 0 ? minutes + "m" : minutes + "m " + remainingSeconds + "s";
    }

    private void handleUnapproveCommand(Conversation conversation, String argument) {
        String target = argument.trim();
        if (target.isBlank()) {
            appendAssistantNote(conversation, "Usage: :unapprove <rule-id|all>");
            return;
        }
        int removed = removeApprovalRules(conversation._workspaceRoot, target);
        appendAssistantNote(conversation, removed == 0
                ? "No saved approval matched " + target + "."
                : "Removed " + removed + " approval rule" + (removed == 1 ? "." : "s."));
    }

    private synchronized int removeApprovalRules(Path workspaceRoot, String target) {
        ensureApprovalsLoaded();
        String normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString();
        int before = _approvalRules.size();
        if ("all".equalsIgnoreCase(target)) {
            _approvalRules.removeIf(rule -> rule.workspaceRoot().equals(normalizedRoot));
        } else {
            _approvalRules.removeIf(rule -> rule.workspaceRoot().equals(normalizedRoot) && rule.id().equals(target));
        }
        int removed = before - _approvalRules.size();
        if (removed > 0) {
            persistApprovals();
        }
        return removed;
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
            appendAssistantNote(conversation, "Usage: :switch <conversation-id>");
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

    private void handleClearCommand(Conversation conversation, String argument) {
        if (!argument.isBlank()) {
            appendAssistantNote(conversation, "Usage: :clear");
            return;
        }
        clearConversation(conversation);
        showMessage("Cleared " + conversation._id + ".");
    }

    private void handleResetCommand(Conversation conversation, String argument) {
        Conversation target = argument.isBlank()
                ? conversation
                : findConversation(argument, conversation._workspaceRoot);
        if (target == null) {
            appendAssistantNote(conversation, "Unknown session: " + argument);
            return;
        }
        clearConversation(target);
        showMessage("Reset " + target._id + ".");
    }

    private void clearConversation(Conversation conversation) {
        stopWorker(conversation);
        conversation._turns.clear();
        conversation._contextUsagePercent = null;
        conversation._updatedAtMillis = System.currentTimeMillis();
        persistSessions();
        if (isPanelVisible(conversation)) {
            conversation._panelView.setMessages(List.of());
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(null);
        }
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

    private String formatConversations(Path workspaceRoot) {
        var sessions = new ArrayList<Conversation>();
        for (var conversation : _conversations.values()) {
            if (conversation._workspaceRoot.equals(workspaceRoot)) {
                sessions.add(conversation);
            }
        }
        sessions.sort(Comparator.comparingLong(conversation -> conversation._createdAtMillis));
        if (sessions.isEmpty()) {
            return "No Nemo conversations.";
        }

        var lines = new ArrayList<String>();
        lines.add("Conversations:");
        for (var session : sessions) {
            String marker = session._id.equals(_activeSessionId) ? "*" : "-";
            String status = session._pending ? "running " + elapsedSeconds(session) + "s" : "idle";
            var approvals = pendingApprovalIdsForConversation(session);
            String approvalStatus = approvals.isEmpty() ? "" : " | waiting for approval " + String.join(",", approvals);
            lines.add(marker + " " + session._id + " | " + session._title + " | " + status + approvalStatus
                    + " | turns=" + session._turns.size());
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
            var approvals = pendingApprovalIdsForConversation(worker);
            String approvalStatus = approvals.isEmpty() ? "" : " | waiting for approval " + String.join(",", approvals);
            lines.add("- " + worker._id + " | " + worker._title + " | " + elapsedSeconds(worker) + "s" + approvalStatus);
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
        long requestId = conversation._activeRequestId;
        releaseEditorControlLease(conversation, requestId);
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._activeRequestId = 0;
        conversation._queuedUserTurns.clear();
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

    private synchronized void handleResponse(Conversation conversation, long requestId, ResponseResult response) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        releaseEditorControlLease(conversation, requestId);
        List<ChatTurn> queuedTurns = List.of();
        if (!conversation._queuedUserTurns.isEmpty()) {
            queuedTurns = List.copyOf(conversation._queuedUserTurns);
            conversation._queuedUserTurns.clear();
        }
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._contextUsagePercent = response.contextUsagePercent();
        conversation._activeRequestId = 0;
        conversation._worker = null;
        for (ToolTrace trace : response.toolTraces()) {
            appendTurn(conversation, new ChatTurn("tool", trace.displayText(), false));
        }
        appendTurn(conversation, new ChatTurn("nemo", response.text()));
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(response.contextUsagePercent());
        }
        if (!queuedTurns.isEmpty()) {
            startRequest(conversation, List.of(new ChatTurn("me", queuedWorkerMessage(queuedTurns))));
        }
    }

    private synchronized void handleToolTrace(Conversation conversation, long requestId, ToolTrace trace) {
        if (conversation._activeRequestId != requestId || !conversation._pending) {
            return;
        }
        appendTurn(conversation, new ChatTurn("tool", trace.displayText(), false));
    }

    private synchronized void handleFailure(Conversation conversation, long requestId, String response) {
        if (conversation._activeRequestId != requestId) {
            return;
        }
        releaseEditorControlLease(conversation, requestId);
        conversation._pending = false;
        conversation._pendingStartedAtMillis = 0;
        conversation._contextUsagePercent = null;
        conversation._activeRequestId = 0;
        conversation._worker = null;
        conversation._queuedUserTurns.clear();
        conversation._updatedAtMillis = System.currentTimeMillis();
        if (isPanelVisible(conversation)) {
            conversation._panelView.appendMessage("nemo", response);
        } else {
            showMessage(response);
        }
        if (isPanelVisible(conversation)) {
            conversation._panelView.setPending(false);
            conversation._panelView.setContextUsagePercent(null);
        }
        persistSessions();
    }

    private void showMessage(String message) {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            if (Window.getInstance() != null) {
                Window.getInstance().getCommandView().setMessage(message);
            }
        }));
    }
}
