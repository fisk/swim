package org.fisk.swim.lsp.java;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
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
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentSaveReason;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.googlecode.lanterna.TextColor;

public class JavaLSPClient extends Thread implements LanguageMode {
    private static final Logger _log = LogFactory.createLog();
    private static final Gson _gson = new Gson();

    private Object _lock = new Object();
    private boolean _started = false;
    private boolean _startupComplete = false;
    private boolean _launchAttempted = false;
    private boolean _enabled = true;
    private Throwable _startupError;

    private LanguageServer _server;
    private ServerCapabilities _capabilities;

    private Path _projectPath;
    private Path _workspacePath;
    private Path _swimHomePath = Paths.get(System.getProperty("user.home"), ".swim");
    private Path _eclipsePath = _swimHomePath.resolve("deps")
            .resolve("eclipse.jdt.ls")
            .resolve("org.eclipse.jdt.ls.product")
            .resolve("target")
            .resolve("repository");

    public boolean hasStarted() {
        return _started;
    }

    public boolean isEnabled() {
        return _enabled;
    }

    public void disable() {
        _enabled = false;
    }

    public JavaLSPClient() {
        if (!Files.isDirectory(_eclipsePath)) {
            _log.info("No LSP support");
            _enabled = false;
        }
        initColours();
    }

    static String getConfigurationDirectoryName(String osName, String arch) {
        var os = osName.toLowerCase(Locale.ROOT);
        var normalizedArch = arch.toLowerCase(Locale.ROOT);
        boolean arm = normalizedArch.contains("aarch64") || normalizedArch.contains("arm64");

        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? "config_mac_arm" : "config_mac";
        }
        if (os.contains("linux")) {
            return arm ? "config_linux_arm" : "config_linux";
        }
        if (os.contains("win")) {
            return "config_win";
        }
        throw new IllegalArgumentException("Unsupported operating system: " + osName);
    }

    static Path findLauncherJar(Path eclipsePath) {
        var pluginDir = eclipsePath.resolve("plugins");
        if (!Files.isDirectory(pluginDir)) {
            throw new IllegalArgumentException("Missing plugin directory: " + pluginDir);
        }
        try (Stream<Path> files = Files.list(pluginDir)) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unable to find equinox launcher in " + pluginDir));
        } catch (IOException e) {
            throw new RuntimeException("Unable to inspect plugin directory " + pluginDir, e);
        }
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
        new Thread(this::run, "swim-java-lsp").start();
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

    private void logErrorStream(InputStream errorStream) {
        try (var reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                _log.info("jdtls stderr: " + line);
            }
        } catch (IOException e) {
            _log.debug("Error stream reader stopped", e);
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

    private void setup() throws IOException {
        Files.createDirectories(_workspacePath);

        var launcherJar = findLauncherJar(_eclipsePath);
        var configPath = _eclipsePath.resolve(getConfigurationDirectoryName(System.getProperty("os.name"), System.getProperty("os.arch")));

        _log.info("LSP eclipse path: " + _eclipsePath);
        _log.info("LSP workspace path: " + _projectPath);
        _log.info("LSP workspace folder path: " + _workspacePath);

        var command = new ArrayList<String>();
        command.add("java");
        if (Runtime.version().feature() >= 24) {
            command.add("-Djdk.xml.maxGeneralEntitySizeLimit=0");
            command.add("-Djdk.xml.totalEntitySizeLimit=0");
        }
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dosgi.checkConfiguration=true");
        command.add("-Dosgi.sharedConfiguration.area=" + configPath);
        command.add("-Dosgi.sharedConfiguration.area.readOnly=true");
        command.add("-Dosgi.configuration.cascaded=true");
        command.add("-Dlog.level=ALL");
        command.add("-Xms1G");
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens");
        command.add("java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(launcherJar.toString());
        command.add("-data");
        command.add(_workspacePath.toString());

        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(_projectPath.toFile());
        var process = processBuilder.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (!_started) {
                    process.destroy();
                } else {
                    try {
                        _server.shutdown().get();
                        _server.exit();
                    } catch (Exception e) {
                        process.destroy();
                    }
                }
            }
        });

        _log.info("Proccess command: " + String.join(" ", command));
        _log.info("Process PID: " + process.pid());

        new Thread(() -> logErrorStream(process.getErrorStream()), "swim-java-lsp-stderr").start();

        _log.info("Starting LSP server...");
        var istream = process.getInputStream();
        var ostream = process.getOutputStream();
        var client = new LanguageClient() {
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
                return null;
            }
            @Override
            public void logMessage(MessageParams message) {
                _log.info("logMessage: " + message.getMessage());
            }
            @Override
            public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
                return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(_projectPath.toUri().toString(), _projectPath.getFileName().toString())));
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
        };
        var clientLauncher = LSPLauncher.createClientLauncher(client, istream, ostream);
        var listeningFuture = clientLauncher.startListening();
        _server = clientLauncher.getRemoteProxy();
        try {
            var initParams = new InitializeParams();
            initParams.setRootUri(_projectPath.toUri().toString());
            initParams.setWorkspaceFolders(List.of(new WorkspaceFolder(_projectPath.toUri().toString(), _projectPath.getFileName().toString())));
            initParams.setCapabilities(getClientCapabilities());
            var initialized = _server.initialize(initParams).get();
            _capabilities = initialized.getCapabilities();
            _server.initialized(new InitializedParams());
            _log.info("Server capabilities: " + _capabilities);
            signalStartupSuccess();
            listeningFuture.get();
        } catch (Exception e) {
            signalStartupFailure(e);
            throw new RuntimeException("Exception initializing LSP server", e);
        }
    }

    private ClientCapabilities getClientCapabilities() {
        var workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        workspace.setConfiguration(true);

        var executeCommand = new ExecuteCommandCapabilities();
        workspace.setExecuteCommand(executeCommand);

        var textDocument = new TextDocumentClientCapabilities();

        // TODO: Fix this
        //        var semanticHighlighting = new SemanticHighlightingCapabilities(true);
        //        textDocument.setSemanticHighlightingCapabilities(semanticHighlighting);

        var codeAction = new CodeActionCapabilities(true);
        textDocument.setCodeAction(codeAction);

        var codeLens = new CodeLensCapabilities();
        textDocument.setCodeLens(codeLens);

        var references = new ReferencesCapabilities();
        textDocument.setReferences(references);

        var clientCapabilities = new ClientCapabilities(workspace, textDocument, null);
        return clientCapabilities;
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

    static JavaLSPClient _instance = new JavaLSPClient();

    public static JavaLSPClient getInstance() {
        return _instance;
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
                } else {
                    try {
                        _lock.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    public List<ColorInformation> decorateBuffer(BufferContext bufferContext) {
        if (!_enabled || _server == null) {
            return new ArrayList<ColorInformation>();
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
            for (var r: result) {
                _log.info("Code lens item: " + r);
                var resolved = _server.getTextDocumentService().resolveCodeLens(r).get();
                _log.info("Resolved code lens item: " + resolved);
                var params2 = new ExecuteCommandParams();
                params2.setCommand(resolved.getCommand().getCommand());
                params2.setArguments(resolved.getCommand().getArguments());
                var commandResult = _server.getWorkspaceService().executeCommand(params2).get();
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

    private static WorkspaceEdit decodeWorkspaceEdit(Object rawEdit) {
        if (rawEdit == null) {
            return null;
        }
        if (rawEdit instanceof WorkspaceEdit) {
            return (WorkspaceEdit) rawEdit;
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
                    edits.add(new IndexedEdit(getIndex(context, edit.getRange().getStart()),
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
                    edits.add(new IndexedEdit(getIndex(context, textEdit.getRange().getStart()),
                            getIndex(context, textEdit.getRange().getEnd()),
                            textEdit.getNewText()));
                }
            }
        }

        edits.sort(Comparator.comparingInt((IndexedEdit edit) -> edit._start).reversed()
                .thenComparing(Comparator.comparingInt((IndexedEdit edit) -> edit._end).reversed()));

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
        for (var either: getCodeActions(bufferContext)) {
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
        if (!_enabled) {
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
        if (!_enabled) {
            return;
        }
        _log.info("didSave");
        var params = new DidSaveTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        params.setText(bufferContext.getBuffer().getString());
        _server.getTextDocumentService().didSave(params);
    }

    @Override
    public void didOpen(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        _log.info("didOpen");
        var params = new DidOpenTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocument());
        _server.getTextDocumentService().didOpen(params);
    }

    @Override
    public void didClose(BufferContext bufferContext) {
        if (!_enabled) {
            return;
        }
        _log.info("didClose");
        var params = new DidCloseTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getTextDocumentID());
        _server.getTextDocumentService().didClose(params);
    }

    @Override
    public void didInsert(BufferContext bufferContext, int position, String text) {
        if (!_enabled) {
            return;
        }
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var line = bufferContext.getTextLayout().getPhysicalLineAt(position);
        var lineIndex = line.getY();
        var charIndex = position - line.getStartPosition();
        _log.info("didInsert " + text + " at " +  position + " (" + lineIndex + ", " + charIndex + ")");
        var range = new Range(new Position(lineIndex, charIndex), new Position(lineIndex, charIndex));
        var insertEvent = new TextDocumentContentChangeEvent(range, 0, text);
        contentChanges.add(insertEvent);
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(contentChanges);
        _server.getTextDocumentService().didChange(params);
    }

    @Override
    public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
        if (!_enabled) {
            return;
        }
        _log.info("didRemove at " + startPosition + ", " + endPosition);
        var contentChanges = new ArrayList<TextDocumentContentChangeEvent>();
        var startLine = bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
        var startLineIndex = startLine.getY();
        var startIndex = startPosition - startLine.getStartPosition();
        var endLine = bufferContext.getTextLayout().getPhysicalLineAt(endPosition);
        var endLineIndex = endLine.getY();
        var endIndex = endPosition - endLine.getStartPosition();
        var range = new Range(new Position(startLineIndex, startIndex), new Position(endLineIndex, endIndex));
        var removeEvent = new TextDocumentContentChangeEvent(range, endPosition - startPosition, "");
        contentChanges.add(removeEvent);
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(bufferContext.getBuffer().getVersionedTextDocumentID());
        params.setContentChanges(contentChanges);
        _server.getTextDocumentService().didChange(params);
    }

    public ServerCapabilities getCapabilities() {
        return _capabilities;
    }

    private Map<String, TextColor> _foregroundColours = new HashMap<>();

    public TextColor foregroundColourForScope(int scope) {
        // TODO: Fix this
        //        var capabilities = JavaLSPClient.getInstance().getCapabilities();
        //        var scopes = capabilities.getSemanticHighlighting().getScopes();
        //        var identifiers = String.join(":", scopes.get(scope));
        //        var result = _foregroundColours.get(identifiers);
        //        if (result == null) {
        //            throw new RuntimeException("Wait what is this scope " + scope);
        //        }
        //        return result;
        return TextColor.ANSI.RED;
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
        // TODO: Exclude range intersections so that strings with comments in them dont both match.
        // Note the misspelled dont above for this very reason. Spell it right to see what I mean.
        try {
            var matcher = pattern.matcher(string);
            while (matcher.find()) {
                str.format(matcher.start(), matcher.end(), colour, TextColor.ANSI.DEFAULT);
            }
        } catch (Throwable e) {}
    }

    @Override
    public void applyColouring(BufferContext bufferContext, AttributedString str) {
        var string = str.toString();
        formatToken(str, string, _javaKeywordPattern, TextColor.ANSI.RED);
        formatToken(str, string, _javaKeywordTokenPattern, TextColor.ANSI.CYAN);
        formatToken(str, string, _javaCharacterPattern, TextColor.ANSI.CYAN);
        formatToken(str, string, _javaCommentPattern, TextColor.ANSI.GREEN);
        formatToken(str, string, _javaStringPattern, TextColor.ANSI.CYAN);
    }

    private static Pattern _bracketPattern = Pattern.compile("\\{|\\}");

    @Override
    public int getIndentationLevel(BufferContext bufferContext) {
        int indentation = 0;
        int cursor = bufferContext.getBuffer().getCursor().getPosition();
        var matcher = _bracketPattern.matcher( bufferContext.getBuffer().getString());
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
        if (character.equals("}")) {
            return true;
        }
        return false;
    }

    @Override
    public TextDocumentItem getTextDocument(BufferContext bufferContext) {
        return new TextDocumentItem(bufferContext.getBuffer().getPath().toFile().toURI().toString(),
                "java",
                bufferContext.getBuffer().getVersionedTextDocumentID().getVersion(),
                bufferContext.getBuffer().getString());
    }
}
