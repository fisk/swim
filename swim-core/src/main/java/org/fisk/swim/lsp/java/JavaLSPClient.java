package org.fisk.swim.lsp.java;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.TextColor;

public class JavaLSPClient extends Thread implements LanguageMode {
    private final Object _lock = new Object();
    private boolean _enabled = true;
    private boolean _launchAttempted = false;
    private boolean _started = false;
    private boolean _startupComplete = false;
    private Throwable _startupError;
    private Path _projectPath;
    private Path _workspacePath;
    private Path _swimHomePath;
    private Path _eclipsePath;
    private Process _process;
    private Thread _shutdownHook;
    private LanguageServer _server;
    private Object _capabilities;
    private final Map<String, TextColor> _foregroundColours = new HashMap<>();
    private static final org.slf4j.Logger _log = org.slf4j.LoggerFactory.getLogger(JavaLSPClient.class);

    public JavaLSPClient() {
    }

    private static Path getWorkspacePath(Path swimHomePath, Path projectPath) {
        return swimHomePath.resolve("workspace").resolve(projectPath.getFileName().toString());
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
            public void languageStatus(Object params) {
                _log.info("language/status: " + params);
            }
        };
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
        _process = processBuilder.start();

        _shutdownHook = createShutdownHook();
        Runtime.getRuntime().addShutdownHook(_shutdownHook);

        _log.info("Proccess command: " + String.join(" ", command));
        _log.info("Process PID: " + _process.pid());

        var stderrThread = new Thread(() -> logErrorStream(_process.getErrorStream()), "swim-java-lsp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        _log.info("Starting LSP server...");
        var istream = _process.getInputStream();
        var ostream = _process.getOutputStream();
        var client = createLanguageClient();
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
        workspace.setConfiguration(false);

        var executeCommand = new ExecuteCommandCapabilities();
        workspace.setExecuteCommand(executeCommand);

        var textDocument = new TextDocumentClientCapabilities();

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

    public void shutdown() {
    }

    private Path findLauncherJar(Path eclipsePath) {
        return eclipsePath;
    }

    private String getConfigurationDirectoryName(String osName, String osArch) {
        return "config";
    }

    private Thread createShutdownHook() {
        return new Thread(() -> {});
    }

    @Override
    public void didOpen(BufferContext bufferContext) {
    }

    @Override
    public void didClose(BufferContext bufferContext) {
    }

    @Override
    public void didSave(BufferContext bufferContext) {
    }
}
