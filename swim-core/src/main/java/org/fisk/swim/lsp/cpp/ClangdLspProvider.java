package org.fisk.swim.lsp.cpp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class ClangdLspProvider {
    public record Session(LanguageServer server, ServerCapabilities capabilities, AutoCloseable closeable, String description) {
    }

    private static final Logger _log = LogFactory.createLog();
    private final Path _clangdPath;

    public ClangdLspProvider() {
        this(resolveDefaultExecutable(System.getenv("PATH"), System.getProperty("os.name")));
    }

    ClangdLspProvider(Path clangdPath) {
        _clangdPath = clangdPath == null ? null : clangdPath.toAbsolutePath().normalize();
    }

    public boolean isAvailable() {
        return _clangdPath != null;
    }

    public Session start(
            Path projectPath,
            Path workspacePath,
            LanguageClient client,
            ClientCapabilities clientCapabilities,
            Object initializationOptions,
            long timeoutSeconds) throws Exception {
        if (_clangdPath == null) {
            throw new IllegalStateException("clangd not found on PATH");
        }

        Files.createDirectories(workspacePath);

        var command = buildCommand(_clangdPath);
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspacePath.toFile());
        Process process = processBuilder.start();

        _log.debug("Starting clangd with command {}", command);
        _log.debug("clangd workspace root: {}", projectPath);
        _log.debug("clangd scratch workspace: {}", workspacePath);

        var stderrThread = new Thread(() -> logErrorStream(process.getErrorStream()), "swim-clangd-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        try {
            var launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(), process.getOutputStream());
            var listeningFuture = launcher.startListening();
            var server = launcher.getRemoteProxy();

            var initParams = new InitializeParams();
            initParams.setRootUri(projectPath.toUri().toString());
            initParams.setWorkspaceFolders(List.of(new WorkspaceFolder(
                    projectPath.toUri().toString(),
                    projectPath.getFileName() == null ? projectPath.toString() : projectPath.getFileName().toString())));
            initParams.setCapabilities(clientCapabilities);
            initParams.setInitializationOptions(initializationOptions);

            var initialized = server.initialize(initParams).get(timeoutSeconds, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());

            AutoCloseable closeable = () -> {
                try {
                    server.shutdown().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                }
                try {
                    server.exit();
                } catch (Exception e) {
                }
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                listeningFuture.cancel(true);
            };

            return new Session(server, initialized.getCapabilities(), closeable, "clangd");
        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        }
    }

    static List<String> buildCommand(Path clangdPath) {
        var command = new ArrayList<String>();
        command.add(clangdPath.toString());
        command.add("--background-index");
        return List.copyOf(command);
    }

    static Path findExecutableOnPath(String pathEnv, String osName) {
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String element : pathEnv.split(java.io.File.pathSeparator)) {
            if (element == null || element.isBlank()) {
                continue;
            }
            Path directory = Paths.get(element);
            for (String candidateName : executableNames(osName)) {
                Path candidate = directory.resolve(candidateName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    static Path resolveDefaultExecutable(String pathEnv, String osName) {
        Path fromPath = findExecutableOnPath(pathEnv, osName);
        if (fromPath != null) {
            return fromPath;
        }
        for (String candidate : fallbackExecutableCandidates(osName)) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static List<String> executableNames(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) {
            return List.of("clangd.exe", "clangd.cmd", "clangd.bat");
        }
        return List.of("clangd");
    }

    private static List<String> fallbackExecutableCandidates(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) {
            return List.of(
                    "C:\\Program Files\\LLVM\\bin\\clangd.exe",
                    "C:\\Program Files (x86)\\LLVM\\bin\\clangd.exe");
        }
        return List.of(
                "/usr/bin/clangd",
                "/opt/homebrew/bin/clangd",
                "/usr/local/bin/clangd");
    }

    private void logErrorStream(InputStream errorStream) {
        try (var reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                _log.debug("clangd stderr: {}", line);
            }
        } catch (IOException e) {
            _log.debug("clangd stderr reader stopped", e);
        }
    }
}
