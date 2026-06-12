package org.fisk.swim.lsp.java;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSaveReason;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.DiagnosticAction;
import org.fisk.swim.lsp.DiagnosticActionProvider;
import org.fisk.swim.lsp.DiagnosticEntry;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LspCompletionSession;
import org.fisk.swim.lsp.LspLocationMenuSession;
import org.fisk.swim.lsp.shared.AsyncCompletionCoordinator;
import org.fisk.swim.lsp.shared.AsyncLspRequestQueue;
import org.fisk.swim.lsp.shared.AsyncSemanticTokenHighlighter;
import org.fisk.swim.lsp.shared.LspDocumentChangeBatcher;
import org.fisk.swim.lsp.shared.LspFeatureSupport;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.Settings;
import org.fisk.swim.ui.CompletionPopupView;
import org.fisk.swim.ui.LspLocationPopupView;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.UiTheme;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.googlecode.lanterna.TextColor;

public class JavaLSPClient extends Thread implements LanguageMode, DiagnosticActionProvider {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();
    private static final String DIAGNOSTIC_PROVIDER_ID = "java-lsp";
    private static final String CODE_ACTION_SOURCE_ORGANIZE_IMPORTS = "source.organizeImports";
    private static final long DID_CHANGE_BATCH_DELAY_MILLIS = 15;
    static final TextColor SEMANTIC_NAMESPACE = UiTheme.SEMANTIC_NAMESPACE;
    static final TextColor SEMANTIC_TYPE = UiTheme.SEMANTIC_TYPE;
    static final TextColor SEMANTIC_PARAMETER = UiTheme.SEMANTIC_PARAMETER;
    static final TextColor SEMANTIC_MEMBER = UiTheme.SEMANTIC_MEMBER;
    static final TextColor SEMANTIC_FUNCTION = UiTheme.SEMANTIC_FUNCTION;
    static final TextColor SEMANTIC_COMMENT = UiTheme.SEMANTIC_COMMENT;
    static final TextColor SEMANTIC_STRING = UiTheme.SEMANTIC_STRING;
    static final TextColor SEMANTIC_NUMBER = UiTheme.SEMANTIC_NUMBER;
    static final TextColor SEMANTIC_KEYWORD = UiTheme.SEMANTIC_KEYWORD;
    static final TextColor SEMANTIC_READONLY = UiTheme.SEMANTIC_READONLY;

    private final Object _lock = new Object();
    private boolean _started = false;
    private boolean _startupComplete = false;
    private boolean _launchAttempted = false;
    private boolean _enabled = true;
    private Throwable _startupError;
    private Thread _workerThread;
    private LanguageServer _server;
    private ServerCapabilities _capabilities;
    private final AsyncLspRequestQueue _lspRequestQueue = new AsyncLspRequestQueue(
            _log,
            "swim-java-lsp-requests",
            () -> _enabled && _server != null);
    private final LspDocumentChangeBatcher _documentChangeBatcher = new LspDocumentChangeBatcher(
            _lspRequestQueue,
            () -> _server == null ? null : _server.getTextDocumentService(),
            DID_CHANGE_BATCH_DELAY_MILLIS,
            (uri, path, change) -> DiagnosticService.getInstance().applyTextChange(
                    uri,
                    path,
                    change.getRange(),
                    change.getText()));
    private final AsyncSemanticTokenHighlighter _semanticTokens = new AsyncSemanticTokenHighlighter(
            _lspRequestQueue,
            _log,
            "semantic token refresh",
            this::supportsSemanticTokens,
            this::flushPendingDocumentChanges,
            this::fetchSemanticHighlights,
            250,
            20);
    private final AsyncCompletionCoordinator<CompletionRequestSnapshot, LspCompletionSession> _completionRequests =
            new AsyncCompletionCoordinator<>(_lspRequestQueue, this::runOnEventThread);
    private Thread _shutdownHook;
    private AutoCloseable _providerConnection;

    private Path _projectPath;
    private Path _workspacePath;
    private Path _swimHomePath = Paths.get(System.getProperty("user.home"), ".swim");
    private final JavaLspProvider _provider;
    private volatile String _providerDescription = "";
    private final LspFeatureSupport _features = new LspFeatureSupport(new LspFeatureSupport.Client() {
        @Override
        public String displayName() {
            return "Java LSP";
        }

        @Override
        public boolean isAvailable() {
            return _enabled && _server != null;
        }

        @Override
        public LanguageServer server() {
            return _server;
        }

        @Override
        public ServerCapabilities capabilities() {
            return _capabilities;
        }

        @Override
        public AsyncLspRequestQueue requestQueue() {
            return _lspRequestQueue;
        }

        @Override
        public void flushPendingDocumentChanges(String uri) {
            JavaLSPClient.this.flushPendingDocumentChanges(uri);
        }

        @Override
        public void runOnEventThread(Runnable action) {
            JavaLSPClient.this.runOnEventThread(action);
        }

        @Override
        public void applyWorkspaceEdit(BufferContext context, WorkspaceEdit edit) {
            JavaLSPClient.applyWorkspaceEdit(context, edit);
        }

        @Override
        public void applyCommand(BufferContext context, Command command) {
            JavaLSPClient.this.applyCommand(context, command);
        }

        @Override
        public String displayPath(Path path) {
            return JavaLSPClient.this.displayPath(path);
        }

        @Override
        public Logger log() {
            return _log;
        }
    });

    private final Map<String, TextColor> _foregroundColours = new HashMap<>();
    private final Map<String, StringBuilder> _outputBuffers = new HashMap<>();
    private final Map<String, AsyncSemanticTokenHighlighter.CachedSemanticTokens> _semanticTokensCache =
            _semanticTokens.cacheView();
    private final Object _completionLock = new Object();
    private final Object _definitionLock = new Object();
    private final Object _snippetLock = new Object();

    private LspCompletionSession _completionSession;
    private CompletionPopupView _completionPopupView;
    private LspLocationMenuSession _definitionMenuSession;
    private LspLocationPopupView _definitionPopupView;
    private JavaSnippetSession _snippetSession;

    static record SemanticHighlight(int start, int end, TextColor foregroundColor) {
    }

    private static record CompletionRequestSnapshot(
            BufferContext bufferContext,
            String uri,
            int version,
            int cursor,
            Position position,
            String prefix,
            int replacementStart,
            int replacementEnd,
            CompletionTriggerKind triggerKind,
            String triggerCharacter,
            long generation) implements AsyncCompletionCoordinator.Snapshot {
    }

    public static JavaLSPClient getInstance() {
        return JavaLspPluginSupport.getClient();
    }

    static synchronized JavaLSPClient getInstalledInstanceOr(JavaLSPClient fallback) {
        return _instance == null ? fallback : _instance;
    }

    static synchronized void installInstance(JavaLSPClient client) {
        shutdownInstalledInstance();
        _instance = client;
    }

    static synchronized void shutdownInstalledInstance() {
        JavaLSPClient instance = _instance;
        _instance = null;
        if (instance != null) {
            instance.shutdown();
        }
    }

    private static JavaLSPClient _instance;

    public JavaLSPClient() {
        this(createDefaultProvider());
    }

    JavaLSPClient(JavaLspProvider provider) {
        _provider = provider;
        if (!_provider.isAvailable()) {
            _log.debug("No LSP support");
            _enabled = false;
        }
        initColours();
    }

    private static JavaLspProvider createDefaultProvider() {
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        return new EmbeddedOracleModuleLayerLspProvider(extensionPath);
    }

    public boolean hasStarted() {
        return _started;
    }

    public boolean isEnabled() {
        return _enabled;
    }

    public String getProviderDescription() {
        return _providerDescription;
    }

    public void disable() {
        _enabled = false;
    }

    static Path findOracleExtensionPath(Path extensionsDir) {
        return EmbeddedOracleModuleLayerLspProvider.findOracleExtensionPath(extensionsDir);
    }

    static Path getWorkspacePath(Path swimHomePath, Path projectPath) {
        String projectName = projectPath.getFileName() == null ? "project" : projectPath.getFileName().toString();
        projectName = projectName.replaceAll("[^A-Za-z0-9._-]", "_");
        return swimHomePath.resolve("workspace").resolve(projectName + "-" + sha1(projectPath.toAbsolutePath().normalize().toString()));
    }

    private static String sha1(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder();
            for (var b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm missing", e);
        }
    }

    private Path getProjectPath(Path filePath) {
        var projectPath = ProjectPaths.getProjectRootPath(filePath);
        if (projectPath != null) {
            return projectPath;
        }
        if (filePath != null) {
            filePath = filePath.toAbsolutePath();
            if (filePath.toFile().isFile()) {
                return filePath.getParent();
            }
            return filePath;
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    public synchronized void startServer(Path filePath) {
        if (!_enabled || _launchAttempted) {
            return;
        }
        _projectPath = getProjectPath(filePath);
        _workspacePath = getWorkspacePath(_swimHomePath, _projectPath);
        _launchAttempted = true;
        var thread = new Thread(this::run, "swim-java-lsp");
        thread.setDaemon(true);
        _workerThread = thread;
        thread.start();
    }

    private void signalStartupSuccess() {
        synchronized (_lock) {
            _started = true;
            _startupComplete = true;
            _startupError = null;
            _lock.notifyAll();
        }
    }

    private void signalStartupFailure(Throwable error) {
        synchronized (_lock) {
            _started = false;
            _startupComplete = true;
            _startupError = error;
            _lock.notifyAll();
        }
    }

    private void initColours() {
        _foregroundColours.put(String.join(":", new String[] {
                "invalid.deprecated.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_KEYWORD);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.other.autoboxing.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_MEMBER);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "storage.modifier.final.java",
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_READONLY);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_READONLY);
        _foregroundColours.put(String.join(":", new String[] {
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.method.identifier.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.abstract.java",
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "constant.other.key.java",
                "meta.declaration.annotation.java",
                "source.java"
        }), UiTheme.SEMANTIC_NAMESPACE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.parameter.java",
                "meta.method.identifier.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_PARAMETER);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_PARAMETER);
        _foregroundColours.put(String.join(":", new String[] {
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.DEFAULT);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.type.generic.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.method.identifier.java",
                "meta.function-call.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_FUNCTION);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.type.generic.java",
                "meta.definition.class.implemented.interfaces.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.abstract.java",
                "entity.name.type.class.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.class.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.enum.java",
                "meta.enum.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.type.annotation.java",
                "meta.declaration.annotation.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.interface.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_TYPE);
        _foregroundColours.put(String.join(":", new String[] {
                "constant.numeric.decimal.java",
                "meta.definition.variable.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_NUMBER);
        _foregroundColours.put(String.join(":", new String[] {
                "keyword.other.var.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), UiTheme.SEMANTIC_KEYWORD);
    }

    private synchronized void clearSemanticTokensCache() {
        _semanticTokens.clear();
    }

    private synchronized void clearSemanticTokensCache(String uri) {
        _semanticTokens.clear(uri);
    }

    Thread createShutdownHook() {
        return new Thread(() -> {
            if (_providerConnection == null) {
                return;
            }
            try {
                _providerConnection.close();
            } catch (Exception e) {
            }
        });
    }

    LanguageClient createLanguageClient() {
        return new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
                _log.debug("telemetryEvent called");
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
                _log.debug("publishDiagnostics called");
                DiagnosticService.getInstance().publish(
                        DIAGNOSTIC_PROVIDER_ID,
                        diagnostics.getUri(),
                        pathForUri(diagnostics.getUri()),
                        diagnostics.getDiagnostics());
                refreshDiagnosticsUi();
            }

            @Override
            public void showMessage(MessageParams message) {
                _log.debug("showMessage: " + message.getMessage());
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                _log.debug("showMessageRequest called");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void logMessage(MessageParams message) {
                _log.debug("logMessage: " + message.getMessage());
            }

            @Override
            public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
                return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(
                        _projectPath.toUri().toString(),
                        _projectPath.getFileName().toString())));
            }

            @Override
            public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
                _log.debug("Configuration?");
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
                var window = Window.getInstance();
                if (window == null || window.getBufferContext() == null) {
                    return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
                }
                try {
                    applyWorkspaceEdit(window.getBufferContext(), params == null ? null : params.getEdit());
                    return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true));
                } catch (RuntimeException e) {
                    _log.debug("Applying workspace edit failed", e);
                    return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
                }
            }

            @Override
            public CompletableFuture<Void> registerCapability(RegistrationParams params) {
                _log.debug("Register capability?");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
                _log.debug("Unregister capability?");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
                _log.debug("createProgress: " + params);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void notifyProgress(ProgressParams params) {
                _log.debug("notifyProgress: " + params);
            }

            public void languageStatus(Object params) {
                _log.debug("language/status: " + params);
            }

            @JsonRequest("output/write")
            public CompletableFuture<Void> writeOutput(Object params) {
                var values = objectValues(params);
                String outputName = values.getOrDefault("outputName", "Oracle Java");
                String message = values.getOrDefault("message", "");
                appendOutput(outputName, message);
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("output/show")
            public CompletableFuture<Void> showOutput(String outputName) {
                if (outputName != null) {
                    _outputBuffers.computeIfAbsent(outputName, ignored -> new StringBuilder());
                    _log.debug("oracle-java output/show: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("output/close")
            public CompletableFuture<Void> closeOutput(String outputName) {
                if (outputName != null) {
                    _outputBuffers.remove(outputName);
                    _log.debug("oracle-java output/close: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("output/reset")
            public CompletableFuture<Void> resetOutput(String outputName) {
                if (outputName != null) {
                    _outputBuffers.put(outputName, new StringBuilder());
                    _log.debug("oracle-java output/reset: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("window/showHtmlPage")
            public CompletableFuture<Void> showHtmlPage(Object params) {
                _log.debug("oracle-java window/showHtmlPage: " + params);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private Map<String, String> objectValues(Object params) {
        if (params == null) {
            return Map.of();
        }
        if (params instanceof Map<?, ?> rawMap) {
            var values = new HashMap<String, String>();
            for (var entry : rawMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    values.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            return values;
        }
        return _gson.fromJson(_gson.toJsonTree(params), Map.class);
    }

    private synchronized void appendOutput(String outputName, String message) {
        var buffer = _outputBuffers.computeIfAbsent(outputName, ignored -> new StringBuilder());
        buffer.append(message);
        _log.debug("oracle-java output[" + outputName + "]: " + message);
    }

    private void setup() throws IOException {
        Files.createDirectories(_workspacePath);
        _log.debug("LSP workspace path: " + _projectPath);
        _log.debug("LSP workspace folder path: " + _workspacePath);
        var client = createLanguageClient();
        try {
            var session = _provider.start(
                    _projectPath,
                    _workspacePath,
                    client,
                    getClientCapabilities(),
                    getInitializationOptions(),
                    configurationTimeoutSeconds());
            _server = session.server();
            _capabilities = session.capabilities();
            _providerConnection = session.closeable();
            _providerDescription = session.description();
            _log.debug("Java LSP provider: " + session.description());
            _shutdownHook = createShutdownHook();
            Runtime.getRuntime().addShutdownHook(_shutdownHook);
            signalStartupSuccess();
        } catch (Exception e) {
            signalStartupFailure(e);
            throw new RuntimeException("Exception initializing LSP server", e);
        }
    }

    private static long configurationTimeoutSeconds() {
        return 30;
    }

    private Map<String, Object> getInitializationOptions() {
        return Map.of(
                "nbcodeCapabilities", Map.of(
                        "statusBarMessageSupport", false,
                        "testResultsSupport", false,
                        "showHtmlPageSupport", false,
                        "wantsJavaSupport", true,
                        "wantsGroovySupport", false,
                        "wantsTelemetryEnabled", false,
                        "wantsNotebookSupport", false));
    }

    private ClientCapabilities getClientCapabilities() {
        var workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        workspace.setConfiguration(true);

        var executeCommand = new ExecuteCommandCapabilities();
        workspace.setExecuteCommand(executeCommand);

        var textDocument = new TextDocumentClientCapabilities();

        var codeAction = new CodeActionCapabilities(true);
        codeAction.setDataSupport(true);
        codeAction.setResolveSupport(new CodeActionResolveSupportCapabilities(List.of("edit", "command")));
        textDocument.setCodeAction(codeAction);

        var codeLens = new CodeLensCapabilities();
        textDocument.setCodeLens(codeLens);

        var completionItem = new CompletionItemCapabilities();
        completionItem.setSnippetSupport(false);
        completionItem.setCommitCharactersSupport(true);
        completionItem.setDeprecatedSupport(true);
        completionItem.setPreselectSupport(true);
        completionItem.setInsertReplaceSupport(true);
        completionItem.setLabelDetailsSupport(true);
        var completion = new CompletionCapabilities();
        completion.setCompletionItem(completionItem);
        completion.setContextSupport(true);
        textDocument.setCompletion(completion);

        var references = new ReferencesCapabilities();
        textDocument.setReferences(references);

        var semanticTokens = new SemanticTokensCapabilities();
        var requests = new SemanticTokensClientCapabilitiesRequests();
        requests.setFull(true);
        requests.setRange(false);
        semanticTokens.setRequests(requests);
        semanticTokens.setFormats(List.of(TokenFormat.Relative));
        semanticTokens.setTokenTypes(List.of(
                "namespace", "type", "class", "enum", "interface", "struct", "typeParameter",
                "parameter", "variable", "property", "enumMember", "event", "function", "method",
                "macro", "keyword", "modifier", "comment", "string", "number", "regexp", "operator", "decorator"));
        semanticTokens.setTokenModifiers(List.of(
                "declaration", "definition", "readonly", "static", "deprecated",
                "abstract", "async", "modification", "documentation", "defaultLibrary"));
        semanticTokens.setMultilineTokenSupport(false);
        semanticTokens.setOverlappingTokenSupport(false);
        semanticTokens.setAugmentsSyntaxTokens(true);
        textDocument.setSemanticTokens(semanticTokens);
        LspFeatureSupport.installClientCapabilities(workspace, textDocument);

        return new ClientCapabilities(workspace, textDocument, null);
    }

    @Override
    public void run() {
        if (!_enabled) {
            return;
        }
        try {
            setup();
        } catch (Throwable e) {
            signalStartupFailure(e);
            _log.error("Error setting up LSP server", e);
        }
    }

    public LanguageServer getServer() {
        return _server;
    }

    public void ensureInit() {
        if (!_enabled) {
            return;
        }
        for (;;) {
            synchronized (_lock) {
                if (_startupComplete) {
                    if (!_started) {
                        throw new IllegalStateException("Java LSP failed to initialize", _startupError);
                    }
                    break;
                }
                try {
                    _lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void enqueueLspRequest(String description, Runnable action) {
        _lspRequestQueue.execute(description, action);
    }

    private void queueDocumentChanges(
            BufferContext bufferContext,
            org.eclipse.lsp4j.VersionedTextDocumentIdentifier textDocument,
            List<TextDocumentContentChangeEvent> contentChanges) {
        _documentChangeBatcher.queue(
                bufferContext.getBuffer().getURI().toString(),
                bufferContext.getBuffer().getPath(),
                textDocument,
                contentChanges);
    }

    private void flushPendingDocumentChanges(String uri) {
        _documentChangeBatcher.flush(uri);
    }

    public synchronized void shutdown() {
        _features.clearAllDocumentContexts();
        _lspRequestQueue.shutdown();
        _documentChangeBatcher.clear();
        if (_shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException e) {
            }
            _shutdownHook = null;
        }
        if (_providerConnection != null) {
            try {
                _providerConnection.close();
            } catch (Exception e) {
            }
            _providerConnection = null;
        }
        if (_workerThread != null && Thread.currentThread() != _workerThread) {
            try {
                _workerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        _workerThread = null;
        _server = null;
        _capabilities = null;
        _providerDescription = "";
        clearSemanticTokensCache();
        DiagnosticService.getInstance().clearProvider(DIAGNOSTIC_PROVIDER_ID);
        cancelCompletion();
        cancelDefinitionMenu();
        cancelSnippet();
        _started = false;
        _startupComplete = false;
        _launchAttempted = false;
        _startupError = null;
    }

    public List<ColorInformation> decorateBuffer(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return new ArrayList<>();
        }
        try {
            _log.debug("Decorate buffer");
            var colorParams = new DocumentColorParams(bufferContext.getBuffer().getTextDocumentID());
            return _server.getTextDocumentService().documentColor(colorParams).join();
        } catch (Exception e) {
            _log.error("Error getting colours: ", e);
            throw new RuntimeException("Error getting code actions: ", e);
        }
    }

    public void codeLens(BufferContext bufferContext) {
        _features.showCodeLens(bufferContext);
    }

    private List<Either<Command, CodeAction>> requestCodeActions(
            BufferContext bufferContext,
            Range range,
            List<Diagnostic> diagnostics) {
        return requestCodeActions(bufferContext, range, diagnostics, List.of());
    }

    private List<Either<Command, CodeAction>> requestCodeActions(
            BufferContext bufferContext,
            Range range,
            List<Diagnostic> diagnostics,
            List<String> only) {
        if (!_enabled || _server == null) {
            return List.of();
        }
        try {
            _log.debug("Get code actions");
            var context = new CodeActionContext(diagnostics);
            if (only != null && !only.isEmpty()) {
                context.setOnly(only);
            }
            var params = new CodeActionParams(bufferContext.getBuffer().getTextDocumentID(), range, context);
            _log.debug("Code action: " + params);
            var actions = _server.getTextDocumentService().codeAction(params).join();
            _log.debug("Code actions returned: " + actions.size());
            for (var either : actions) {
                if (either.isLeft()) {
                    var command = either.getLeft();
                    _log.debug("Code action command returned: title=" + command.getTitle()
                            + ", command=" + command.getCommand());
                } else {
                    var action = either.getRight();
                    _log.debug("Code action returned: title=" + action.getTitle()
                            + ", kind=" + action.getKind()
                            + ", command=" + (action.getCommand() == null ? null : action.getCommand().getCommand())
                            + ", hasEdit=" + hasWorkspaceEditChanges(action.getEdit()));
                }
            }
            return actions;
        } catch (Exception e) {
            _log.error("Error getting code actions: ", e);
            throw new RuntimeException("Error getting code actions: ", e);
        }
    }

    private List<Either<Command, CodeAction>> getCodeActions(BufferContext bufferContext) {
        return getCodeActions(bufferContext, List.of());
    }

    private List<Either<Command, CodeAction>> getCodeActions(BufferContext bufferContext, List<String> only) {
        var range = wholeDocumentRange(bufferContext);
        return requestCodeActions(bufferContext, range, new ArrayList<>(), only);
    }

    private static Range wholeDocumentRange(BufferContext bufferContext) {
        String text = bufferContext.getBuffer().getString();
        int end = text.endsWith("\n") ? text.length() - 1 : text.length();
        int line = 0;
        int character = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Range(new Position(0, 0), new Position(line, character));
    }

    private boolean supportsCompletion() {
        return _enabled
                && _server != null
                && _capabilities != null
                && _capabilities.getCompletionProvider() != null;
    }

    private static Position getPosition(BufferContext context, int index) {
        var line = context.getTextLayout().getPhysicalLineAt(index);
        return new Position(line.getY(), index - line.getStartPosition());
    }

    private String currentCompletionPrefix(BufferContext bufferContext) {
        var buffer = bufferContext.getBuffer();
        int cursor = buffer.getCursor().getPosition();
        int start = cursor;
        while (start > 0) {
            String character = buffer.getCharacter(start - 1);
            if (character.isEmpty() || !Character.isJavaIdentifierPart(character.charAt(0))) {
                break;
            }
            --start;
        }
        return buffer.getSubstring(start, cursor);
    }

    private boolean isCompletionTriggerCharacter(char character) {
        if (!supportsCompletion()) {
            return false;
        }
        CompletionOptions options = _capabilities.getCompletionProvider();
        if (options == null || options.getTriggerCharacters() == null) {
            return false;
        }
        String value = Character.toString(character);
        return options.getTriggerCharacters().contains(value);
    }

    private boolean shouldRequestCompletion(BufferContext bufferContext) {
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        if (cursor <= 0) {
            return false;
        }
        String previous = bufferContext.getBuffer().getCharacter(cursor - 1);
        if (previous.isEmpty()) {
            return false;
        }
        char character = previous.charAt(0);
        return Character.isJavaIdentifierPart(character) || isCompletionTriggerCharacter(character);
    }

    private CompletionRequestSnapshot createCompletionRequestSnapshot(
            BufferContext bufferContext,
            CompletionTriggerKind triggerKind,
            String triggerCharacter,
            long generation) {
        if (!supportsCompletion()) {
            return null;
        }
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        String prefix = currentCompletionPrefix(bufferContext);
        int replacementEnd = cursor;
        int replacementStart = replacementEnd - prefix.length();
        return new CompletionRequestSnapshot(
                bufferContext,
                bufferContext.getBuffer().getURI().toString(),
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                cursor,
                getPosition(bufferContext, cursor),
                prefix,
                replacementStart,
                replacementEnd,
                triggerKind,
                triggerCharacter,
                generation);
    }

    private LspCompletionSession requestCompletionSession(CompletionRequestSnapshot snapshot) {
        if (!supportsCompletion() || !_completionRequests.isCurrent(snapshot)) {
            return null;
        }
        var params = new CompletionParams(
                snapshot.bufferContext().getBuffer().getTextDocumentID(),
                snapshot.position(),
                new CompletionContext(snapshot.triggerKind(), snapshot.triggerCharacter()));
        try {
            var completion = _server.getTextDocumentService().completion(params).get(400, TimeUnit.MILLISECONDS);
            List<CompletionItem> items;
            boolean incomplete;
            if (completion == null) {
                return null;
            }
            if (completion.isLeft()) {
                items = completion.getLeft();
                incomplete = false;
            } else {
                CompletionList list = completion.getRight();
                items = list == null ? List.of() : list.getItems();
                incomplete = list != null && list.isIncomplete();
            }
            if (items == null || items.isEmpty()) {
                return null;
            }
            return LspCompletionSession.create(
                    snapshot.bufferContext(),
                    snapshot.prefix(),
                    snapshot.replacementStart(),
                    snapshot.replacementEnd(),
                    items,
                    incomplete);
        } catch (Exception e) {
            _log.debug("Completion request failed", e);
            return null;
        }
    }

    private void showCompletionSession(LspCompletionSession session) {
        synchronized (_completionLock) {
            _completionSession = session;
        }
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        synchronized (_completionLock) {
            if (_completionPopupView == null || _completionPopupView.getParent() == null) {
                _completionPopupView = new CompletionPopupView(Rect.create(0, 0, 0, 0));
                window.getRootView().addSubview(_completionPopupView);
            }
            _completionPopupView.setSession(session);
        }
        window.getRootView().setNeedsRedraw();
    }

    private void runOnEventThread(Runnable action) {
        var eventThread = EventThread.getInstance();
        if (eventThread.isAlive()) {
            eventThread.enqueue(new RunnableEvent(action));
        } else {
            action.run();
        }
    }

    private boolean completionRequestStillMatchesBuffer(CompletionRequestSnapshot snapshot) {
        var buffer = snapshot.bufferContext().getBuffer();
        return snapshot.uri().equals(buffer.getURI().toString())
                && buffer.getVersionedTextDocumentID().getVersion() == snapshot.version()
                && buffer.getCursor().getPosition() == snapshot.cursor();
    }

    private void refreshCompletion(
            BufferContext bufferContext,
            CompletionTriggerKind triggerKind,
            String triggerCharacter) {
        _completionRequests.request(
                "completion",
                generation -> createCompletionRequestSnapshot(bufferContext, triggerKind, triggerCharacter, generation),
                this::flushPendingDocumentChanges,
                this::requestCompletionSession,
                this::completionRequestStillMatchesBuffer,
                (snapshot, session) -> showCompletionSession(session),
                () -> cancelCompletion());
    }

    @Override
    public boolean triggerCompletion(BufferContext bufferContext) {
        if (!supportsCompletion()) {
            return false;
        }
        refreshCompletion(bufferContext, CompletionTriggerKind.Invoked, null);
        return true;
    }

    @Override
    public void handleInsertedCharacter(BufferContext bufferContext, char insertedCharacter) {
        if (hasActiveSnippet()) {
            return;
        }
        if (!supportsCompletion()) {
            return;
        }
        if (Character.isWhitespace(insertedCharacter)) {
            cancelCompletion();
            return;
        }
        if (Character.isJavaIdentifierPart(insertedCharacter)) {
            refreshCompletion(bufferContext, CompletionTriggerKind.Invoked, null);
        } else if (isCompletionTriggerCharacter(insertedCharacter)) {
            refreshCompletion(bufferContext, CompletionTriggerKind.TriggerCharacter, Character.toString(insertedCharacter));
        } else {
            cancelCompletion();
        }
    }

    @Override
    public void handleBackspace(BufferContext bufferContext) {
        if (hasActiveSnippet()) {
            return;
        }
        if (!supportsCompletion()) {
            return;
        }
        if (shouldRequestCompletion(bufferContext)) {
            refreshCompletion(bufferContext, CompletionTriggerKind.Invoked, null);
        } else {
            cancelCompletion();
        }
    }

    @Override
    public boolean hasCompletionSession() {
        synchronized (_completionLock) {
            return _completionSession != null;
        }
    }

    @Override
    public boolean selectNextCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.moveSelection(1);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

    @Override
    public boolean selectPreviousCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.moveSelection(-1);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

    @Override
    public boolean pageNextCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.pageSelection(1, LspCompletionSession.DEFAULT_VISIBLE_ROWS);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

    @Override
    public boolean pagePreviousCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.pageSelection(-1, LspCompletionSession.DEFAULT_VISIBLE_ROWS);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

    @Override
    public boolean isCommitCharacter(char character) {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            var selected = _completionSession.getSelectedEntry();
            if (selected == null) {
                return false;
            }
            String value = Character.toString(character);
            var item = selected.getItem();
            if (item.getCommitCharacters() != null && item.getCommitCharacters().contains(value)) {
                return true;
            }
            CompletionOptions options = _capabilities == null ? null : _capabilities.getCompletionProvider();
            return options != null
                    && options.getAllCommitCharacters() != null
                    && options.getAllCommitCharacters().contains(value);
        }
    }

    @Override
    public boolean cancelCompletion() {
        CompletionPopupView popupView;
        _completionRequests.cancelPending();
        synchronized (_completionLock) {
            if (_completionSession == null && _completionPopupView == null) {
                return false;
            }
            _completionSession = null;
            popupView = _completionPopupView;
            _completionPopupView = null;
        }
        if (popupView != null) {
            popupView.removeFromParent();
        }
        var window = Window.getInstance();
        if (window != null) {
            window.getRootView().setNeedsRedraw();
        }
        return true;
    }

    public void goToDefinition(BufferContext bufferContext) {
        cancelDefinitionMenu();
        _features.goToDefinition(bufferContext);
    }

    public void findReferences(BufferContext bufferContext) {
        cancelDefinitionMenu();
        _features.findReferences(bufferContext);
    }

    public void showHover(BufferContext bufferContext) {
        _features.showHover(bufferContext);
    }

    public void showSignatureHelp(BufferContext bufferContext) {
        _features.showSignatureHelp(bufferContext);
    }

    public void goToDeclaration(BufferContext bufferContext) {
        cancelDefinitionMenu();
        _features.goToDeclaration(bufferContext);
    }

    public void goToTypeDefinition(BufferContext bufferContext) {
        cancelDefinitionMenu();
        _features.goToTypeDefinition(bufferContext);
    }

    public void goToImplementation(BufferContext bufferContext) {
        cancelDefinitionMenu();
        _features.goToImplementation(bufferContext);
    }

    public void showDocumentHighlights(BufferContext bufferContext) {
        _features.showDocumentHighlights(bufferContext);
    }

    public void showDocumentSymbols(BufferContext bufferContext) {
        _features.showDocumentSymbols(bufferContext);
    }

    public void promptWorkspaceSymbols(BufferContext bufferContext) {
        _features.promptWorkspaceSymbols(bufferContext);
    }

    public void showCodeActions(BufferContext bufferContext) {
        _features.showCodeActions(bufferContext);
    }

    public void formatDocument(BufferContext bufferContext) {
        _features.formatDocument(bufferContext);
    }

    public void formatCurrentLine(BufferContext bufferContext) {
        _features.formatCurrentLine(bufferContext);
    }

    public void formatOnType(BufferContext bufferContext) {
        _features.formatOnType(bufferContext);
    }

    public void rename(BufferContext bufferContext) {
        _features.promptRename(bufferContext);
    }

    public void showInlayHints(BufferContext bufferContext) {
        _features.showInlayHints(bufferContext);
    }

    public void applyFoldingRanges(BufferContext bufferContext) {
        _features.applyFoldingRanges(bufferContext);
    }

    public void showSelectionRanges(BufferContext bufferContext) {
        _features.showSelectionRanges(bufferContext);
    }

    public void showCallHierarchy(BufferContext bufferContext) {
        _features.showCallHierarchy(bufferContext);
    }

    public void showTypeHierarchy(BufferContext bufferContext) {
        _features.showTypeHierarchy(bufferContext);
    }

    public void showDocumentLinks(BufferContext bufferContext) {
        _features.showDocumentLinks(bufferContext);
    }

    public void showLinkedEditingRanges(BufferContext bufferContext) {
        _features.showLinkedEditingRanges(bufferContext);
    }

    public void showColorPresentations(BufferContext bufferContext) {
        _features.showColorPresentations(bufferContext);
    }

    public boolean cancelDefinitionMenu() {
        LspLocationPopupView popupView;
        synchronized (_definitionLock) {
            if (_definitionMenuSession == null && _definitionPopupView == null) {
                return false;
            }
            _definitionMenuSession = null;
            popupView = _definitionPopupView;
            _definitionPopupView = null;
        }
        if (popupView != null) {
            popupView.removeFromParent();
        }
        var window = Window.getInstance();
        if (window != null) {
            window.focusActiveBuffer();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
            window.refreshChromeState();
        }
        return true;
    }

    @Override
    public boolean hasActiveSnippet() {
        synchronized (_snippetLock) {
            return _snippetSession != null && _snippetSession.isActive();
        }
    }

    @Override
    public void cancelSnippet() {
        synchronized (_snippetLock) {
            _snippetSession = null;
        }
    }

    @Override
    public boolean jumpToNextSnippetStop(BufferContext bufferContext) {
        synchronized (_snippetLock) {
            if (_snippetSession == null) {
                return false;
            }
            _snippetSession.moveNext(bufferContext);
            if (!_snippetSession.isActive()) {
                _snippetSession = null;
            }
            return true;
        }
    }

    @Override
    public boolean jumpToPreviousSnippetStop(BufferContext bufferContext) {
        synchronized (_snippetLock) {
            if (_snippetSession == null) {
                return false;
            }
            return _snippetSession.movePrevious(bufferContext);
        }
    }

    @Override
    public boolean handleSnippetCharacter(BufferContext bufferContext, char character) {
        synchronized (_snippetLock) {
            if (_snippetSession == null) {
                return false;
            }
            var buffer = bufferContext.getBuffer();
            if (_snippetSession.replaceActivePlaceholder(bufferContext, character)) {
                return true;
            }
            int position = buffer.getCursor().getPosition();
            buffer.insert(Character.toString(character));
            _snippetSession.onInsert(position, 1);
            return true;
        }
    }

    @Override
    public void handleSnippetBackspace(int startPosition, int endPosition) {
        synchronized (_snippetLock) {
            if (_snippetSession != null) {
                _snippetSession.onRemove(startPosition, endPosition);
            }
        }
    }

    private CompletionItem resolveCompletionItem(CompletionItem item) {
        if (item == null || !supportsCompletion()) {
            return item;
        }
        CompletionOptions options = _capabilities.getCompletionProvider();
        if (options == null || !Boolean.TRUE.equals(options.getResolveProvider())) {
            return item;
        }
        try {
            return _server.getTextDocumentService().resolveCompletionItem(item).get(400, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            _log.debug("Completion resolve failed", e);
            return item;
        }
    }

    private TextEdit primaryCompletionEdit(BufferContext bufferContext, LspCompletionSession session, CompletionItem item) {
        if (item.getTextEdit() != null) {
            if (item.getTextEdit().isLeft()) {
                return item.getTextEdit().getLeft();
            }
            InsertReplaceEdit edit = item.getTextEdit().getRight();
            return edit == null ? null : new TextEdit(edit.getReplace(), edit.getNewText());
        }
        String newText = item.getInsertText();
        if (newText == null) {
            newText = item.getTextEditText();
        }
        if (newText == null) {
            newText = item.getLabel();
        }
        if (newText == null) {
            return null;
        }
        var range = new Range(
                getPosition(bufferContext, session.getReplacementStart()),
                getPosition(bufferContext, session.getReplacementEnd()));
        return new TextEdit(range, newText);
    }

    private boolean isSnippetCompletion(CompletionItem item) {
        return item != null && item.getInsertTextFormat() == InsertTextFormat.Snippet;
    }

    private JavaSnippetParser.ParseResult snippetParseResult(BufferContext bufferContext, CompletionItem item, TextEdit primaryEdit) {
        if (!isSnippetCompletion(item) || primaryEdit == null) {
            return null;
        }
        return JavaSnippetParser.parse(primaryEdit.getNewText(), bufferContext.getBuffer().getPath());
    }

    private int finalSnippetInsertionStart(int primaryStart, List<IndexedEdit> edits) {
        int result = primaryStart;
        for (var edit : edits) {
            if (edit._start < primaryStart) {
                result += edit._newText.replace("\t", "    ").length() - (edit._end - edit._start);
            }
        }
        return result;
    }

    private void activateSnippet(BufferContext bufferContext, JavaSnippetParser.ParseResult snippet, int insertionStart) {
        if (snippet == null) {
            cancelSnippet();
            return;
        }
        if (snippet.tabStops().isEmpty()) {
            bufferContext.getBuffer().getCursor().setPosition(insertionStart + snippet.finalCursorOffset());
            cancelSnippet();
            return;
        }
        synchronized (_snippetLock) {
            _snippetSession = JavaSnippetSession.fromParseResult(insertionStart, snippet);
            _snippetSession.activate(bufferContext);
        }
    }

    private void applyCompletionItem(BufferContext bufferContext, LspCompletionSession session, CompletionItem item) {
        if (item == null) {
            return;
        }
        var edits = new ArrayList<TextEdit>();
        TextEdit primaryEdit = primaryCompletionEdit(bufferContext, session, item);
        if (primaryEdit != null) {
            edits.add(primaryEdit);
        }
        if (item.getAdditionalTextEdits() != null) {
            edits.addAll(item.getAdditionalTextEdits());
        }
        if (!edits.isEmpty()) {
            var snippet = snippetParseResult(bufferContext, item, primaryEdit);
            if (snippet != null && primaryEdit != null) {
                int primaryStart = getIndex(bufferContext, primaryEdit.getRange().getStart());
                var appliedEdits = new ArrayList<TextEdit>();
                for (var edit : edits) {
                    if (edit == primaryEdit) {
                        appliedEdits.add(new TextEdit(edit.getRange(), snippet.text()));
                    } else {
                        appliedEdits.add(edit);
                    }
                }
                var indexedEdits = indexedEdits(bufferContext, appliedEdits);
                int insertionStart = finalSnippetInsertionStart(primaryStart, indexedEdits);
                applyIndexedEdits(bufferContext, indexedEdits);
                activateSnippet(bufferContext, snippet, insertionStart);
            } else {
                cancelSnippet();
                var workspaceEdit = new WorkspaceEdit();
                workspaceEdit.setChanges(Map.of(bufferContext.getBuffer().getURI().toString(), edits));
                applyWorkspaceEdit(bufferContext, workspaceEdit);
            }
        } else {
            cancelSnippet();
        }
        applyCommand(bufferContext, item.getCommand());
    }

    @Override
    public boolean acceptCompletion(BufferContext bufferContext) {
        LspCompletionSession session;
        synchronized (_completionLock) {
            session = _completionSession;
        }
        if (session == null || session.getSelectedEntry() == null) {
            return false;
        }
        CompletionItem item = resolveCompletionItem(session.getSelectedEntry().getItem());
        cancelCompletion();
        applyCompletionItem(bufferContext, session, item);
        return true;
    }

    @Override
    public boolean acceptCompletionWithCharacter(BufferContext bufferContext, char character) {
        if (!acceptCompletion(bufferContext)) {
            return false;
        }
        bufferContext.getBuffer().insert(Character.toString(character));
        handleInsertedCharacter(bufferContext, character);
        return true;
    }

    private static WorkspaceEdit decodeWorkspaceEdit(Object rawEdit) {
        if (rawEdit == null) {
            return null;
        }
        if (rawEdit instanceof WorkspaceEdit workspaceEdit) {
            return workspaceEdit;
        }
        return _gson.fromJson(_gson.toJsonTree(rawEdit), WorkspaceEdit.class);
    }

    private List<LspLocationMenuSession.Entry> requestDefinitionEntries(BufferContext bufferContext) throws Exception {
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var params = new DefinitionParams(bufferContext.getBuffer().getTextDocumentID(), getPosition(bufferContext, cursor));
        var response = _server.getTextDocumentService().definition(params).get(2, TimeUnit.SECONDS);
        if (response == null) {
            return List.of();
        }

        var entries = response.isLeft()
                ? definitionEntries(response.getLeft(), null)
                : definitionEntries(null, response.getRight());
        if (entries.isEmpty()) {
            return List.of();
        }

        var deduped = new LinkedHashMap<String, LspLocationMenuSession.Entry>();
        for (var entry : entries) {
            String key = entry.path().toAbsolutePath().normalize() + ":" + entry.position().getLine() + ":" + entry.position().getCharacter();
            deduped.putIfAbsent(key, entry);
        }
        return List.copyOf(deduped.values());
    }

    private List<LspLocationMenuSession.Entry> requestReferenceEntries(BufferContext bufferContext) throws Exception {
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var params = new ReferenceParams(
                bufferContext.getBuffer().getTextDocumentID(),
                getPosition(bufferContext, cursor),
                new ReferenceContext(true));
        var response = _server.getTextDocumentService().references(params).get(2, TimeUnit.SECONDS);
        if (response == null || response.isEmpty()) {
            return List.of();
        }
        var deduped = new LinkedHashMap<String, LspLocationMenuSession.Entry>();
        for (var location : response) {
            var entry = definitionEntry(location);
            if (entry == null) {
                continue;
            }
            String key = entry.path().toAbsolutePath().normalize() + ":" + entry.position().getLine() + ":" + entry.position().getCharacter();
            deduped.putIfAbsent(key, entry);
        }
        return List.copyOf(deduped.values());
    }

    private List<LspLocationMenuSession.Entry> definitionEntries(
            List<? extends Location> locations,
            List<? extends LocationLink> links) {
        var entries = new ArrayList<LspLocationMenuSession.Entry>();
        if (locations != null) {
            for (var location : locations) {
                var entry = definitionEntry(location);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        if (links != null) {
            for (var locationLink : links) {
                var entry = definitionEntry(locationLink);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private LspLocationMenuSession.Entry definitionEntry(Location location) {
        if (location == null || location.getRange() == null) {
            return null;
        }
        return definitionEntry(location.getUri(), location.getRange().getStart());
    }

    private LspLocationMenuSession.Entry definitionEntry(LocationLink locationLink) {
        if (locationLink == null) {
            return null;
        }
        var targetRange = locationLink.getTargetSelectionRange() != null
                ? locationLink.getTargetSelectionRange()
                : locationLink.getTargetRange();
        if (targetRange == null) {
            return null;
        }
        return definitionEntry(locationLink.getTargetUri(), targetRange.getStart());
    }

    private LspLocationMenuSession.Entry definitionEntry(String uri, Position position) {
        if (uri == null || position == null) {
            return null;
        }

        Path path;
        try {
            path = Paths.get(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }

        String location = displayPath(path) + ":" + (position.getLine() + 1) + ":" + (position.getCharacter() + 1);
        String preview = previewText(path, position.getLine());
        String label = preview.isBlank() ? location : location + "  " + preview;
        return new LspLocationMenuSession.Entry(label, path.toString(), path, position);
    }

    private String displayPath(Path path) {
        Path projectRoot = _projectPath != null ? _projectPath : ProjectPaths.getProjectRootPath(path);
        if (projectRoot != null) {
            try {
                if (path.startsWith(projectRoot)) {
                    return projectRoot.relativize(path).toString();
                }
            } catch (IllegalArgumentException e) {
            }
        }
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static String previewText(Path path, int lineIndex) {
        try {
            var lines = Files.readAllLines(path);
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                return "";
            }
            return lines.get(lineIndex).trim().replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "";
        }
    }

    private void showLocationMenu(BufferContext bufferContext, List<LspLocationMenuSession.Entry> entries, String title) {
        var window = Window.getInstance();
        if (window == null || window.getRootView() == null) {
            return;
        }

        var session = new LspLocationMenuSession(bufferContext, entries, title);
        synchronized (_definitionLock) {
            _definitionMenuSession = session;
            if (_definitionPopupView == null || _definitionPopupView.getParent() == null) {
                _definitionPopupView = new LspLocationPopupView(Rect.create(0, 0, 0, 0));
                _definitionPopupView.setOnAccept(this::acceptDefinitionSelection);
                _definitionPopupView.setOnCancel(this::cancelDefinitionMenu);
                window.getRootView().addSubview(_definitionPopupView);
            }
            _definitionPopupView.setSession(session);
        }
        window.getRootView().setFirstResponder(_definitionPopupView);
        window.refreshChromeState();
        window.getRootView().setNeedsRedraw();
    }

    private void acceptDefinitionSelection() {
        LspLocationMenuSession.Entry entry;
        synchronized (_definitionLock) {
            entry = _definitionMenuSession == null ? null : _definitionMenuSession.getSelectedEntry();
        }
        if (entry == null) {
            cancelDefinitionMenu();
            return;
        }
        jumpToDefinition(entry);
        cancelDefinitionMenu();
    }

    private void jumpToDefinition(LspLocationMenuSession.Entry entry) {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        try {
            Path targetPath = entry.path().toAbsolutePath().normalize();
            Path currentBufferPath = window.getBufferContext().getBuffer().getPath();
            Path currentPath = currentBufferPath == null ? null : currentBufferPath.toAbsolutePath().normalize();
            if ((currentPath == null || !currentPath.equals(targetPath)) && !window.setBufferPath(targetPath)) {
                setStatusMessage("Unable to open definition");
                return;
            }
            var targetContext = window.getBufferContext();
            int index = getIndex(targetContext, entry.position());
            targetContext.getBuffer().getCursor().setPosition(index);
            targetContext.getBufferView().adaptViewToCursor();
            targetContext.getBufferView().setNeedsRedraw();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
        } catch (Exception e) {
            _log.debug("Jump to definition failed", e);
            setStatusMessage("Unable to open definition");
        }
    }

    private void setStatusMessage(String message) {
        var window = Window.getInstance();
        if (window != null && window.getCommandView() != null) {
            window.getCommandView().setMessage(message);
        }
    }

    private static int getIndex(BufferContext context, Position position) {
        return context.getTextLayout().getIndexForPhysicalLineCharacter(position.getLine(), position.getCharacter());
    }

    private static boolean uriMatches(URI expectedUri, String candidateUri) {
        try {
            var candidate = new URI(candidateUri);
            if ("file".equalsIgnoreCase(expectedUri.getScheme()) && "file".equalsIgnoreCase(candidate.getScheme())) {
                return Paths.get(expectedUri).equals(Paths.get(candidate));
            }
            return expectedUri.equals(candidate);
        } catch (Exception e) {
            return false;
        }
    }

    private static class IndexedEdit {
        private final int _start;
        private final int _end;
        private final String _newText;

        IndexedEdit(int start, int end, String newText) {
            _start = start;
            _end = end;
            _newText = newText;
        }
    }

    private static List<IndexedEdit> indexedEdits(BufferContext context, List<? extends TextEdit> edits) {
        var indexedEdits = new ArrayList<IndexedEdit>();
        for (var edit : edits) {
            indexedEdits.add(new IndexedEdit(
                    getIndex(context, edit.getRange().getStart()),
                    getIndex(context, edit.getRange().getEnd()),
                    edit.getNewText()));
        }
        indexedEdits.sort(Comparator.comparingInt((IndexedEdit edit) -> edit._start).reversed()
                .thenComparing(Comparator.comparingInt((IndexedEdit edit) -> edit._end).reversed()));
        return indexedEdits;
    }

    private static void applyIndexedEdits(BufferContext context, List<IndexedEdit> edits) {
        var buffer = context.getBuffer();
        for (var edit : edits) {
            var newText = edit._newText.replace("\t", "    ");
            _log.debug("Insert " + newText + " at " + edit._start);
            _log.debug("Remove [" + edit._start + ", " + edit._end + "]");
            buffer.remove(edit._start, edit._end);
            buffer.insert(edit._start, newText);
        }
        if (!edits.isEmpty()) {
            buffer.commitUndo();
        }
    }

    static void applyWorkspaceEdit(BufferContext context, WorkspaceEdit workspaceEdit) {
        LspFeatureSupport.applyWorkspaceEditToOpenBuffersAndFiles(context, workspaceEdit);
    }

    private void applyCommand(BufferContext bufferContext, Command command) {
        if (command == null) {
            return;
        }
        switch (command.getCommand()) {
        case "java.apply.workspaceEdit":
            if (command.getArguments() != null && !command.getArguments().isEmpty()) {
                applyWorkspaceEdit(bufferContext, decodeWorkspaceEdit(command.getArguments().get(0)));
            }
            break;
        default:
            try {
                if (_server != null && _server.getWorkspaceService() != null) {
                    var params = new ExecuteCommandParams();
                    params.setCommand(command.getCommand());
                    params.setArguments(command.getArguments());
                    applyCommandResult(bufferContext, _server.getWorkspaceService().executeCommand(params).join());
                } else {
                    _log.debug("Ignoring unsupported command: " + command);
                }
            } catch (Exception e) {
                _log.debug("Executing code-action command failed", e);
            }
            break;
        }
    }

    private void applyCommandResult(BufferContext bufferContext, Object result) {
        if (result == null) {
            return;
        }
        if (result instanceof WorkspaceEdit workspaceEdit) {
            applyWorkspaceEdit(bufferContext, workspaceEdit);
            return;
        }
        if (result instanceof ApplyWorkspaceEditParams applyParams) {
            applyWorkspaceEdit(bufferContext, applyParams.getEdit());
            return;
        }
        WorkspaceEdit workspaceEdit = decodeWorkspaceEdit(result);
        if (hasWorkspaceEditChanges(workspaceEdit)) {
            applyWorkspaceEdit(bufferContext, workspaceEdit);
        }
    }

    private boolean applyCodeActionByTitle(BufferContext bufferContext, String title) {
        return applyCodeAction(bufferContext, getCodeActions(bufferContext), title, null);
    }

    private boolean applyCodeActionByTitleOrKind(
            BufferContext bufferContext,
            String title,
            String kind,
            List<String> only) {
        return applyCodeAction(bufferContext, getCodeActions(bufferContext, only), title, kind);
    }

    private boolean applyCodeAction(
            BufferContext bufferContext,
            List<Either<Command, CodeAction>> actions,
            String title,
            String kind) {
        for (var either : actions) {
            if (either.isLeft()) {
                var command = either.getLeft();
                if (matchesCodeActionTitle(title, command.getTitle())) {
                    _log.debug("Applying code action command: title=" + command.getTitle()
                            + ", command=" + command.getCommand());
                    applyCommand(bufferContext, command);
                    return true;
                }
            } else {
                var action = either.getRight();
                if (matchesCodeActionTitle(title, action.getTitle()) || matchesCodeActionKind(kind, action.getKind())) {
                    var resolved = resolveCodeAction(action);
                    boolean hasEdit = hasWorkspaceEditChanges(resolved.getEdit());
                    boolean hasCommand = resolved.getCommand() != null;
                    _log.debug("Applying code action: title=" + resolved.getTitle()
                            + ", kind=" + resolved.getKind()
                            + ", command=" + (resolved.getCommand() == null ? null : resolved.getCommand().getCommand())
                            + ", hasEdit=" + hasEdit);
                    if (!hasEdit && !hasCommand) {
                        _log.debug("Skipping code action without edit or command");
                        continue;
                    }
                    applyWorkspaceEdit(bufferContext, resolved.getEdit());
                    applyCommand(bufferContext, resolved.getCommand());
                    return true;
                }
            }
        }
        return false;
    }

    private CodeAction resolveCodeAction(CodeAction action) {
        if (action == null || hasWorkspaceEditChanges(action.getEdit()) || action.getCommand() != null
                || _server == null || _server.getTextDocumentService() == null) {
            return action;
        }
        try {
            _log.debug("Resolving code action: title=" + action.getTitle() + ", kind=" + action.getKind());
            var resolved = _server.getTextDocumentService().resolveCodeAction(action).join();
            if (resolved != null) {
                _log.debug("Resolved code action: title=" + resolved.getTitle()
                        + ", kind=" + resolved.getKind()
                        + ", command=" + (resolved.getCommand() == null ? null : resolved.getCommand().getCommand())
                        + ", hasEdit=" + hasWorkspaceEditChanges(resolved.getEdit()));
                return resolved;
            }
        } catch (Exception e) {
            _log.debug("Resolving code action failed", e);
        }
        return action;
    }

    private static boolean matchesCodeActionTitle(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    private static boolean matchesCodeActionKind(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private static boolean hasWorkspaceEditChanges(WorkspaceEdit workspaceEdit) {
        if (workspaceEdit == null) {
            return false;
        }
        return workspaceEdit.getChanges() != null && !workspaceEdit.getChanges().isEmpty()
                || workspaceEdit.getDocumentChanges() != null && !workspaceEdit.getDocumentChanges().isEmpty();
    }

    public void organizeImports(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        try {
            if (!applyCodeActionByTitleOrKind(bufferContext, "Organize imports",
                    CODE_ACTION_SOURCE_ORGANIZE_IMPORTS, List.of(CODE_ACTION_SOURCE_ORGANIZE_IMPORTS))) {
                applyCodeActionByTitle(bufferContext, "Organize imports");
            }
        } catch (Exception e) {
            _log.error("Exception: ", e);
        }
    }

    public void makeFinal(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        try {
            applyCodeActionByTitle(bufferContext, "Change modifiers to final where possible");
        } catch (Exception e) {
            _log.error("Exception: ", e);
        }
    }

    public void generateAccessors(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        try {
            applyCodeActionByTitle(bufferContext, "Generate Getters and Setters");
        } catch (Exception e) {
            _log.error("Exception: ", e);
        }
    }

    public void generateToString(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        try {
            applyCodeActionByTitle(bufferContext, "Generate toString()...");
        } catch (Exception e) {
            _log.error("Exception: ", e);
        }
    }

    @Override
    public void willSave(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        _log.debug("willSave");
        String uri = bufferContext.getBuffer().getURI().toString();
        var params = new WillSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setReason(TextDocumentSaveReason.Manual);
        enqueueLspRequest("willSave", () -> {
            flushPendingDocumentChanges(uri);
            _server.getTextDocumentService().willSave(params);
        });
    }

    @Override
    public void didSave(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        _log.debug("didSave");
        String uri = bufferContext.getBuffer().getURI().toString();
        var params = new DidSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setText(bufferContext.getBuffer().getString());
        enqueueLspRequest("didSave", () -> {
            flushPendingDocumentChanges(uri);
            _server.getTextDocumentService().didSave(params);
        });
        scheduleSemanticHighlightRefresh(bufferContext);
        _features.refreshDocumentContext(bufferContext);
    }

    @Override
    public void didOpen(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        _log.debug("didOpen");
        var params = new DidOpenTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocument());
        enqueueLspRequest("didOpen", () -> _server.getTextDocumentService().didOpen(params));
        scheduleSemanticHighlightRefresh(bufferContext);
        _features.refreshDocumentContext(bufferContext);
    }

    @Override
    public void didClose(BufferContext bufferContext) {
        cancelSnippet();
        _features.clearDocumentContext(bufferContext);
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        DiagnosticService.getInstance().clear(DIAGNOSTIC_PROVIDER_ID, bufferContext.getBuffer().getURI().toString());
        cancelCompletion();
        _log.debug("didClose");
        String uri = bufferContext.getBuffer().getURI().toString();
        var params = new DidCloseTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        enqueueLspRequest("didClose", () -> {
            flushPendingDocumentChanges(uri);
            _server.getTextDocumentService().didClose(params);
        });
        refreshDiagnosticsUi();
    }

    @Override
    public void didInsert(BufferContext bufferContext, int position, String text) {
        if (!_enabled || _server == null) {
            return;
        }
        recordSemanticInsert(bufferContext, position, text == null ? 0 : text.length());
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var line = bufferContext.getTextLayout().getPhysicalLineAt(position);
        var lineIndex = line.getY();
        var charIndex = position - line.getStartPosition();
        _log.debug("didInsert " + text + " at " + position + " (" + lineIndex + ", " + charIndex + ")");
        var range = new Range(new Position(lineIndex, charIndex), new Position(lineIndex, charIndex));
        contentChanges.add(new TextDocumentContentChangeEvent(range, 0, text));
        var textDocument = bufferContext.getBuffer().getVersionedTextDocumentID();
        queueDocumentChanges(bufferContext, textDocument, contentChanges);
        scheduleSemanticHighlightRefresh(bufferContext);
        _features.refreshDocumentContext(bufferContext);
    }

    @Override
    public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
        if (!_enabled || _server == null) {
            return;
        }
        recordSemanticDelete(bufferContext, startPosition, endPosition);
        _log.debug("didRemove at " + startPosition + ", " + endPosition);
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var startLine = bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
        var startLineIndex = startLine.getY();
        var startIndex = startPosition - startLine.getStartPosition();
        var endLine = bufferContext.getTextLayout().getPhysicalLineAt(endPosition);
        var endLineIndex = endLine.getY();
        var endIndex = endPosition - endLine.getStartPosition();
        var range = new Range(new Position(startLineIndex, startIndex), new Position(endLineIndex, endIndex));
        contentChanges.add(new TextDocumentContentChangeEvent(range, endPosition - startPosition, ""));
        var textDocument = bufferContext.getBuffer().getVersionedTextDocumentID();
        queueDocumentChanges(bufferContext, textDocument, contentChanges);
        scheduleSemanticHighlightRefresh(bufferContext);
        _features.refreshDocumentContext(bufferContext);
    }

    public ServerCapabilities getCapabilities() {
        return _capabilities;
    }

    public TextColor foregroundColourForScope(int scope) {
        return UiTheme.SEMANTIC_KEYWORD;
    }

    private boolean supportsSemanticTokens() {
        return _enabled
                && _server != null
                && _capabilities != null
                && _capabilities.getSemanticTokensProvider() != null;
    }

    private TextColor semanticTokenColor(String tokenType, int modifiersBitset, List<String> modifiers) {
        if (hasModifier(modifiersBitset, modifiers, "deprecated")) {
            return UiTheme.SEMANTIC_KEYWORD;
        }
        if (hasModifier(modifiersBitset, modifiers, "readonly")) {
            return UiTheme.SEMANTIC_READONLY;
        }
        return switch (tokenType) {
        case "namespace", "decorator" -> UiTheme.SEMANTIC_NAMESPACE;
        case "type", "class", "enum", "interface", "struct" -> UiTheme.SEMANTIC_TYPE;
        case "typeParameter", "parameter" -> UiTheme.SEMANTIC_PARAMETER;
        case "property", "enumMember", "event" -> UiTheme.SEMANTIC_MEMBER;
        case "function", "method", "macro" -> UiTheme.SEMANTIC_FUNCTION;
        case "keyword", "modifier" -> UiTheme.SEMANTIC_KEYWORD;
        case "comment" -> UiTheme.SEMANTIC_COMMENT;
        case "string" -> UiTheme.SEMANTIC_STRING;
        case "number", "regexp" -> UiTheme.SEMANTIC_NUMBER;
        case "operator" -> TextColor.ANSI.DEFAULT;
        default -> TextColor.ANSI.DEFAULT;
        };
    }

    private boolean hasModifier(int modifiersBitset, List<String> modifiers, String name) {
        int index = modifiers.indexOf(name);
        return index >= 0 && ((modifiersBitset >> index) & 1) == 1;
    }

    static List<SemanticHighlight> decodeSemanticHighlights(BufferContext bufferContext, SemanticTokens tokens, SemanticTokensLegend legend) {
        String text = bufferContext == null || bufferContext.getBuffer() == null
                ? ""
                : bufferContext.getBuffer().getString();
        return decodeSemanticHighlights(text, tokens, legend);
    }

    static List<SemanticHighlight> decodeSemanticHighlights(String text, SemanticTokens tokens, SemanticTokensLegend legend) {
        return toSemanticHighlights(AsyncSemanticTokenHighlighter.decodeSemanticHighlights(
                text,
                tokens,
                legend,
                JavaLSPClient.getInstance()::semanticTokenColor,
                _log));
    }

    private List<SemanticHighlight> getSemanticHighlights(BufferContext bufferContext) {
        return toSemanticHighlights(_semanticTokens.getHighlights(semanticDocument(bufferContext)));
    }

    private List<AsyncSemanticTokenHighlighter.Highlight> fetchSemanticHighlights(AsyncSemanticTokenHighlighter.Snapshot snapshot) {
        try {
            var legend = _capabilities.getSemanticTokensProvider().getLegend();
            var params = new SemanticTokensParams(new TextDocumentIdentifier(snapshot.uri()));
            var tokens = _server.getTextDocumentService().semanticTokensFull(params).get(2, TimeUnit.SECONDS);
            return AsyncSemanticTokenHighlighter.decodeSemanticHighlights(
                    snapshot.text(),
                    tokens,
                    legend,
                    this::semanticTokenColor,
                    _log);
        } catch (Exception e) {
            _log.debug("Semantic token request failed", e);
            return List.of();
        }
    }

    private void scheduleSemanticHighlightRefresh(BufferContext bufferContext) {
        _semanticTokens.scheduleRefresh(semanticDocument(bufferContext));
    }

    private void recordSemanticInsert(BufferContext bufferContext, int position, int length) {
        _semanticTokens.recordInsert(semanticDocument(bufferContext), position, length);
    }

    private void recordSemanticDelete(BufferContext bufferContext, int start, int end) {
        _semanticTokens.recordDelete(semanticDocument(bufferContext), start, end);
    }

    private AsyncSemanticTokenHighlighter.Document semanticDocument(BufferContext bufferContext) {
        if (bufferContext == null || bufferContext.getBuffer() == null) {
            return null;
        }
        return new AsyncSemanticTokenHighlighter.Document() {
            @Override
            public String uri() {
                return bufferContext.getBuffer().getURI().toString();
            }

            @Override
            public int version() {
                return bufferContext.getBuffer().getVersionedTextDocumentID().getVersion();
            }

            @Override
            public String text() {
                return bufferContext.getBuffer().getString();
            }

            @Override
            public void requestSemanticRedraw() {
                JavaLSPClient.this.requestSemanticRedraw(bufferContext);
            }
        };
    }

    private static List<SemanticHighlight> toSemanticHighlights(
            List<AsyncSemanticTokenHighlighter.Highlight> highlights) {
        if (highlights == null || highlights.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<SemanticHighlight>(highlights.size());
        for (var highlight : highlights) {
            result.add(new SemanticHighlight(highlight.start(), highlight.end(), highlight.foregroundColor()));
        }
        return List.copyOf(result);
    }

    private void requestSemanticRedraw(BufferContext bufferContext) {
        Runnable redraw = () -> {
            bufferContext.getBuffer().invalidateAttributedStringCache();
            bufferContext.getBufferView().setNeedsRedraw();
            var window = Window.getInstance();
            if (window != null && window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
        };
        var eventThread = EventThread.getInstance();
        if (eventThread.isAlive()) {
            eventThread.enqueue(new RunnableEvent(redraw));
        } else {
            redraw.run();
        }
    }

    private static Pattern _javaCommentPattern = Pattern.compile("(/\\*([^*]|[\\n]|(\\*+([^*/]|[\\n])))*\\*+/)|(//.*)", Pattern.MULTILINE);
    private static Pattern _javaStringPattern = Pattern.compile("\\\"([^\\\\\\\"]|(\\\\.))*\\\"", Pattern.MULTILINE);
    private static Pattern _javaCharacterPattern = Pattern.compile("'[^']*'", Pattern.MULTILINE);
    private static Pattern _javaKeywordPattern = Pattern.compile(
            "(\\bprivate\\b)|(\\bprotected\\b)|(\\bpublic\\b)|(\\bstatic\\b)|(\\babstract\\b)|" +
                    "(\\bvoid\\b)|(\\bbyte\\b)|(\\bchar\\b)|(\\bboolean\\b)|(\\bshort\\b)|(\\bint\\b)|(\\blong\\b)|(\\bfloat\\b)|" +
                    "(\\bdouble\\b)|(\\bimplements\\b)|(\\bextends\\b)|(\\bclass\\b)|(\\benum\\b)|(\\bfinal\\b)|" +
                    "(\\btry\\b)|(\\bcatch\\b)|(\\bthrows\\b)|(\\bthrow\\b)|(\\brecord\\b)|(\\bnew\\b)|(\\breturn\\b)|" +
                    "(\\bif\\b)|(\\belse\\b)|(\\bfor\\b)|(\\bwhile\\b)|(\\bdo\\b)|(\\bimport\\b)|(\\bpackage\\b)|" +
                    "(\\bcase\\b)|(\\bbreak\\b)|(\\bthis\\b)|(\\bsynchronized\\b)|(\\bvar\\b)|(\\bdefault\\b)",
            Pattern.MULTILINE);
    private static Pattern _javaKeywordTokenPattern = Pattern.compile("(\\bnull\\b)|(\\btrue\\b)|(\\bfalse\\b)", Pattern.MULTILINE);

    private void collectTokenRanges(
            List<AttributedString.FormatRange> ranges,
            String string,
            Pattern pattern,
            TextColor colour) {
        try {
            var matcher = pattern.matcher(string);
            while (matcher.find()) {
                ranges.add(new AttributedString.FormatRange(
                        matcher.start(),
                        matcher.end(),
                        colour,
                        TextColor.ANSI.DEFAULT));
            }
        } catch (Throwable e) {
        }
    }

    @Override
    public void applyColouring(BufferContext bufferContext, AttributedString str) {
        var string = str.toString();
        var ranges = new ArrayList<AttributedString.FormatRange>();
        collectTokenRanges(ranges, string, _javaKeywordPattern, UiTheme.SEMANTIC_KEYWORD);
        collectTokenRanges(ranges, string, _javaKeywordTokenPattern, UiTheme.SEMANTIC_NAMESPACE);
        collectTokenRanges(ranges, string, _javaCharacterPattern, UiTheme.SEMANTIC_STRING);
        collectTokenRanges(ranges, string, _javaCommentPattern, UiTheme.SEMANTIC_COMMENT);
        collectTokenRanges(ranges, string, _javaStringPattern, UiTheme.SEMANTIC_STRING);
        if (bufferContext != null) {
            for (var highlight : getSemanticHighlights(bufferContext)) {
                ranges.add(new AttributedString.FormatRange(
                        highlight.start(),
                        highlight.end(),
                        highlight.foregroundColor(),
                        TextColor.ANSI.DEFAULT));
            }
        }
        str.format(ranges);
    }

    @Override
    public boolean canReuseAttributedStringCacheAfterEdit(BufferContext bufferContext) {
        return true;
    }

    private static Pattern _bracketPattern = Pattern.compile("\\{|\\}");

    @Override
    public int getIndentationLevel(BufferContext bufferContext) {
        int indentation = 0;
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var matcher = _bracketPattern.matcher(bufferContext.getBuffer().getString());
        while (matcher.find()) {
            if (matcher.start() >= cursor) {
                return indentation;
            }
            if (matcher.group(0).equals("{")) {
                ++indentation;
            }
            if (matcher.group(0).equals("}")) {
                --indentation;
            }
        }
        return indentation;
    }

    @Override
    public boolean isIndentationEnd(BufferContext bufferContext, String character) {
        return character.equals("}");
    }

    @Override
    public String getIndentationString(BufferContext bufferContext) {
        return Settings.getIndentationString("java");
    }

    @Override
    public TextDocumentItem getTextDocument(BufferContext bufferContext) {
        return new TextDocumentItem(
                bufferContext.getBuffer().getPath().toFile().toURI().toString(),
                "java",
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                bufferContext.getBuffer().getString());
    }

    @Override
    public List<DiagnosticAction> diagnosticActions(
            BufferContext bufferContext,
            int logicalLine,
            List<DiagnosticEntry> lineDiagnostics) {
        if (!_enabled || _server == null || bufferContext == null) {
            return List.of();
        }
        var range = logicalLineRange(bufferContext, logicalLine);
        if (range == null) {
            return List.of();
        }
        var diagnostics = new ArrayList<Diagnostic>();
        for (var entry : lineDiagnostics) {
            diagnostics.add(entry.diagnostic());
        }
        var actions = requestCodeActions(bufferContext, range, diagnostics);
        if (actions.isEmpty()) {
            return List.of();
        }
        var deduped = new LinkedHashMap<String, DiagnosticAction>();
        for (var either : actions) {
            if (either.isLeft()) {
                var command = either.getLeft();
                deduped.putIfAbsent(command.getTitle(),
                        new DiagnosticAction(command.getTitle(), command.getCommand(),
                                () -> applyCommand(bufferContext, command)));
            } else {
                var action = either.getRight();
                deduped.putIfAbsent(action.getTitle(),
                        new DiagnosticAction(action.getTitle(), action.getKind(),
                                () -> {
                                    applyWorkspaceEdit(bufferContext, action.getEdit());
                                    applyCommand(bufferContext, action.getCommand());
                                }));
            }
        }
        return List.copyOf(deduped.values());
    }

    private static Range logicalLineRange(BufferContext bufferContext, int logicalLine) {
        if (logicalLine < 0) {
            return null;
        }
        String text = bufferContext.getBuffer().getString();
        int line = 0;
        int index = 0;
        int lineStart = 0;
        while (index < text.length() && line < logicalLine) {
            if (text.charAt(index++) == '\n') {
                line++;
                lineStart = index;
            }
        }
        if (line != logicalLine) {
            if (logicalLine == 0) {
                lineStart = 0;
            } else {
                return null;
            }
        }
        int lineEnd = lineStart;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        return new Range(new Position(logicalLine, 0), new Position(logicalLine, Math.max(0, lineEnd - lineStart)));
    }

    private static Path pathForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return Paths.get(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static void refreshDiagnosticsUi() {
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            var window = Window.getInstance();
            if (window == null) {
                return;
            }
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
            window.refreshChromeState();
        }));
    }

}
