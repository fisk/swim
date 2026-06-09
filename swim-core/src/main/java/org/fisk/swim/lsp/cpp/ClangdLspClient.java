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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
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
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.lsp.DiagnosticAction;
import org.fisk.swim.lsp.DiagnosticActionProvider;
import org.fisk.swim.lsp.DiagnosticEntry;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.java.JavaDefinitionMenuSession;
import org.fisk.swim.lsp.java.JavaCompletionSession;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.Settings;
import org.fisk.swim.ui.CompletionPopupView;
import org.fisk.swim.ui.JavaDefinitionPopupView;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;

public class ClangdLspClient implements LanguageMode, DiagnosticActionProvider {
    private static final Logger _log = LogFactory.createLog();
    private static final String DIAGNOSTIC_PROVIDER_ID = "clangd-lsp";

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
    private final Object _completionLock = new Object();
    private JavaCompletionSession _completionSession;
    private CompletionPopupView _completionPopupView;
    private final Object _definitionLock = new Object();
    private JavaDefinitionMenuSession _definitionMenuSession;
    private JavaDefinitionPopupView _definitionPopupView;

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
        DiagnosticService.getInstance().clearProvider(DIAGNOSTIC_PROVIDER_ID);
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
        DiagnosticService.getInstance().clear(DIAGNOSTIC_PROVIDER_ID, bufferContext.getBuffer().getURI().toString());
        var params = new DidCloseTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        _server.getTextDocumentService().didClose(params);
        refreshDiagnosticsUi();
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
    public void handleInsertedCharacter(BufferContext bufferContext, char insertedCharacter) {
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
    public boolean triggerCompletion(BufferContext bufferContext) {
        if (!supportsCompletion()) {
            return false;
        }
        refreshCompletion(bufferContext, CompletionTriggerKind.Invoked, null);
        synchronized (_completionLock) {
            return _completionSession != null;
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
            _completionSession.pageSelection(1, JavaCompletionSession.DEFAULT_VISIBLE_ROWS);
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
            _completionSession.pageSelection(-1, JavaCompletionSession.DEFAULT_VISIBLE_ROWS);
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
        if (window != null && window.getRootView() != null) {
            window.getRootView().setNeedsRedraw();
        }
        return true;
    }

    public void goToDefinition(BufferContext bufferContext) {
        cancelDefinitionMenu();
        if (!_enabled || _server == null) {
            return;
        }
        try {
            var entries = requestDefinitionEntries(bufferContext);
            if (entries.isEmpty()) {
                return;
            }
            if (entries.size() == 1) {
                jumpToLocation(entries.get(0));
                return;
            }
            showLocationMenu(bufferContext, entries, "Definitions");
        } catch (Exception e) {
            _log.debug("clangd definition lookup failed", e);
        }
    }

    public void findReferences(BufferContext bufferContext) {
        cancelDefinitionMenu();
        if (!_enabled || _server == null) {
            return;
        }
        try {
            var entries = requestReferenceEntries(bufferContext);
            if (entries.isEmpty()) {
                return;
            }
            if (entries.size() == 1) {
                jumpToLocation(entries.get(0));
                return;
            }
            showLocationMenu(bufferContext, entries, "References");
        } catch (Exception e) {
            _log.debug("clangd reference lookup failed", e);
        }
    }

    private List<Either<Command, CodeAction>> requestCodeActions(
            BufferContext bufferContext,
            Range range,
            List<Diagnostic> diagnostics) {
        if (!_enabled || _server == null) {
            return List.of();
        }
        try {
            var context = new CodeActionContext(diagnostics);
            var params = new CodeActionParams(bufferContext.getBuffer().getTextDocumentID(), range, context);
            return _server.getTextDocumentService().codeAction(params).join();
        } catch (Exception e) {
            _log.debug("clangd code action request failed", e);
            return List.of();
        }
    }

    @Override
    public List<DiagnosticAction> diagnosticActions(
            BufferContext bufferContext,
            int logicalLine,
            List<DiagnosticEntry> lineDiagnostics) {
        var range = logicalLineRange(bufferContext, logicalLine);
        if (range == null) {
            return List.of();
        }
        var diagnostics = new ArrayList<Diagnostic>();
        for (var entry : lineDiagnostics) {
            diagnostics.add(entry.diagnostic());
        }
        var response = requestCodeActions(bufferContext, range, diagnostics);
        if (response.isEmpty()) {
            return List.of();
        }
        var deduped = new LinkedHashMap<String, DiagnosticAction>();
        for (var either : response) {
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
        if (bufferContext == null || logicalLine < 0) {
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

    public boolean cancelDefinitionMenu() {
        JavaDefinitionPopupView popupView;
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

    private List<JavaDefinitionMenuSession.Entry> requestDefinitionEntries(BufferContext bufferContext) throws Exception {
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var params = new org.eclipse.lsp4j.DefinitionParams(
                bufferContext.getBuffer().getTextDocumentID(),
                getPosition(bufferContext, cursor));
        var response = _server.getTextDocumentService().definition(params).get(2, TimeUnit.SECONDS);
        if (response == null) {
            return List.of();
        }
        var entries = response.isLeft()
                ? locationEntries(response.getLeft(), null)
                : locationEntries(null, response.getRight());
        return dedupeEntries(entries);
    }

    private List<JavaDefinitionMenuSession.Entry> requestReferenceEntries(BufferContext bufferContext) throws Exception {
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var params = new ReferenceParams(
                bufferContext.getBuffer().getTextDocumentID(),
                getPosition(bufferContext, cursor),
                new ReferenceContext(true));
        var response = _server.getTextDocumentService().references(params).get(2, TimeUnit.SECONDS);
        if (response == null || response.isEmpty()) {
            return List.of();
        }
        return dedupeEntries(locationEntries(response, null));
    }

    private List<JavaDefinitionMenuSession.Entry> dedupeEntries(List<JavaDefinitionMenuSession.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        var deduped = new LinkedHashMap<String, JavaDefinitionMenuSession.Entry>();
        for (var entry : entries) {
            String key = entry.path().toAbsolutePath().normalize() + ":" + entry.position().getLine() + ":" + entry.position().getCharacter();
            deduped.putIfAbsent(key, entry);
        }
        return List.copyOf(deduped.values());
    }

    private List<JavaDefinitionMenuSession.Entry> locationEntries(
            List<? extends Location> locations,
            List<? extends LocationLink> links) {
        var entries = new ArrayList<JavaDefinitionMenuSession.Entry>();
        if (locations != null) {
            for (var location : locations) {
                var entry = locationEntry(location);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        if (links != null) {
            for (var locationLink : links) {
                var entry = locationEntry(locationLink);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private JavaDefinitionMenuSession.Entry locationEntry(Location location) {
        if (location == null || location.getRange() == null) {
            return null;
        }
        return locationEntry(location.getUri(), location.getRange().getStart());
    }

    private JavaDefinitionMenuSession.Entry locationEntry(LocationLink locationLink) {
        if (locationLink == null) {
            return null;
        }
        var targetRange = locationLink.getTargetSelectionRange() != null
                ? locationLink.getTargetSelectionRange()
                : locationLink.getTargetRange();
        if (targetRange == null) {
            return null;
        }
        return locationEntry(locationLink.getTargetUri(), targetRange.getStart());
    }

    private JavaDefinitionMenuSession.Entry locationEntry(String uri, Position position) {
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
        return new JavaDefinitionMenuSession.Entry(label, path.toString(), path, position);
    }

    private String displayPath(Path path) {
        Path projectRoot = _projectPath;
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

    private void showLocationMenu(BufferContext bufferContext, List<JavaDefinitionMenuSession.Entry> entries, String title) {
        var window = Window.getInstance();
        if (window == null || window.getRootView() == null) {
            return;
        }
        var session = new JavaDefinitionMenuSession(bufferContext, entries, title);
        synchronized (_definitionLock) {
            _definitionMenuSession = session;
            if (_definitionPopupView == null || _definitionPopupView.getParent() == null) {
                _definitionPopupView = new JavaDefinitionPopupView(Rect.create(0, 0, 0, 0));
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
        JavaDefinitionMenuSession.Entry entry;
        synchronized (_definitionLock) {
            entry = _definitionMenuSession == null ? null : _definitionMenuSession.getSelectedEntry();
        }
        if (entry == null) {
            cancelDefinitionMenu();
            return;
        }
        jumpToLocation(entry);
        cancelDefinitionMenu();
    }

    private void jumpToLocation(JavaDefinitionMenuSession.Entry entry) {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        try {
            Path targetPath = entry.path().toAbsolutePath().normalize();
            Path currentBufferPath = window.getBufferContext().getBuffer().getPath();
            Path currentPath = currentBufferPath == null ? null : currentBufferPath.toAbsolutePath().normalize();
            if ((currentPath == null || !currentPath.equals(targetPath)) && !window.setBufferPath(targetPath)) {
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
            _log.debug("Jump to clangd location failed", e);
        }
    }

    private static final class IndexedEdit {
        private final int _start;
        private final int _end;
        private final String _newText;

        private IndexedEdit(int start, int end, String newText) {
            _start = start;
            _end = end;
            _newText = newText;
        }
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

    private static void applyIndexedEdits(BufferContext context, List<IndexedEdit> edits) {
        var buffer = context.getBuffer();
        for (var edit : edits) {
            buffer.remove(edit._start, edit._end);
            buffer.insert(edit._start, edit._newText.replace("\t", "    "));
        }
        if (!edits.isEmpty()) {
            buffer.getUndoLog().commit();
        }
    }

    private static void applyWorkspaceEdit(BufferContext context, WorkspaceEdit workspaceEdit) {
        if (context == null || workspaceEdit == null) {
            return;
        }
        var edits = new ArrayList<IndexedEdit>();
        var currentUri = context.getBuffer().getURI();
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
                TextDocumentEdit documentEdit = change.getLeft();
                if (!uriMatches(currentUri, documentEdit.getTextDocument().getUri())) {
                    continue;
                }
                for (var edit : documentEdit.getEdits()) {
                    edits.add(new IndexedEdit(
                            getIndex(context, edit.getRange().getStart()),
                            getIndex(context, edit.getRange().getEnd()),
                            edit.getNewText()));
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
        try {
            if (_server != null && _server.getWorkspaceService() != null) {
                var params = new ExecuteCommandParams();
                params.setCommand(command.getCommand());
                params.setArguments(command.getArguments());
                _server.getWorkspaceService().executeCommand(params).join();
            }
        } catch (Exception e) {
            _log.debug("Executing clangd code-action command failed", e);
        }
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
    public String getIndentationString(BufferContext bufferContext) {
        return Settings.getIndentationString(languageId(bufferContext == null ? null : bufferContext.getBuffer().getPath()));
    }

    @Override
    public TextDocumentItem getTextDocument(BufferContext bufferContext) {
        return new TextDocumentItem(
                bufferContext.getBuffer().getURI().toString(),
                languageId(bufferContext.getBuffer().getPath()),
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                bufferContext.getBuffer().getString());
    }

    @Override
    public void applyColouring(BufferContext bufferContext, AttributedString str) {
        String string = str.toString();
        formatCppPreprocessorLines(str, string);
        formatToken(str, string, CPP_KEYWORD_PATTERN, SEMANTIC_KEYWORD);
        formatCppLexicalTokens(str, string);
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

    private static int getIndex(BufferContext context, Position position) {
        return context.getTextLayout().getIndexForPhysicalLineCharacter(position.getLine(), position.getCharacter());
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
            _log.debug("clangd completion request failed", e);
            return null;
        }
    }

    private void showCompletionSession(JavaCompletionSession session) {
        synchronized (_completionLock) {
            _completionSession = session;
        }
        var window = Window.getInstance();
        if (window == null || window.getRootView() == null) {
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
            _log.debug("clangd completion resolve failed", e);
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

    private void applyCompletionItem(BufferContext bufferContext, JavaCompletionSession session, CompletionItem item) {
        if (item == null) {
            return;
        }
        TextEdit edit = primaryCompletionEdit(bufferContext, session, item);
        if (edit == null) {
            return;
        }
        int start = getIndex(bufferContext, edit.getRange().getStart());
        int end = getIndex(bufferContext, edit.getRange().getEnd());
        bufferContext.getBuffer().remove(start, end);
        bufferContext.getBuffer().insert(start, edit.getNewText());
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
                DiagnosticService.getInstance().publish(
                        DIAGNOSTIC_PROVIDER_ID,
                        diagnostics.getUri(),
                        pathForUri(diagnostics.getUri()),
                        diagnostics.getDiagnostics());
                refreshDiagnosticsUi();
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

    private static void formatCppPreprocessorLines(AttributedString str, String string) {
        int lineStart = 0;
        while (lineStart < string.length()) {
            int lineEnd = string.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = string.length();
            }
            int index = lineStart;
            while (index < lineEnd && Character.isWhitespace(string.charAt(index))) {
                index++;
            }
            if (index < lineEnd && string.charAt(index) == '#') {
                str.format(lineStart, lineEnd, SEMANTIC_MACRO, TextColor.ANSI.DEFAULT);
            }
            lineStart = lineEnd + 1;
        }
    }

    private static void formatCppLexicalTokens(AttributedString str, String string) {
        int index = 0;
        while (index < string.length()) {
            char current = string.charAt(index);
            if (current == '/' && index + 1 < string.length()) {
                char next = string.charAt(index + 1);
                if (next == '/') {
                    int start = index;
                    index += 2;
                    while (index < string.length() && !isLineBreak(string.charAt(index))) {
                        index++;
                    }
                    formatRange(str, start, index, SEMANTIC_COMMENT);
                    continue;
                }
                if (next == '*') {
                    int start = index;
                    index += 2;
                    while (index + 1 < string.length()
                            && !(string.charAt(index) == '*' && string.charAt(index + 1) == '/')) {
                        index++;
                    }
                    index = index + 1 < string.length() ? index + 2 : string.length();
                    formatRange(str, start, index, SEMANTIC_COMMENT);
                    continue;
                }
            }
            if (current == '"' || current == '\'') {
                int start = index;
                char quote = current;
                index++;
                boolean escaped = false;
                while (index < string.length()) {
                    char value = string.charAt(index);
                    if (escaped) {
                        escaped = false;
                        index++;
                        continue;
                    }
                    if (value == '\\') {
                        escaped = true;
                        index++;
                        continue;
                    }
                    if (value == quote) {
                        index++;
                        break;
                    }
                    if (isLineBreak(value)) {
                        break;
                    }
                    index++;
                }
                formatRange(str, start, index, SEMANTIC_STRING);
                continue;
            }
            index++;
        }
    }

    private static boolean isLineBreak(char value) {
        return value == '\n' || value == '\r';
    }

    private static void formatRange(AttributedString str, int start, int end, TextColor color) {
        if (end > start) {
            str.format(start, end, color, TextColor.ANSI.DEFAULT);
        }
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
