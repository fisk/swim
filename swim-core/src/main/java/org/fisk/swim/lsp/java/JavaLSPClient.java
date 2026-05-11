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
import java.util.List;
import java.util.Locale;
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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
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
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentEdit;
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
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.CompletionPopupView;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.googlecode.lanterna.TextColor;

public class JavaLSPClient extends Thread implements LanguageMode {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();

    private final Object _lock = new Object();
    private boolean _started = false;
    private boolean _startupComplete = false;
    private boolean _launchAttempted = false;
    private boolean _enabled = true;
    private Throwable _startupError;
    private Thread _workerThread;

    private LanguageServer _server;
    private ServerCapabilities _capabilities;
    private Thread _shutdownHook;
    private AutoCloseable _providerConnection;

    private Path _projectPath;
    private Path _workspacePath;
    private Path _swimHomePath = Paths.get(System.getProperty("user.home"), ".swim");
    private final JavaLspProvider _provider;
    private volatile String _providerDescription = "";

    private final Map<String, TextColor> _foregroundColours = new HashMap<>();
    private final Map<String, StringBuilder> _outputBuffers = new HashMap<>();
    private final Map<String, CachedSemanticTokens> _semanticTokensCache = new HashMap<>();
    private final Object _completionLock = new Object();
    private final Object _snippetLock = new Object();

    private JavaCompletionSession _completionSession;
    private CompletionPopupView _completionPopupView;
    private JavaSnippetSession _snippetSession;

    static record SemanticHighlight(int start, int end, TextColor foregroundColor) {
    }

    private static record CachedSemanticTokens(int version, List<SemanticHighlight> highlights) {
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
            _log.info("No LSP support");
            _enabled = false;
        }
        initColours();
    }

    private static JavaLspProvider createDefaultProvider() {
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        String override = System.getProperty("swim.java.lsp.provider", "").trim().toLowerCase(Locale.ROOT);
        if ("embedded".equals(override)) {
            return new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        }
        if ("process".equals(override)) {
            return new OracleNbcodeLspProvider(extensionPath);
        }
        return new EmbeddedOracleModuleLayerLspProvider(extensionPath);
    }

    static boolean prefersProcessProvider(String osName) {
        if (osName == null) {
            return false;
        }
        String normalized = osName.toLowerCase(Locale.ROOT);
        return normalized.contains("mac") || normalized.contains("darwin");
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
        return OracleNbcodeLspProvider.findOracleExtensionPath(extensionsDir);
    }

    static String getNbcodeExecutableName(String osName, String arch) {
        return OracleNbcodeLspProvider.getNbcodeExecutableName(osName, arch);
    }

    static Path findNbcode(Path oracleExtensionPath, String osName, String arch) {
        return OracleNbcodeLspProvider.findNbcode(oracleExtensionPath, osName, arch);
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
        }), TextColor.ANSI.RED);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.other.autoboxing.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "storage.modifier.final.java",
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.YELLOW);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.YELLOW);
        _foregroundColours.put(String.join(":", new String[] {
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "meta.definition.variable.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.method.identifier.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.static.java",
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.abstract.java",
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "constant.other.key.java",
                "meta.declaration.annotation.java",
                "source.java"
        }), TextColor.ANSI.CYAN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.function-call.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.parameter.java",
                "meta.method.identifier.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.MAGENTA);
        _foregroundColours.put(String.join(":", new String[] {
                "variable.other.definition.java",
                "meta.definition.variable.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.MAGENTA);
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
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.function.java",
                "meta.method.identifier.java",
                "meta.function-call.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.BLUE);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.type.generic.java",
                "meta.definition.class.implemented.interfaces.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.MAGENTA);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.modifier.abstract.java",
                "entity.name.type.class.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.class.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.enum.java",
                "meta.enum.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "storage.type.annotation.java",
                "meta.declaration.annotation.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "entity.name.type.interface.java",
                "meta.class.identifier.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.GREEN);
        _foregroundColours.put(String.join(":", new String[] {
                "constant.numeric.decimal.java",
                "meta.definition.variable.java",
                "meta.method.body.java",
                "meta.method.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.MAGENTA);
        _foregroundColours.put(String.join(":", new String[] {
                "keyword.other.var.java",
                "meta.class.body.java",
                "meta.class.java",
                "source.java"
        }), TextColor.ANSI.RED);
    }

    private synchronized void clearSemanticTokensCache() {
        _semanticTokensCache.clear();
    }

    private synchronized void clearSemanticTokensCache(String uri) {
        if (uri != null) {
            _semanticTokensCache.remove(uri);
        }
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
                _log.info("telemetryEvent called");
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
                _log.info("publishDiagnostics called");
            }

            @Override
            public void showMessage(MessageParams message) {
                _log.info("showMessage: " + message.getMessage());
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                _log.info("showMessageRequest called");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void logMessage(MessageParams message) {
                _log.info("logMessage: " + message.getMessage());
            }

            @Override
            public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
                return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(
                        _projectPath.toUri().toString(),
                        _projectPath.getFileName().toString())));
            }

            @Override
            public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
                _log.info("Configuration?");
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
                _log.info("Workspace edit?");
                return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
            }

            @Override
            public CompletableFuture<Void> registerCapability(RegistrationParams params) {
                _log.info("Register capability?");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
                _log.info("Unregister capability?");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
                _log.info("createProgress: " + params);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void notifyProgress(ProgressParams params) {
                _log.info("notifyProgress: " + params);
            }

            public void languageStatus(Object params) {
                _log.info("language/status: " + params);
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
                    _log.info("oracle-java output/show: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("output/close")
            public CompletableFuture<Void> closeOutput(String outputName) {
                if (outputName != null) {
                    _outputBuffers.remove(outputName);
                    _log.info("oracle-java output/close: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("output/reset")
            public CompletableFuture<Void> resetOutput(String outputName) {
                if (outputName != null) {
                    _outputBuffers.put(outputName, new StringBuilder());
                    _log.info("oracle-java output/reset: " + outputName);
                }
                return CompletableFuture.completedFuture(null);
            }

            @JsonRequest("window/showHtmlPage")
            public CompletableFuture<Void> showHtmlPage(Object params) {
                _log.info("oracle-java window/showHtmlPage: " + params);
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
        _log.info("oracle-java output[" + outputName + "]: " + message);
    }

    private void setup() throws IOException {
        Files.createDirectories(_workspacePath);
        _log.info("LSP workspace path: " + _projectPath);
        _log.info("LSP workspace folder path: " + _workspacePath);
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
            _log.info("Java LSP provider: " + session.description());
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

    public synchronized void shutdown() {
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
        cancelCompletion();
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
            _log.info("Decorate buffer");
            var colorParams = new DocumentColorParams(bufferContext.getBuffer().getTextDocumentID());
            return _server.getTextDocumentService().documentColor(colorParams).join();
        } catch (Exception e) {
            _log.error("Error getting colours: ", e);
            throw new RuntimeException("Error getting code actions: ", e);
        }
    }

    public void codeLens(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        try {
            var params = new CodeLensParams();
            params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
            var result = _server.getTextDocumentService().codeLens(params).get();
            for (var item : result) {
                _log.info("Code lens item: " + item);
                var resolved = _server.getTextDocumentService().resolveCodeLens(item).get();
                _log.info("Resolved code lens item: " + resolved);
                var execute = new ExecuteCommandParams();
                execute.setCommand(resolved.getCommand().getCommand());
                execute.setArguments(resolved.getCommand().getArguments());
                var commandResult = _server.getWorkspaceService().executeCommand(execute).get();
                _log.info("Command result: " + commandResult);
            }
        } catch (InterruptedException | ExecutionException e) {
            _log.error("Code lens failed: ", e);
        }
    }

    private List<Either<Command, CodeAction>> getCodeActions(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return List.of();
        }
        try {
            _log.info("Get code actions");
            var lineCount = bufferContext.getTextLayout().getPhysicalLineCount();
            var line = bufferContext.getTextLayout().getLastPhysicalLine();
            var range = new Range(new Position(0, 0), new Position(lineCount - 1, line.getGlyphs().size()));
            var diagnostics = new ArrayList<Diagnostic>();
            var context = new CodeActionContext(diagnostics);
            var params = new CodeActionParams(bufferContext.getBuffer().getTextDocumentID(), range, context);
            _log.info("Code action: " + params);
            return _server.getTextDocumentService().codeAction(params).join();
        } catch (Exception e) {
            _log.error("Error getting code actions: ", e);
            throw new RuntimeException("Error getting code actions: ", e);
        }
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

    private JavaCompletionSession requestCompletionSession(
            BufferContext bufferContext,
            CompletionTriggerKind triggerKind,
            String triggerCharacter) {
        if (!supportsCompletion()) {
            return null;
        }
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var params = new CompletionParams(
                bufferContext.getBuffer().getTextDocumentID(),
                getPosition(bufferContext, cursor),
                new CompletionContext(triggerKind, triggerCharacter));
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
            String prefix = currentCompletionPrefix(bufferContext);
            int replacementEnd = bufferContext.getBuffer().getCursor().getPosition();
            int replacementStart = replacementEnd - prefix.length();
            return JavaCompletionSession.create(
                    bufferContext,
                    prefix,
                    replacementStart,
                    replacementEnd,
                    items,
                    incomplete);
        } catch (Exception e) {
            _log.debug("Completion request failed", e);
            return null;
        }
    }

    private void showCompletionSession(JavaCompletionSession session) {
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

    private void refreshCompletion(
            BufferContext bufferContext,
            CompletionTriggerKind triggerKind,
            String triggerCharacter) {
        JavaCompletionSession session = requestCompletionSession(bufferContext, triggerKind, triggerCharacter);
        if (session == null) {
            cancelCompletion();
            return;
        }
        showCompletionSession(session);
    }

    public boolean triggerCompletion(BufferContext bufferContext) {
        if (!supportsCompletion()) {
            return false;
        }
        refreshCompletion(bufferContext, CompletionTriggerKind.Invoked, null);
        synchronized (_completionLock) {
            return _completionSession != null;
        }
    }

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

    public boolean hasCompletionSession() {
        synchronized (_completionLock) {
            return _completionSession != null;
        }
    }

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

    public boolean pageNextCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.pageSelection(1, JavaCompletionSession.DEFAULT_VISIBLE_ROWS);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

    public boolean pagePreviousCompletion() {
        synchronized (_completionLock) {
            if (_completionSession == null) {
                return false;
            }
            _completionSession.pageSelection(-1, JavaCompletionSession.DEFAULT_VISIBLE_ROWS);
            if (_completionPopupView != null) {
                _completionPopupView.setNeedsRedraw();
            }
        }
        return true;
    }

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

    public boolean cancelCompletion() {
        CompletionPopupView popupView;
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

    public boolean hasActiveSnippet() {
        synchronized (_snippetLock) {
            return _snippetSession != null && _snippetSession.isActive();
        }
    }

    public void cancelSnippet() {
        synchronized (_snippetLock) {
            _snippetSession = null;
        }
    }

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

    public boolean jumpToPreviousSnippetStop(BufferContext bufferContext) {
        synchronized (_snippetLock) {
            if (_snippetSession == null) {
                return false;
            }
            return _snippetSession.movePrevious(bufferContext);
        }
    }

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

    private TextEdit primaryCompletionEdit(BufferContext bufferContext, JavaCompletionSession session, CompletionItem item) {
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

    private void applyCompletionItem(BufferContext bufferContext, JavaCompletionSession session, CompletionItem item) {
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

    public boolean acceptCompletion(BufferContext bufferContext) {
        JavaCompletionSession session;
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
            _log.info("Insert " + newText + " at " + edit._start);
            _log.info("Remove [" + edit._start + ", " + edit._end + "]");
            buffer.remove(edit._start, edit._end);
            buffer.insert(edit._start, newText);
        }
        if (!edits.isEmpty()) {
            buffer.getUndoLog().commit();
        }
    }

    static void applyWorkspaceEdit(BufferContext context, WorkspaceEdit workspaceEdit) {
        if (workspaceEdit == null) {
            return;
        }
        var currentUri = context.getBuffer().getURI();
        var edits = new ArrayList<IndexedEdit>();

        if (workspaceEdit.getChanges() != null) {
            for (var change : workspaceEdit.getChanges().entrySet()) {
                if (!uriMatches(currentUri, change.getKey())) {
                    continue;
                }
                for (var edit : change.getValue()) {
                    edits.add(new IndexedEdit(
                            getIndex(context, edit.getRange().getStart()),
                            getIndex(context, edit.getRange().getEnd()),
                            edit.getNewText()));
                }
            }
        }

        if (workspaceEdit.getDocumentChanges() != null) {
            for (var change : workspaceEdit.getDocumentChanges()) {
                if (!change.isLeft()) {
                    continue;
                }
                TextDocumentEdit edit = change.getLeft();
                if (!uriMatches(currentUri, edit.getTextDocument().getUri())) {
                    continue;
                }
                for (var textEdit : edit.getEdits()) {
                    edits.add(new IndexedEdit(
                            getIndex(context, textEdit.getRange().getStart()),
                            getIndex(context, textEdit.getRange().getEnd()),
                            textEdit.getNewText()));
                }
            }
        }

        edits.sort(Comparator.comparingInt((IndexedEdit edit) -> edit._start).reversed()
                .thenComparing(Comparator.comparingInt((IndexedEdit edit) -> edit._end).reversed()));
        applyIndexedEdits(context, edits);
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
            _log.info("Ignoring unsupported command: " + command);
            break;
        }
    }

    private boolean applyCodeActionByTitle(BufferContext bufferContext, String title) {
        for (var either : getCodeActions(bufferContext)) {
            if (either.isLeft()) {
                var command = either.getLeft();
                if (title.equals(command.getTitle())) {
                    applyCommand(bufferContext, command);
                    return true;
                }
            } else {
                var action = either.getRight();
                if (title.equals(action.getTitle())) {
                    applyWorkspaceEdit(bufferContext, action.getEdit());
                    applyCommand(bufferContext, action.getCommand());
                    return true;
                }
            }
        }
        return false;
    }

    public void organizeImports(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        try {
            applyCodeActionByTitle(bufferContext, "Organize imports");
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
        _log.info("willSave");
        var params = new WillSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setReason(TextDocumentSaveReason.Manual);
        _server.getTextDocumentService().willSave(params);
    }

    @Override
    public void didSave(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        _log.info("didSave");
        var params = new DidSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setText(bufferContext.getBuffer().getString());
        _server.getTextDocumentService().didSave(params);
    }

    @Override
    public void didOpen(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        _log.info("didOpen");
        var params = new DidOpenTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocument());
        _server.getTextDocumentService().didOpen(params);
    }

    @Override
    public void didClose(BufferContext bufferContext) {
        cancelSnippet();
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        cancelCompletion();
        _log.info("didClose");
        var params = new DidCloseTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        _server.getTextDocumentService().didClose(params);
    }

    @Override
    public void didInsert(BufferContext bufferContext, int position, String text) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var line = bufferContext.getTextLayout().getPhysicalLineAt(position);
        var lineIndex = line.getY();
        var charIndex = position - line.getStartPosition();
        _log.info("didInsert " + text + " at " + position + " (" + lineIndex + ", " + charIndex + ")");
        var range = new Range(new Position(lineIndex, charIndex), new Position(lineIndex, charIndex));
        contentChanges.add(new TextDocumentContentChangeEvent(range, 0, text));
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(contentChanges);
        _server.getTextDocumentService().didChange(params);
    }

    @Override
    public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        _log.info("didRemove at " + startPosition + ", " + endPosition);
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var startLine = bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
        var startLineIndex = startLine.getY();
        var startIndex = startPosition - startLine.getStartPosition();
        var endLine = bufferContext.getTextLayout().getPhysicalLineAt(endPosition);
        var endLineIndex = endLine.getY();
        var endIndex = endPosition - endLine.getStartPosition();
        var range = new Range(new Position(startLineIndex, startIndex), new Position(endLineIndex, endIndex));
        contentChanges.add(new TextDocumentContentChangeEvent(range, endPosition - startPosition, ""));
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(contentChanges);
        _server.getTextDocumentService().didChange(params);
    }

    public ServerCapabilities getCapabilities() {
        return _capabilities;
    }

    public TextColor foregroundColourForScope(int scope) {
        return TextColor.ANSI.RED;
    }

    private boolean supportsSemanticTokens() {
        return _enabled
                && _server != null
                && _capabilities != null
                && _capabilities.getSemanticTokensProvider() != null;
    }

    private TextColor semanticTokenColor(String tokenType, int modifiersBitset, List<String> modifiers) {
        if (hasModifier(modifiersBitset, modifiers, "deprecated")) {
            return TextColor.ANSI.RED;
        }
        if (hasModifier(modifiersBitset, modifiers, "readonly")) {
            return TextColor.ANSI.YELLOW;
        }
        return switch (tokenType) {
        case "namespace", "decorator" -> TextColor.ANSI.CYAN;
        case "type", "class", "enum", "interface", "struct" -> TextColor.ANSI.GREEN;
        case "typeParameter", "parameter" -> TextColor.ANSI.MAGENTA;
        case "property", "enumMember", "event" -> TextColor.ANSI.YELLOW;
        case "function", "method", "macro" -> TextColor.ANSI.BLUE;
        case "keyword", "modifier" -> TextColor.ANSI.RED;
        case "comment" -> TextColor.ANSI.GREEN;
        case "string" -> TextColor.ANSI.CYAN;
        case "number", "regexp" -> TextColor.ANSI.MAGENTA;
        case "operator" -> TextColor.ANSI.DEFAULT;
        default -> TextColor.ANSI.DEFAULT;
        };
    }

    private boolean hasModifier(int modifiersBitset, List<String> modifiers, String name) {
        int index = modifiers.indexOf(name);
        return index >= 0 && ((modifiersBitset >> index) & 1) == 1;
    }

    static List<SemanticHighlight> decodeSemanticHighlights(BufferContext bufferContext, SemanticTokens tokens, SemanticTokensLegend legend) {
        if (tokens == null || tokens.getData() == null || legend == null || legend.getTokenTypes() == null) {
            return List.of();
        }
        var tokenTypes = legend.getTokenTypes();
        var tokenModifiers = legend.getTokenModifiers() == null ? List.<String>of() : legend.getTokenModifiers();
        var client = JavaLSPClient.getInstance();
        var data = tokens.getData();
        var highlights = new ArrayList<SemanticHighlight>();
        int line = 0;
        int character = 0;

        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = data.get(i);
            int deltaStart = data.get(i + 1);
            int length = data.get(i + 2);
            int tokenTypeIndex = data.get(i + 3);
            int modifiersBitset = data.get(i + 4);

            line += deltaLine;
            character = deltaLine == 0 ? character + deltaStart : deltaStart;
            if (length <= 0 || tokenTypeIndex < 0 || tokenTypeIndex >= tokenTypes.size()) {
                continue;
            }

            try {
                int start = bufferContext.getTextLayout().getIndexForPhysicalLineCharacter(line, character);
                int end = bufferContext.getTextLayout().getIndexForPhysicalLineCharacter(line, character + length);
                highlights.add(new SemanticHighlight(
                        start,
                        end,
                        client.semanticTokenColor(tokenTypes.get(tokenTypeIndex), modifiersBitset, tokenModifiers)));
            } catch (RuntimeException e) {
                _log.debug("Skipping invalid semantic token at line " + line + " character " + character, e);
            }
        }
        return highlights;
    }

    private List<SemanticHighlight> getSemanticHighlights(BufferContext bufferContext) {
        if (!supportsSemanticTokens()) {
            return List.of();
        }
        String uri = bufferContext.getBuffer().getURI().toString();
        int version = bufferContext.getBuffer().getVersionedTextDocumentID().getVersion();
        synchronized (this) {
            var cached = _semanticTokensCache.get(uri);
            if (cached != null && cached.version() == version) {
                return cached.highlights();
            }
        }

        List<SemanticHighlight> highlights = List.of();
        try {
            var legend = _capabilities.getSemanticTokensProvider().getLegend();
            var params = new SemanticTokensParams(bufferContext.getBuffer().getTextDocumentID());
            var tokens = _server.getTextDocumentService().semanticTokensFull(params).get(2, TimeUnit.SECONDS);
            highlights = decodeSemanticHighlights(bufferContext, tokens, legend);
        } catch (Exception e) {
            _log.debug("Semantic token request failed", e);
        }

        synchronized (this) {
            if (highlights.isEmpty()) {
                _semanticTokensCache.remove(uri);
            } else {
                _semanticTokensCache.put(uri, new CachedSemanticTokens(version, highlights));
            }
        }
        return highlights;
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

    private void formatToken(AttributedString str, String string, Pattern pattern, TextColor colour) {
        try {
            var matcher = pattern.matcher(string);
            while (matcher.find()) {
                str.format(matcher.start(), matcher.end(), colour, TextColor.ANSI.DEFAULT);
            }
        } catch (Throwable e) {
        }
    }

    @Override
    public void applyColouring(BufferContext bufferContext, AttributedString str) {
        var string = str.toString();
        formatToken(str, string, _javaKeywordPattern, TextColor.ANSI.RED);
        formatToken(str, string, _javaKeywordTokenPattern, TextColor.ANSI.CYAN);
        formatToken(str, string, _javaCharacterPattern, TextColor.ANSI.CYAN);
        formatToken(str, string, _javaCommentPattern, TextColor.ANSI.GREEN);
        formatToken(str, string, _javaStringPattern, TextColor.ANSI.CYAN);
        if (bufferContext != null) {
            for (var highlight : getSemanticHighlights(bufferContext)) {
                str.format(highlight.start(), highlight.end(), highlight.foregroundColor(), TextColor.ANSI.DEFAULT);
            }
        }
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
    public TextDocumentItem getTextDocument(BufferContext bufferContext) {
        return new TextDocumentItem(
                bufferContext.getBuffer().getPath().toFile().toURI().toString(),
                "java",
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                bufferContext.getBuffer().getString());
    }

    private static Path resolveOracleExtensionPath() {
        String override = System.getProperty("swim.oracle.java.extension.path", "").trim();
        if (!override.isEmpty()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        Path bundled = Paths.get(System.getProperty("user.home"), ".swim", "deps", "oracle.oracle-java");
        if (Files.isDirectory(bundled)) {
            return bundled;
        }
        return findOracleExtensionPath(Paths.get(System.getProperty("user.home"), ".vscode", "extensions"));
    }
}
