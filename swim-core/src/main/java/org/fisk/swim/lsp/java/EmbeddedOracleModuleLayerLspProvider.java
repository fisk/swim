package org.fisk.swim.lsp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

final class EmbeddedOracleModuleLayerLspProvider implements JavaLspProvider {
    private static final Logger _log = LogFactory.createLog();
    private static final Object NETBEANS_STDIO_LOCK = new Object();
    private static final String EMBEDDED_LAUNCH_HINT =
            "Embedded Oracle Java LSP requires launching Swim with the NetBeans JVM compatibility flags. "
                    + "Use ~/.swim/image/bin/swim or an equivalent launcher.";
    private final Path _extensionPath;

    EmbeddedOracleModuleLayerLspProvider(Path extensionPath) {
        _extensionPath = extensionPath;
    }

    static Path resolveOracleExtensionPath() {
        return OracleNbcodeLspProvider.resolveOracleExtensionPath();
    }

    @Override
    public boolean isAvailable() {
        return hasExtensionPayload();
    }

    @Override
    public Session start(
            Path projectPath,
            Path workspacePath,
            LanguageClient client,
            ClientCapabilities clientCapabilities,
            Object initializationOptions,
            long timeoutSeconds) throws Exception {
        return start(projectPath, workspacePath, client, clientCapabilities, initializationOptions, timeoutSeconds, true);
    }

    private Session start(
            Path projectPath,
            Path workspacePath,
            LanguageClient client,
            ClientCapabilities clientCapabilities,
            Object initializationOptions,
            long timeoutSeconds,
            boolean allowWorkspaceReset) throws Exception {
        _log.debug("Starting embedded Oracle Java LSP for project {}", projectPath);
        requireJvmCompatibility();
        Path nbcodeRoot = _extensionPath.resolve("nbcode");
        Path platformHome = nbcodeRoot.resolve("platform");
        Path userDir = workspacePath.resolve("userdir");
        Path cacheDir = workspacePath.resolve("cachedir");
        Files.createDirectories(userDir);
        Files.createDirectories(cacheDir);
        List<String> clusterPaths = clusterPaths(nbcodeRoot);
        _log.debug("Embedded NetBeans clusters: {}", clusterPaths);
        var loader = new URLClassLoader(
                platformLibClasspath(platformHome).toArray(URL[]::new),
                EmbeddedOracleModuleLayerLspProvider.class.getClassLoader());
        var socketFuture = new CompletableFuture<Socket>();
        var serverSocket = new ServerSocket(0, 1, Inet4Address.getLoopbackAddress());
        Class<?> mainImplClass = loader.loadClass("org.netbeans.MainImpl");
        Method execute = mainImplClass.getDeclaredMethod(
                "execute",
                String[].class,
                InputStream.class,
                OutputStream.class,
                OutputStream.class,
                AtomicReference.class);
        execute.setAccessible(true);

        var launchArgs = buildLaunchArguments(userDir, cacheDir, clusterPaths, serverSocket.getLocalPort());
        var systemProperties = netBeansSystemProperties(platformHome, userDir, clusterPaths);
        _log.debug("Embedded NetBeans launch args: {}", java.util.Arrays.toString(launchArgs));
        _log.debug("Embedded NetBeans system properties: {}", systemProperties);
        var previousProperties = applySystemProperties(systemProperties);
        Socket languageServerSocket = null;
        var bootThread = new Thread(
                () -> runNetBeansBoot(loader, execute, launchArgs, socketFuture),
                "swim-oracle-java-embedded");
        bootThread.setDaemon(true);
        bootThread.start();

        var acceptThread = new Thread(() -> acceptLanguageServerSocket(serverSocket, socketFuture), "swim-oracle-java-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        try {
            languageServerSocket = socketFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            _log.debug("Embedded NetBeans accepted language server socket on {}", serverSocket.getLocalPort());
            var launcher = LSPLauncher.createClientLauncher(client, languageServerSocket.getInputStream(), languageServerSocket.getOutputStream());
            launcher.startListening();
            LanguageServer server = launcher.getRemoteProxy();

            var initParams = new InitializeParams();
            initParams.setRootUri(projectPath.toUri().toString());
            initParams.setWorkspaceFolders(List.of(new WorkspaceFolder(
                    projectPath.toUri().toString(),
                    projectPath.getFileName().toString())));
            initParams.setCapabilities(clientCapabilities);
            initParams.setInitializationOptions(initializationOptions);
            var initialized = server.initialize(initParams).get(timeoutSeconds, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());

            Socket finalLanguageServerSocket = languageServerSocket;
            AutoCloseable closeable = () -> {
                try {
                    server.shutdown().get(5, TimeUnit.SECONDS);
                    server.exit();
                } catch (Exception e) {
                }
                closeBootstrapResources(finalLanguageServerSocket, serverSocket, loader, previousProperties);
            };

            return new Session(server, initialized.getCapabilities(), closeable, "oracle-embedded");
        } catch (Exception e) {
            closeBootstrapResources(languageServerSocket, serverSocket, loader, previousProperties);
            if (allowWorkspaceReset && shouldResetWorkspace(workspacePath, e)) {
                _log.warn("Resetting embedded Java LSP workspace at {} after recoverable bootstrap failure", workspacePath);
                deleteRecursively(workspacePath);
                return start(projectPath, workspacePath, client, clientCapabilities, initializationOptions, timeoutSeconds, false);
            }
            throw e;
        }
    }

    boolean hasExtensionPayload() {
        return _extensionPath != null && Files.isDirectory(_extensionPath);
    }

    static boolean hasRequiredJvmAccess() {
        try (var loader = new URLClassLoader(new URL[0], null)) {
            return ModuleLayer.boot().findModule("java.instrument").isPresent()
                    && Object.class.getModule().isOpen("java.net", loader.getUnnamedModule());
        } catch (IOException e) {
            return false;
        }
    }

    private static void requireJvmCompatibility() {
        if (hasRequiredJvmAccess()) {
            return;
        }
        throw new IllegalStateException(EMBEDDED_LAUNCH_HINT);
    }

    private void runNetBeansBoot(
            ClassLoader loader,
            Method execute,
            String[] args,
            CompletableFuture<Socket> socketFuture) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        synchronized (NETBEANS_STDIO_LOCK) {
            PrintStream previousOut = System.out;
            PrintStream previousErr = System.err;
            try (var sink = new PrintStream(OutputStream.nullOutputStream(), true)) {
                System.setOut(sink);
                System.setErr(sink);
                var mainMethodReference = new AtomicReference<Method>();
                int exitCode = ((Number) execute.invoke(null, args, System.in, sink, sink, mainMethodReference)).intValue();
                if (exitCode != 0) {
                    socketFuture.completeExceptionally(
                            new IllegalStateException("Embedded NetBeans bootstrap exited with code " + exitCode));
                    _log.warn("Embedded NetBeans bootstrap exited with code {}", exitCode);
                    return;
                }
                Method mainMethod = mainMethodReference.get();
                if (mainMethod == null) {
                    socketFuture.completeExceptionally(
                            new IllegalStateException("Embedded NetBeans bootstrap did not expose a main entrypoint"));
                    _log.warn("Embedded NetBeans bootstrap completed without a main entrypoint");
                    return;
                }
                mainMethod.invoke(null, (Object) args);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                socketFuture.completeExceptionally(cause);
                _log.warn("Embedded NetBeans bootstrap failed", e);
            } finally {
                System.setOut(previousOut);
                System.setErr(previousErr);
                current.setContextClassLoader(previous);
            }
        }
    }

    private java.util.Map<String, String> applySystemProperties(java.util.Map<String, String> systemProperties) {
        var previousProperties = new java.util.HashMap<String, String>();
        for (var entry : systemProperties.entrySet()) {
            previousProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), entry.getValue());
        }
        return previousProperties;
    }

    private void restoreSystemProperties(java.util.Map<String, String> previousProperties) {
        for (var entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    static boolean shouldResetWorkspace(Path workspacePath, Throwable failure) {
        if (workspacePath == null || failure == null) {
            return false;
        }
        if (containsBootstrapExitCode(failure, "-255")) {
            return true;
        }
        Path messagesLog = workspacePath.resolve("userdir").resolve("var").resolve("log").resolve("messages.log");
        if (!Files.isRegularFile(messagesLog)) {
            return false;
        }
        try {
            String logText = Files.readString(messagesLog);
            return logText.contains("ClassNotFoundException: org.netbeans.api.maven.MavenActions")
                    || logText.contains("ClassNotFoundException: maven.actions.override");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsBootstrapExitCode(Throwable failure, String code) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static void closeBootstrapResources(
            Socket languageServerSocket,
            ServerSocket serverSocket,
            URLClassLoader loader,
            java.util.Map<String, String> previousProperties) {
        try {
            if (languageServerSocket != null) {
                languageServerSocket.close();
            }
        } catch (Exception e) {
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
        }
        try {
            if (loader != null) {
                loader.close();
            }
        } catch (Exception e) {
        }
        if (previousProperties != null) {
            for (var entry : previousProperties.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private void acceptLanguageServerSocket(ServerSocket serverSocket, CompletableFuture<Socket> socketFuture) {
        try {
            _log.debug("Waiting for embedded language server connection on {}", serverSocket.getLocalPort());
            Socket socket = serverSocket.accept();
            _log.debug("Embedded language server connected from {}", socket.getRemoteSocketAddress());
            socketFuture.complete(socket);
        } catch (Exception e) {
            socketFuture.completeExceptionally(e);
        }
    }

    private String[] buildLaunchArguments(Path userDir, Path cacheDir, List<String> clusterPaths, int port) {
        var args = new ArrayList<String>();
        args.add("--nogui");
        args.add("--nosplash");
        args.add("--branding");
        args.add("nbcode");
        args.add("--userdir");
        args.add(userDir.toString());
        args.add("--modules");
        args.add("--list");
        args.add("--cachedir");
        args.add(cacheDir.toString());
        args.add("--locale");
        args.add(Locale.getDefault().toLanguageTag());
        args.add("--start-java-language-server=connect:" + port);
        return args.toArray(String[]::new);
    }

    private List<URL> platformLibClasspath(Path platformHome) throws IOException {
        return OracleRuntimeClasspath.discoverFlat(platformHome.resolve("lib"));
    }

    private java.util.Map<String, String> netBeansSystemProperties(
            Path platformHome,
            Path userDir,
            List<String> clusterPaths) {
        return java.util.Map.of(
                "jdk.home", System.getProperty("java.home"),
                "netbeans.dirs", String.join(java.io.File.pathSeparator, clusterPaths),
                "netbeans.extra.dirs", String.join(java.io.File.pathSeparator, clusterPaths),
                "netbeans.home", platformHome.toAbsolutePath().normalize().toString(),
                "netbeans.user", userDir.toString());
    }

    private List<String> clusterPaths(Path nbcodeRoot) throws IOException {
        Path clustersFile = nbcodeRoot.resolve("etc").resolve("nbcode.clusters");
        if (Files.isRegularFile(clustersFile)) {
            return Files.readAllLines(clustersFile).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> !line.equals("platform"))
                    .map(nbcodeRoot::resolve)
                    .filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .toList();
        }
        try (var stream = Files.list(nbcodeRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("bin") && !name.equals("etc") && !name.equals("platform");
                    })
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .sorted()
                    .toList();
        }
    }
}
