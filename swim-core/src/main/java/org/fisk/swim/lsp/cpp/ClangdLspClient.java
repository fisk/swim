package org.fisk.swim.lsp.cpp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;

public class ClangdLspClient implements LanguageMode {
    private static final Logger _log = LogFactory.createLog();

    static final TextColor SEMANTIC_NAMESPACE = TextColor.Factory.fromString("#5ec4ff");
    static final TextColor SEMANTIC_TYPE = TextColor.Factory.fromString("#86d96a");
    static final TextColor SEMANTIC_PARAMETER = TextColor.Factory.fromString("#ffb86c");
    static final TextColor SEMANTIC_MEMBER = TextColor.Factory.fromString("#ffd166");
    static final TextColor SEMANTIC_FUNCTION = TextColor.Factory.fromString("#7ab8ff");
    static final TextColor SEMANTIC_COMMENT = TextColor.Factory.fromString("#7ecb7e");
    static final TextColor SEMANTIC_STRING = TextColor.Factory.fromString("#7fe3ff");
    static final TextColor SEMANTIC_NUMBER = TextColor.Factory.fromString("#f5a3ff");
    static final TextColor SEMANTIC_KEYWORD = TextColor.Factory.fromString("#ff6b6b");
    static final TextColor SEMANTIC_READONLY = TextColor.Factory.fromString("#ffcf66");
    static final TextColor SEMANTIC_MACRO = TextColor.Factory.fromString("#f7a94b");

    private static final Pattern CPP_COMMENT_PATTERN = Pattern.compile("(/\\*([^*]|[\\n]|(\\*+([^*/]|[\\n])))*\\*+/)|(//.*)", Pattern.MULTILINE);
    private static final Pattern CPP_STRING_PATTERN = Pattern.compile("\"([^\\\\\"]|(\\\\.))*\"", Pattern.MULTILINE);
    private static final Pattern CPP_CHARACTER_PATTERN = Pattern.compile("'([^\\\\']|(\\\\.))*'", Pattern.MULTILINE);
    private static final Pattern CPP_PREPROCESSOR_PATTERN = Pattern.compile("(?m)^\\s*#\\s*[A-Za-z_][A-Za-z0-9_]*.*$");
    private static final Pattern CPP_KEYWORD_PATTERN = Pattern.compile(
            "\\b(alignas|alignof|asm|auto|bool|break|case|catch|char|char8_t|char16_t|char32_t|class|concept|const|consteval|constexpr|constinit|continue|co_await|co_return|co_yield|decltype|default|delete|do|double|else|enum|explicit|export|extern|false|final|float|for|friend|goto|if|inline|int|long|mutable|namespace|new|noexcept|nullptr|operator|override|private|protected|public|register|requires|return|short|signed|sizeof|static|struct|switch|template|this|thread_local|throw|true|try|typedef|typename|union|unsigned|using|virtual|void|volatile|while)\\b",
            Pattern.MULTILINE);
    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\{|\\}");

    private final Object _lock = new Object();
    private final ClangdLspProvider _provider;
    private boolean _started = false;
    private boolean _startupComplete = false;
    private boolean _launchAttempted = false;
    private boolean _enabled = true;
    private Throwable _startupError;
    private Thread _workerThread;
    private Thread _shutdownHook;
    private AutoCloseable _providerConnection;
    private LanguageServer _server;
    private ServerCapabilities _capabilities;
    private Path _projectPath;
    private Path _workspacePath;
    private Path _compilationDatabaseRoot;
    private final Path _swimHomePath = Paths.get(System.getProperty("user.home"), ".swim");
    private final Map<String, CachedSemanticTokens> _semanticTokensCache = new HashMap<>();
    private final Map<String, Integer> _semanticRefreshVersions = new HashMap<>();

    private static ClangdLspClient _instance;

    static record SemanticHighlight(int start, int end, TextColor foregroundColor) {
    }

    private static record CachedSemanticTokens(int version, List<SemanticHighlight> highlights) {
    }

    public ClangdLspClient() {
        this(new ClangdLspProvider());
    }

    ClangdLspClient(ClangdLspProvider provider) {
        _provider = provider;
        if (!_provider.isAvailable()) {
            _enabled = false;
            _log.info("clangd is not available on PATH");
        }
    }

    public static synchronized ClangdLspClient getInstalledInstanceOr(ClangdLspClient fallback) {
        return _instance == null ? fallback : _instance;
    }

    static synchronized void installInstance(ClangdLspClient client) {
        shutdownInstalledInstance();
        _instance = client;
    }

    static synchronized void shutdownInstalledInstance() {
        var instance = _instance;
        _instance = null;
        if (instance != null) {
            instance.shutdown();
        }
    }

    public boolean isEnabled() {
        return _enabled;
    }

    public boolean hasStarted() {
        return _started;
    }

    public void disable() {
        _enabled = false;
    }

    public synchronized void startServer(Path filePath) {
        if (!_enabled || _launchAttempted) {
            return;
        }
        _projectPath = defaultPath(ClangdProjectRoots.findWorkspaceRoot(filePath), filePath);
        _workspacePath = getWorkspacePath(_swimHomePath, _projectPath);
        _compilationDatabaseRoot = ClangdProjectRoots.findCompilationDatabaseRoot(filePath);
        _launchAttempted = true;
        var thread = new Thread(this::run, "swim-clangd-lsp");
        thread.setDaemon(true);
        _workerThread = thread;
        thread.start();
    }

    public void ensureInit() {
        if (!_enabled) {
            return;
        }
        synchronized (_lock) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configurationTimeoutSeconds());
            while (!_startupComplete) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new RuntimeException("Timed out waiting for clangd initialization");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(_lock, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for clangd initialization", e);
                }
            }
            if (_startupError != null) {
                throw new RuntimeException("clangd initialization failed", _startupError);
            }
        }
    }

    public void shutdown() {
        clearSemanticTokensCache();
        _enabled = false;
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
                _log.debug("Error shutting down clangd provider", e);
            }
            _providerConnection = null;
        }
        if (_workerThread != null) {
            _workerThread.interrupt();
            _workerThread = null;
        }
        _server = null;
        _capabilities = null;
        synchronized (_lock) {
            _started = false;
            _startupComplete = false;
            _startupError = null;
            _launchAttempted = false;
        }
    }

    @Override
    public void didInsert(BufferContext bufferContext, int position, String text) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var line = bufferContext.getTextLayout().getPhysicalLineAt(position);
        int lineIndex = line.getY();
        int charIndex = position - line.getStartPosition();
        var range = new Range(new Position(lineIndex, charIndex), new Position(lineIndex, charIndex));
        var change = new TextDocumentContentChangeEvent(range, 0, text);
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(List.of(change));
        _server.getTextDocumentService().didChange(params);
        scheduleSemanticHighlightRefresh(bufferContext);
    }

    @Override
    public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var startLine = bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
        int startLineIndex = startLine.getY();
        int startIndex = startPosition - startLine.getStartPosition();
        var endLine = bufferContext.getTextLayout().getPhysicalLineAt(endPosition);
        int endLineIndex = endLine.getY();
        int endIndex = endPosition - endLine.getStartPosition();
        var range = new Range(new Position(startLineIndex, startIndex), new Position(endLineIndex, endIndex));
        var change = new TextDocumentContentChangeEvent(range, endPosition - startPosition, "");
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(List.of(change));
        _server.getTextDocumentService().didChange(params);
        scheduleSemanticHighlightRefresh(bufferContext);
    }

    @Override
    public void willSave(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        var params = new WillSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        _server.getTextDocumentService().willSave(params);
    }

    @Override
    public void didSave(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var params = new DidSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setText(bufferContext.getBuffer().getString());
        _server.getTextDocumentService().didSave(params);
        scheduleSemanticHighlightRefresh(bufferContext);
    }

    @Override
    public void didClose(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var params = new DidCloseTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        _server.getTextDocumentService().didClose(params);
    }

    @Override
    public void didOpen(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return;
        }
        clearSemanticTokensCache(bufferContext.getBuffer().getURI().toString());
        var params = new DidOpenTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocument());
        _server.getTextDocumentService().didOpen(params);
        scheduleSemanticHighlightRefresh(bufferContext);
    }

    @Override
    public int getIndentationLevel(BufferContext bufferContext) {
        int indentation = 0;
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var matcher = BRACKET_PATTERN.matcher(bufferContext.getBuffer().getString());
        while (matcher.find()) {
            if (matcher.start() >= cursor) {
                return indentation;
            }
            if ("{".equals(matcher.group(0))) {
                ++indentation;
            } else {
                --indentation;
            }
        }
        return indentation;
    }

    @Override
    public boolean isIndentationEnd(BufferContext bufferContext, String character) {
        return "}".equals(character);
    }

    @Override
    public TextDocumentItem getTextDocument(BufferContext bufferContext) {
        return new TextDocumentItem(
                bufferContext.getBuffer().getPath().toUri().toString(),
                languageId(bufferContext.getBuffer().getPath()),
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                bufferContext.getBuffer().getString());
    }

    @Override
    public void applyColouring(BufferContext bufferContext, AttributedString str) {
        String string = str.toString();
        formatToken(str, string, CPP_PREPROCESSOR_PATTERN, SEMANTIC_MACRO);
        formatToken(str, string, CPP_KEYWORD_PATTERN, SEMANTIC_KEYWORD);
        formatToken(str, string, CPP_COMMENT_PATTERN, SEMANTIC_COMMENT);
        formatToken(str, string, CPP_STRING_PATTERN, SEMANTIC_STRING);
        formatToken(str, string, CPP_CHARACTER_PATTERN, SEMANTIC_STRING);
        if (bufferContext != null) {
            for (var highlight : getSemanticHighlights(bufferContext)) {
                str.format(highlight.start(), highlight.end(), highlight.foregroundColor(), TextColor.ANSI.DEFAULT);
            }
        }
    }

    static Path getWorkspacePath(Path swimHomePath, Path projectPath) {
        String projectName = projectPath.getFileName() == null ? "project" : projectPath.getFileName().toString();
        projectName = projectName.replaceAll("[^A-Za-z0-9._-]", "_");
        return swimHomePath.resolve("workspace").resolve(projectName + "-" + sha1(projectPath.toAbsolutePath().normalize().toString()));
    }

    static Map<String, Object> createInitializationOptions(Path compilationDatabaseRoot) {
        if (compilationDatabaseRoot == null) {
            return Map.of();
        }
        return Map.of("compilationDatabasePath", compilationDatabaseRoot.toString());
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

    private static Path defaultPath(Path resolved, Path filePath) {
        if (resolved != null) {
            return resolved;
        }
        if (filePath == null) {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        return Files.isDirectory(normalized) ? normalized : normalized.getParent();
    }

    private static long configurationTimeoutSeconds() {
        return 20;
    }

    private synchronized void clearSemanticTokensCache() {
        _semanticTokensCache.clear();
        _semanticRefreshVersions.clear();
    }

    private synchronized void clearSemanticTokensCache(String uri) {
        if (uri != null) {
            _semanticTokensCache.remove(uri);
        }
    }

    private void run() {
        if (!_enabled) {
            return;
        }
        try {
            Files.createDirectories(_workspacePath);
            var session = _provider.start(
                    _projectPath,
                    _workspacePath,
                    createLanguageClient(),
                    getClientCapabilities(),
                    createInitializationOptions(_compilationDatabaseRoot),
                    configurationTimeoutSeconds());
            _server = session.server();
            _capabilities = session.capabilities();
            _providerConnection = session.closeable();
            _shutdownHook = new Thread(() -> {
                if (_providerConnection == null) {
                    return;
                }
                try {
                    _providerConnection.close();
                } catch (Exception e) {
                }
            });
            Runtime.getRuntime().addShutdownHook(_shutdownHook);
            signalStartupSuccess();
        } catch (Throwable e) {
            signalStartupFailure(e);
            _log.error("Error setting up clangd", e);
        }
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

    private LanguageClient createLanguageClient() {
        return new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
                _log.debug("clangd telemetry: {}", object);
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
                _log.debug("clangd diagnostics: {}", diagnostics.getDiagnostics());
            }

            @Override
            public void showMessage(MessageParams message) {
                _log.info("clangd message: {}", message.getMessage());
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void logMessage(MessageParams message) {
                _log.info("clangd log: {}", message.getMessage());
            }

            @Override
            public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
                return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(
                        _projectPath.toUri().toString(),
                        _projectPath.getFileName() == null ? _projectPath.toString() : _projectPath.getFileName().toString())));
            }

            @Override
            public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
                return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
            }

            @Override
            public CompletableFuture<Void> registerCapability(RegistrationParams params) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void notifyProgress(ProgressParams params) {
                _log.debug("clangd progress: {}", params);
            }
        };
    }

    private ClientCapabilities getClientCapabilities() {
        var workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(false);
        workspace.setConfiguration(true);

        var textDocument = new TextDocumentClientCapabilities();
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

    private boolean supportsSemanticTokens() {
        return _enabled
                && _server != null
                && _capabilities != null
                && _capabilities.getSemanticTokensProvider() != null;
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

        List<SemanticHighlight> highlights = fetchSemanticHighlights(bufferContext);
        synchronized (this) {
            if (highlights.isEmpty()) {
                _semanticTokensCache.remove(uri);
            } else {
                _semanticTokensCache.put(uri, new CachedSemanticTokens(version, highlights));
            }
        }
        return highlights;
    }

    private List<SemanticHighlight> fetchSemanticHighlights(BufferContext bufferContext) {
        try {
            var provider = _capabilities.getSemanticTokensProvider();
            SemanticTokensLegend legend = provider == null ? null : provider.getLegend();
            var params = new SemanticTokensParams(bufferContext.getBuffer().getTextDocumentID());
            var tokens = _server.getTextDocumentService().semanticTokensFull(params).get(2, TimeUnit.SECONDS);
            return decodeSemanticHighlights(bufferContext, tokens, legend);
        } catch (Exception e) {
            _log.debug("clangd semantic token request failed", e);
            return List.of();
        }
    }

    private void scheduleSemanticHighlightRefresh(BufferContext bufferContext) {
        if (!supportsSemanticTokens()) {
            return;
        }
        String uri = bufferContext.getBuffer().getURI().toString();
        int version = bufferContext.getBuffer().getVersionedTextDocumentID().getVersion();
        synchronized (this) {
            Integer queuedVersion = _semanticRefreshVersions.get(uri);
            if (queuedVersion != null && queuedVersion >= version) {
                return;
            }
            _semanticRefreshVersions.put(uri, version);
        }

        var thread = new Thread(() -> refreshSemanticHighlights(uri, version, bufferContext), "swim-clangd-semantic-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshSemanticHighlights(String uri, int version, BufferContext bufferContext) {
        try {
            for (int attempt = 0; attempt < 20; ++attempt) {
                if (!supportsSemanticTokens()) {
                    return;
                }
                int currentVersion = bufferContext.getBuffer().getVersionedTextDocumentID().getVersion();
                synchronized (this) {
                    Integer queuedVersion = _semanticRefreshVersions.get(uri);
                    if (queuedVersion == null || queuedVersion != version || currentVersion != version) {
                        return;
                    }
                }
                List<SemanticHighlight> highlights = fetchSemanticHighlights(bufferContext);
                if (!highlights.isEmpty()) {
                    synchronized (this) {
                        _semanticTokensCache.put(uri, new CachedSemanticTokens(version, highlights));
                        _semanticRefreshVersions.remove(uri);
                    }
                    requestSemanticRedraw(bufferContext);
                    return;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                Integer queuedVersion = _semanticRefreshVersions.get(uri);
                if (queuedVersion != null && queuedVersion == version) {
                    _semanticRefreshVersions.remove(uri);
                }
            }
        }
    }

    private void requestSemanticRedraw(BufferContext bufferContext) {
        Runnable redraw = () -> {
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

    static List<SemanticHighlight> decodeSemanticHighlights(BufferContext bufferContext, SemanticTokens tokens, SemanticTokensLegend legend) {
        if (tokens == null || tokens.getData() == null || legend == null || legend.getTokenTypes() == null) {
            return List.of();
        }
        var tokenTypes = legend.getTokenTypes();
        var tokenModifiers = legend.getTokenModifiers() == null ? List.<String>of() : legend.getTokenModifiers();
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
                        semanticTokenColor(tokenTypes.get(tokenTypeIndex), modifiersBitset, tokenModifiers)));
            } catch (RuntimeException e) {
                _log.debug("Skipping invalid clangd semantic token at line {} character {}", line, character, e);
            }
        }
        return highlights;
    }

    private static TextColor semanticTokenColor(String tokenType, int modifiersBitset, List<String> modifiers) {
        if (hasModifier(modifiersBitset, modifiers, "deprecated")) {
            return SEMANTIC_KEYWORD;
        }
        if (hasModifier(modifiersBitset, modifiers, "readonly")) {
            return SEMANTIC_READONLY;
        }
        return switch (tokenType) {
        case "namespace", "decorator" -> SEMANTIC_NAMESPACE;
        case "type", "class", "enum", "interface", "struct", "typeParameter" -> SEMANTIC_TYPE;
        case "parameter" -> SEMANTIC_PARAMETER;
        case "property", "enumMember", "event", "variable" -> SEMANTIC_MEMBER;
        case "function", "method" -> SEMANTIC_FUNCTION;
        case "macro" -> SEMANTIC_MACRO;
        case "keyword", "modifier" -> SEMANTIC_KEYWORD;
        case "comment" -> SEMANTIC_COMMENT;
        case "string" -> SEMANTIC_STRING;
        case "number", "regexp" -> SEMANTIC_NUMBER;
        case "operator" -> TextColor.ANSI.DEFAULT;
        default -> TextColor.ANSI.DEFAULT;
        };
    }

    private static boolean hasModifier(int modifiersBitset, List<String> modifiers, String name) {
        int index = modifiers.indexOf(name);
        return index >= 0 && ((modifiersBitset >> index) & 1) == 1;
    }

    private static void formatToken(AttributedString str, String string, Pattern pattern, TextColor color) {
        var matcher = pattern.matcher(string);
        while (matcher.find()) {
            str.format(matcher.start(), matcher.end(), color, TextColor.ANSI.DEFAULT);
        }
    }

    private static String languageId(Path path) {
        if (path == null || path.getFileName() == null) {
            return "cpp";
        }
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (fileName.endsWith(".c") || fileName.endsWith(".h")) {
            return "c";
        }
        return "cpp";
    }
}
