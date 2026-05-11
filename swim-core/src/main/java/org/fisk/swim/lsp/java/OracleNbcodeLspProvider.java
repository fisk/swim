package org.fisk.swim.lsp.java;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

final class OracleNbcodeLspProvider implements JavaLspProvider {
    private static final Logger _log = LogFactory.createLog();

    private final Path _extensionPath;

    OracleNbcodeLspProvider(Path extensionPath) {
        _extensionPath = extensionPath;
    }

    static Path resolveOracleExtensionPath() {
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

    static Path findOracleExtensionPath(Path extensionsDir) {
        if (!Files.isDirectory(extensionsDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(extensionsDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("oracle.oracle-java") || name.startsWith("oracle.oracle-java-");
                    })
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new RuntimeException("Unable to inspect VS Code extensions in " + extensionsDir, e);
        }
    }

    static String getNbcodeExecutableName(String osName, String arch) {
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (!normalizedOs.contains("mac") && !normalizedOs.contains("darwin") && normalizedOs.contains("win")) {
            String normalizedArch = arch.toLowerCase(Locale.ROOT);
            return normalizedArch.contains("x64") || normalizedArch.contains("x86_64")
                    || normalizedArch.contains("amd64") || normalizedArch.contains("arm64")
                    ? "nbcode64.exe"
                    : "nbcode.exe";
        }
        return "nbcode.sh";
    }

    static Path findNbcode(Path oracleExtensionPath, String osName, String arch) {
        Path path = oracleExtensionPath.resolve("nbcode").resolve("bin").resolve(getNbcodeExecutableName(osName, arch));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Cannot execute " + path);
        }
        return path;
    }

    @Override
    public boolean isAvailable() {
        return _extensionPath != null && Files.isDirectory(_extensionPath);
    }

    @Override
    public Session start(
            Path projectPath,
            Path workspacePath,
            LanguageClient client,
            ClientCapabilities clientCapabilities,
            Object initializationOptions,
            long timeoutSeconds) throws Exception {
        Path userDir = workspacePath.resolve("userdir");
        Files.createDirectories(userDir);

        Path nbcodePath = findNbcode(_extensionPath, System.getProperty("os.name"), System.getProperty("os.arch"));
        ensureExecutablePermissions(nbcodePath);

        _log.info("Oracle Java extension path: " + _extensionPath);
        _log.info("Oracle Java userdir path: " + userDir);

        var command = buildLaunchCommand(nbcodePath, userDir);
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(userDir.toFile());
        Process process = processBuilder.start();

        _log.info("Process command: " + String.join(" ", command));
        _log.info("Process PID: " + process.pid());

        var stderrThread = new Thread(() -> logErrorStream(process.getErrorStream()), "swim-java-lsp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        var socketFuture = new CompletableFuture<Socket>();
        var stdoutThread = new Thread(() -> monitorLanguageServerOutput(process.getInputStream(), socketFuture), "swim-oracle-java-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        try {
            Socket serverSocket = socketFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            var launcher = LSPLauncher.createClientLauncher(client, serverSocket.getInputStream(), serverSocket.getOutputStream());
            var listeningFuture = launcher.startListening();
            var server = launcher.getRemoteProxy();

            var initParams = new InitializeParams();
            initParams.setRootUri(projectPath.toUri().toString());
            initParams.setWorkspaceFolders(List.of(new WorkspaceFolder(
                    projectPath.toUri().toString(),
                    projectPath.getFileName().toString())));
            initParams.setCapabilities(clientCapabilities);
            initParams.setInitializationOptions(initializationOptions);

            var initialized = server.initialize(initParams).get();
            server.initialized(new InitializedParams());

            AutoCloseable closeable = () -> {
                try {
                    server.shutdown().get(5, TimeUnit.SECONDS);
                    server.exit();
                } catch (Exception e) {
                }
                try {
                    serverSocket.close();
                } catch (IOException e) {
                }
                process.destroy();
            };

            return new Session(server, initialized.getCapabilities(), closeable, "oracle-process");
        } catch (TimeoutException e) {
            process.destroy();
            throw new RuntimeException("Timed out waiting for Oracle Java language server socket", e);
        } catch (Exception e) {
            process.destroy();
            throw e;
        }
    }

    private void logErrorStream(InputStream errorStream) {
        try (var reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                _log.info("oracle-java stderr: " + line);
            }
        } catch (IOException e) {
            _log.debug("Error stream reader stopped", e);
        }
    }

    private void ensureExecutablePermissions(Path nbcodePath) throws IOException {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Set<PosixFilePermission> executable = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(nbcodePath, executable);
        Path nbexec = _extensionPath.resolve("nbcode").resolve("platform").resolve("lib").resolve("nbexec.sh");
        if (Files.isRegularFile(nbexec)) {
            Files.setPosixFilePermissions(nbexec, executable);
        }
    }

    private List<String> getNbcodeClusterPaths() throws IOException {
        Path nbcodeRoot = _extensionPath.resolve("nbcode");
        try (Stream<Path> entries = Files.list(nbcodeRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("bin") && !name.equals("etc");
                    })
                    .sorted()
                    .map(Path::toString)
                    .toList();
        }
    }

    private String detectJdkHome() {
        Path javaHome = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
        if (Files.isRegularFile(javaHome.resolve("bin").resolve("javac"))) {
            return javaHome.toString();
        }
        Path parent = javaHome.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("bin").resolve("javac"))) {
            return parent.toString();
        }
        return null;
    }

    private List<String> buildLaunchCommand(Path nbcodePath, Path userDir) throws IOException {
        var command = new ArrayList<String>();
        command.add(nbcodePath.toString());

        String jdkHome = detectJdkHome();
        if (jdkHome != null) {
            command.add("--jdkhome");
            command.add(jdkHome);
        }

        command.add("--userdir");
        command.add(userDir.toString());
        command.add("--modules");
        command.add("--list");
        command.add("-J-XX:PerfMaxStringConstLength=10240");
        command.add("--locale");
        command.add(Locale.getDefault().toLanguageTag());
        command.add("--start-java-language-server=listen-hash:0");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED");
        command.add("-J-Dnetbeans.extra.dirs=" + String.join(File.pathSeparator, getNbcodeClusterPaths()));
        return command;
    }

    private Socket connectToLanguageServer(int port, String hash) throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.getOutputStream().write(hash.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        return socket;
    }

    private void monitorLanguageServerOutput(InputStream outputStream, CompletableFuture<Socket> socketFuture) {
        Pattern pattern = Pattern.compile("Java Language Server listening at port (\\d+) with hash (.+)");
        try (var reader = new BufferedReader(new InputStreamReader(outputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                _log.info("oracle-java stdout: " + line);
                var matcher = pattern.matcher(line);
                if (matcher.find() && !socketFuture.isDone()) {
                    socketFuture.complete(connectToLanguageServer(
                            Integer.parseInt(matcher.group(1)),
                            matcher.group(2)));
                }
            }
            if (!socketFuture.isDone()) {
                socketFuture.completeExceptionally(new IOException("Oracle Java language server did not publish a socket endpoint"));
            }
        } catch (Exception e) {
            if (!socketFuture.isDone()) {
                socketFuture.completeExceptionally(e);
            }
        }
    }
}
