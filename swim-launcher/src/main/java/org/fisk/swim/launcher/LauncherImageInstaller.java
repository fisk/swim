package org.fisk.swim.launcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LauncherImageInstaller {
    private static final Pattern DEFAULT_OPTIONS_PATTERN =
            Pattern.compile("(?m)^default_options=\"(.*)\"$");
    private static final String APP_NAME = "swim";
    private static final Path PUBLIC_LAUNCHER_TARGET = Path.of("bin", APP_NAME);
    private static final String FINAL_FIELD_MUTATION_OPTION = "--enable-final-field-mutation=ALL-UNNAMED";
    private static final List<String> CLIENT_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-Xmx128M");
    private static final List<String> APP_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-Xmx4G",
            "-XX:SoftMaxHeapSize=1G",
            "--sun-misc-unsafe-memory-access=allow");
    private static final List<String> SERVER_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-Xmx128M",
            "--enable-native-access=org.fisk.swim.session");
    private static final List<String> NETBEANS_RUNTIME_MODULES = List.of(
            "java.base", "java.compiler", "java.datatransfer", "java.desktop", "java.instrument",
            "java.logging", "java.management", "java.management.rmi", "java.naming", "java.net.http",
            "java.prefs", "java.rmi", "java.scripting", "java.security.jgss", "java.security.sasl",
            "java.smartcardio", "java.sql", "java.sql.rowset", "java.transaction.xa", "java.xml",
            "java.xml.crypto", "jdk.accessibility", "jdk.attach", "jdk.charsets", "jdk.compiler",
            "jdk.crypto.cryptoki", "jdk.dynalink", "jdk.editpad", "jdk.httpserver", "jdk.internal.ed",
            "jdk.internal.jvmstat", "jdk.internal.le", "jdk.internal.md", "jdk.internal.opt", "jdk.jartool",
            "jdk.javadoc", "jdk.jconsole", "jdk.jdeps", "jdk.jdi", "jdk.jdwp.agent", "jdk.jfr",
            "jdk.jlink", "jdk.jshell", "jdk.jsobject", "jdk.jstatd", "jdk.localedata", "jdk.management",
            "jdk.management.agent", "jdk.management.jfr", "jdk.naming.dns", "jdk.naming.rmi", "jdk.net",
            "jdk.nio.mapmode", "jdk.sctp", "jdk.security.auth", "jdk.security.jgss", "jdk.unsupported",
            "jdk.unsupported.desktop", "jdk.xml.dom", "jdk.zipfs");

    private LauncherImageInstaller() {
    }

    public static void main(String[] args) throws IOException {
        Path swimHome;
        if (args.length == 1 && args[0] != null && !args[0].isBlank()) {
            swimHome = Path.of(args[0]);
        } else {
            swimHome = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        }
        install(swimHome);
    }

    static void install(Path swimHome) throws IOException {
        Path launcherTarget = swimHome.resolve("swim-launcher").resolve("target");
        Path imageRoot = swimHome.resolve("image");

        Path launcherJar = findLauncherJar(launcherTarget);
        deleteRecursively(imageRoot);
        runJlink(swimHome, launcherJar, launcherTarget.resolve("runtime-libs"), imageRoot);
        installJavaLauncher(imageRoot);
        installSourceLauncher(swimHome.resolve(PUBLIC_LAUNCHER_TARGET),
                imageRoot.resolve("bin").resolve(javaExecutableName()), resolveNetBeansJvmArgs(swimHome));
        Files.deleteIfExists(imageRoot.resolve("bin").resolve(APP_NAME));
    }

    static Path findLauncherJar(Path launcherTarget) throws IOException {
        try (var stream = Files.list(launcherTarget)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("swim-launcher-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-tests.jar"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("Unable to find launcher jar in " + launcherTarget));
        }
    }

    static void runJlink(Path swimHome, Path launcherJar, Path runtimeLibs, Path output) throws IOException {
        List<String> launcherOptions = resolveNetBeansJvmArgs(swimHome);
        runTool("jlink", jlinkArgs(launcherJar, runtimeLibs, output, launcherOptions));
    }

    static List<String> jlinkArgs(Path launcherJar, Path runtimeLibs, Path output, List<String> launcherOptions) {
        var args = new ArrayList<String>();
        args.add("--module-path");
        args.add(String.join(
                System.getProperty("path.separator"),
                launcherJar.toString(),
                runtimeLibs.toString(),
                javaHomeJmods()));
        args.add("--add-modules");
        args.add(String.join(",", collectJlinkModules(launcherOptions)));
        args.add("--launcher");
        args.add(APP_NAME + "=org.fisk.swim.launcher/org.fisk.swim.launcher.Main");
        for (String option : CLIENT_JVM_OPTIONS) {
            args.add("--add-options=" + option);
        }
        for (String option : launcherOptions) {
            args.add("--add-options=" + option);
        }
        args.add("--strip-debug");
        args.add("--no-header-files");
        args.add("--no-man-pages");
        args.add("--output");
        args.add(output.toString());
        return List.copyOf(args);
    }

    static List<String> resolveNetBeansJvmArgs(Path swimHome) throws IOException {
        Path nbcodeConf = swimHome.resolve("deps").resolve("oracle.oracle-java")
                .resolve("nbcode").resolve("etc").resolve("nbcode.conf");
        var args = new ArrayList<String>();
        if (Files.isRegularFile(nbcodeConf)) {
            String content = Files.readString(nbcodeConf, StandardCharsets.UTF_8);
            Matcher matcher = DEFAULT_OPTIONS_PATTERN.matcher(content);
            if (matcher.find()) {
                for (String token : matcher.group(1).trim().split("\\s+")) {
                    if (token.startsWith("-J")) {
                        String jvmArg = token.substring(2);
                        if (isSupportedJvmArg(jvmArg)) {
                            args.add(jvmArg);
                        }
                    }
                }
            }
        }
        if (ModuleLayer.boot().findModule("java.instrument").isPresent()) {
            args.add("--add-modules=java.instrument");
        }
        return args;
    }

    static List<String> collectJlinkModules(List<String> launcherOptions) {
        Set<String> modules = new LinkedHashSet<>();
        modules.add("org.fisk.swim.launcher");
        modules.add("org.fisk.swim.session");
        for (String moduleName : NETBEANS_RUNTIME_MODULES) {
            if (ModuleLayer.boot().findModule(moduleName).isPresent()) {
                modules.add(moduleName);
            }
        }
        for (String option : launcherOptions) {
            if (option.startsWith("--add-modules=")) {
                for (String moduleName : option.substring("--add-modules=".length()).split(",")) {
                    if (!moduleName.isBlank()) {
                        modules.add(moduleName);
                    }
                }
                continue;
            }
            if (option.startsWith("--add-opens=") || option.startsWith("--add-exports=")) {
                String value = option.substring(option.indexOf('=') + 1);
                int slash = value.indexOf('/');
                if (slash > 0) {
                    modules.add(value.substring(0, slash));
                }
            }
        }
        return new ArrayList<>(modules);
    }

    private static boolean isSupportedJvmArg(String arg) {
        return isSupportedAddExports(arg)
                || isSupportedAddOpens(arg)
                || isSupportedAddModules(arg)
                || arg.startsWith("--enable-native-access=")
                || arg.startsWith("-Djava.awt.headless=")
                || arg.startsWith("-DTopSecurityManager.disable=")
                || "-XX:+IgnoreUnrecognizedVMOptions".equals(arg);
    }

    private static boolean isSupportedAddExports(String arg) {
        return isSupportedQualifiedModulePackageOption(arg, "--add-exports=");
    }

    private static boolean isSupportedAddOpens(String arg) {
        return isSupportedQualifiedModulePackageOption(arg, "--add-opens=");
    }

    private static boolean isSupportedQualifiedModulePackageOption(String arg, String prefix) {
        if (!arg.startsWith(prefix)) {
            return false;
        }
        String remainder = arg.substring(prefix.length());
        int slash = remainder.indexOf('/');
        int equals = remainder.indexOf('=');
        if (slash <= 0 || equals <= slash + 1) {
            return false;
        }
        String moduleName = remainder.substring(0, slash);
        String packageName = remainder.substring(slash + 1, equals);
        Optional<Module> module = ModuleLayer.boot().findModule(moduleName);
        return module.isPresent() && module.get().getPackages().contains(packageName);
    }

    private static boolean isSupportedAddModules(String arg) {
        if (!arg.startsWith("--add-modules=")) {
            return false;
        }
        String modules = arg.substring("--add-modules=".length());
        if (modules.isBlank()) {
            return false;
        }
        for (String moduleName : modules.split(",")) {
            if (ModuleLayer.boot().findModule(moduleName).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String javaHomeJmods() {
        String javaHome = System.getProperty("java.home");
        Path javaHomePath = Paths.get(javaHome);
        Path direct = javaHomePath.resolve("jmods");
        if (Files.isDirectory(direct)) {
            return direct.toString();
        }
        Path parent = javaHomePath.getParent();
        if (parent != null) {
            Path sibling = parent.resolve("jmods");
            if (Files.isDirectory(sibling)) {
                return sibling.toString();
            }
        }
        throw new IllegalStateException("Unable to locate JDK jmods for " + javaHomePath);
    }

    private static Path javaHomeBin() {
        Path javaHomePath = Paths.get(System.getProperty("java.home"));
        Path direct = javaHomePath.resolve("bin");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = javaHomePath.getParent();
        if (parent != null) {
            Path sibling = parent.resolve("bin");
            if (Files.isDirectory(sibling)) {
                return sibling;
            }
        }
        throw new IllegalStateException("Unable to locate JDK bin directory for " + javaHomePath);
    }

    static void installJavaLauncher(Path imageRoot) throws IOException {
        Path source = javaHomeBin().resolve(javaExecutableName());
        Path target = imageRoot.resolve("bin").resolve(javaExecutableName());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        target.toFile().setExecutable(true, false);
    }

    static void installSourceLauncher(Path launcher, List<String> launcherOptions) throws IOException {
        installSourceLauncher(launcher, launcher.getParent().resolve(javaExecutableName()), launcherOptions);
    }

    static void installSourceLauncher(Path launcher, Path embeddedJava, List<String> launcherOptions) throws IOException {
        var appOptions = new ArrayList<String>();
        appOptions.addAll(APP_JVM_OPTIONS);
        appOptions.addAll(launcherOptions);
        var clientOptions = new ArrayList<String>();
        clientOptions.addAll(CLIENT_JVM_OPTIONS);
        Files.createDirectories(launcher.getParent());
        embeddedJava = embeddedJava.toAbsolutePath().normalize();
        Files.writeString(launcher, sourceLauncher(embeddedJava, javaShebangOptions(clientOptions),
                javaListLiteral(appOptions), javaListLiteral(SERVER_JVM_OPTIONS),
                javaStringLiteral(FINAL_FIELD_MUTATION_OPTION)), StandardCharsets.UTF_8);
        launcher.toFile().setExecutable(true, false);
    }

    private static String javaExecutableName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private static String javaShebangOptions(List<String> values) {
        return values.stream().collect(java.util.stream.Collectors.joining(" "));
    }

    private static String javaListLiteral(List<String> values) {
        return values.stream()
                .map(LauncherImageInstaller::javaStringLiteral)
                .collect(java.util.stream.Collectors.joining(", ", "List.of(", ")"));
    }

    private static String javaStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sourceLauncher(
            Path embeddedJava,
            String clientOptions,
            String appOptions,
            String serverOptions,
            String finalFieldMutationOption) {
        return String.format("""
                #!%s %s --source 25
                import java.io.*;
                import java.net.*;
                import java.nio.channels.*;
                import java.nio.file.*;
                import java.time.*;
                import java.util.*;

                class swim {
                    private static final String MAGIC = "SWIM_SESSION_6";
                    private static final String APP_MODULE = "org.fisk.swim.launcher/org.fisk.swim.launcher.Main";
                    private static final String SERVER_MODULE = "org.fisk.swim.session/org.fisk.swim.session.server.SwimSessionServerMain";
                    private static final String FINAL_FIELD_MUTATION_OPTION = %s;
                    private static final List<String> APP_JVM_OPTIONS = %s;
                    private static final List<String> SERVER_JVM_OPTIONS = %s;
                    private static final Path EMBEDDED_JAVA = Path.of(%s);
                    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(5);

                    public static void main(String[] args) throws Exception {
                        if (args.length == 1 && "--swim-source-client-self-test".equals(args[0])) {
                            return;
                        }
                        Path socket = defaultSocketPath();
                        ControlCommand controlCommand = controlCommand(args);
                        if (controlCommand != null) {
                            runControlCommand(socket, controlCommand);
                            return;
                        }
                        LaunchRequest request = LaunchRequest.parse(args);
                        Path launcher = launcherPath();
                        Path java = EMBEDDED_JAVA;
                        Path swimHome = swimHome(launcher);
                        ensureServer(socket, swimHome, java);
                        try (SocketChannel channel = connect(socket)) {
                            TerminalSize terminalSize = sendAttach(channel, java, request);
                            SessionGuard sessionGuard = new SessionGuard(socket, request.sessionName());
                            boolean gracefulExit = false;
                            try {
                                try (TerminalMode ignored = TerminalMode.enterRawMode()) {
                                    Thread resizeRelay = Thread.ofVirtual().name("swim-client-resize")
                                            .start(() -> relayResize(socket, request.sessionName(), terminalSize));
                                    Thread inputRelay = Thread.ofVirtual().name("swim-client-input").start(() -> {
                                        try {
                                            copy(System.in, Channels.newOutputStream(channel));
                                            channel.shutdownOutput();
                                        } catch (IOException e) {
                                        }
                                    });
                                    try {
                                        copy(Channels.newInputStream(channel), System.out);
                                        gracefulExit = true;
                                    } finally {
                                        resizeRelay.interrupt();
                                    }
                                    inputRelay.join(Duration.ofSeconds(1));
                                    resizeRelay.join(Duration.ofSeconds(1));
                                }
                            } finally {
                                sessionGuard.close(gracefulExit);
                            }
                        }
                    }

                    private record ControlCommand(String name, String target) {
                    }

                    private record LaunchRequest(String sessionName, List<String> launchArgs) {
                        static LaunchRequest parse(String[] args) throws IOException {
                            if (args != null && args.length > 0 && "--attach".equals(args[0])) {
                                if (args.length != 2 || args[1].isBlank()) {
                                    throw new IOException("usage: swim --attach <session>");
                                }
                                return new LaunchRequest(normalizeName(args[1]), List.of());
                            }
                            return new LaunchRequest(swim.sessionName(), Arrays.asList(args == null ? new String[0] : args));
                        }
                    }

                    private static Path launcherPath() throws IOException {
                        String[] processArgs = ProcessHandle.current().info().arguments().orElse(new String[0]);
                        for (int i = 0; i < processArgs.length; i++) {
                            String arg = processArgs[i];
                            if ("--source".equals(arg)) {
                                i++;
                                continue;
                            }
                            if (arg.startsWith("--source=") || arg.startsWith("-")) {
                                continue;
                            }
                            return Path.of(arg).toAbsolutePath().normalize();
                        }
                        String command = System.getProperty("sun.java.command", "");
                        int end = command.indexOf(' ');
                        String path = end < 0 ? command : command.substring(0, end);
                        if (!path.isBlank()) {
                            return Path.of(path).toAbsolutePath().normalize();
                        }
                        throw new IOException("Unable to locate swim launcher source file");
                    }

                    private static Path swimHome(Path launcher) {
                        Path parent = launcher == null ? null : launcher.getParent();
                        Path candidate = parent == null ? null : parent.getParent();
                        while (candidate != null) {
                            if (isSwimHome(candidate)) {
                                return candidate.toAbsolutePath().normalize();
                            }
                            candidate = candidate.getParent();
                        }
                        Path cwdRoot = findBuildRoot(Path.of(System.getProperty("user.dir")));
                        return cwdRoot == null ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize() : cwdRoot;
                    }

                    private static boolean isSwimHome(Path path) {
                        return path != null
                                && ((Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("swim-core")))
                                        || (Files.isDirectory(path.resolve("image")) && Files.isDirectory(path.resolve("plugins"))));
                    }

                    private static Path findBuildRoot(Path start) {
                        Path path = start.toAbsolutePath().normalize();
                        while (path != null) {
                            if (Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("swim-core"))) {
                                return path;
                            }
                            path = path.getParent();
                        }
                        return null;
                    }

                    private static ControlCommand controlCommand(String[] args) throws IOException {
                        if (args == null || args.length == 0) {
                            return null;
                        }
                        if (!"--kill-session".equals(args[0]) && !"--swim-kill-session".equals(args[0])) {
                            return null;
                        }
                        if (args.length > 2) {
                            throw new IOException("usage: swim --kill-session [name]");
                        }
                        return new ControlCommand("kill", args.length == 2 ? args[1] : sessionName());
                    }

                    private static void runControlCommand(Path socket, ControlCommand command) throws IOException {
                        if ("kill".equals(command.name())) {
                            killSession(socket, command.target());
                            return;
                        }
                        throw new IOException("unknown control command: " + command.name());
                    }

                    private static void killSession(Path socket, String target) throws IOException {
                        String message = killSessionRequest(socket, target);
                        System.out.println(message);
                    }

                    private static void killSessionQuietly(Path socket, String target) {
                        try {
                            killSessionRequest(socket, target);
                        } catch (IOException | RuntimeException e) {
                        }
                    }

                    private static String killSessionRequest(Path socket, String target) throws IOException {
                        try (SocketChannel channel = connect(socket)) {
                            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                            output.writeUTF(MAGIC);
                            output.writeUTF("kill");
                            output.writeUTF(normalizeName(target));
                            output.flush();
                            DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
                            String response = input.readUTF();
                            if ("OK".equals(response)) {
                                return input.readUTF();
                            }
                            if ("ERR".equals(response)) {
                                throw new IOException(input.readUTF());
                            }
                            throw new IOException("Unexpected SWIM session server response: " + response);
                        }
                    }

                    private static void ensureServer(Path socket, Path swimHome, Path java) throws IOException {
                        if (ping(socket)) {
                            return;
                        }
                        Files.createDirectories(socket.getParent());
                        ProcessBuilder builder = new ProcessBuilder(serverCommand(java, socket, swimHome));
                        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
                        Path logFile = socket.getParent().resolve("server.log");
                        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                        builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                        builder.start();
                        long deadline = System.nanoTime() + SERVER_START_TIMEOUT.toNanos();
                        while (System.nanoTime() < deadline) {
                            if (ping(socket)) {
                                return;
                            }
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("interrupted while waiting for session server", e);
                            }
                        }
                        throw new IOException("session server did not start; see " + logFile);
                    }

                    private static boolean ping(Path socket) {
                        try (SocketChannel channel = connect(socket)) {
                            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                            output.writeUTF(MAGIC);
                            output.writeUTF("ping");
                            output.flush();
                            return "OK".equals(new DataInputStream(Channels.newInputStream(channel)).readUTF());
                        } catch (IOException e) {
                            return false;
                        }
                    }

                    private static SocketChannel connect(Path socket) throws IOException {
                        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                        channel.connect(UnixDomainSocketAddress.of(socket));
                        return channel;
                    }

                    private static TerminalSize sendAttach(SocketChannel channel, Path java, LaunchRequest request) throws IOException {
                        TerminalSize terminalSize = TerminalMode.currentSize();
                        DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                        output.writeUTF(MAGIC);
                        output.writeUTF("attach");
                        output.writeUTF(request.sessionName());
                        output.writeUTF(clientWorkingDirectory().toString());
                        writeStringMap(output, clientEnvironment());
                        output.writeInt(terminalSize.rows());
                        output.writeInt(terminalSize.columns());
                        writeStringList(output, request.launchArgs());
                        writeStringList(output, appCommand(java, request.launchArgs()));
                        output.flush();
                        String response = new DataInputStream(Channels.newInputStream(channel)).readUTF();
                        if (!"OK".equals(response)) {
                            throw new IOException(response);
                        }
                        return terminalSize;
                    }

                    private static void relayResize(Path socket, String session, TerminalSize initialSize) {
                        TerminalSize previous = initialSize;
                        while (!Thread.currentThread().isInterrupted()) {
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            TerminalSize current = TerminalMode.currentSize();
                            if (current.equals(previous)) {
                                continue;
                            }
                            try {
                                resizeSession(socket, session, current);
                                previous = current;
                            } catch (IOException e) {
                                return;
                            }
                        }
                    }

                    private static void resizeSession(Path socket, String session, TerminalSize size) throws IOException {
                        try (SocketChannel channel = connect(socket)) {
                            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                            output.writeUTF(MAGIC);
                            output.writeUTF("resize");
                            output.writeUTF(normalizeName(session));
                            output.writeInt(size.rows());
                            output.writeInt(size.columns());
                            output.flush();
                            DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
                            String response = input.readUTF();
                            if ("OK".equals(response)) {
                                return;
                            }
                            if ("ERR".equals(response)) {
                                throw new IOException(input.readUTF());
                            }
                            throw new IOException("Unexpected SWIM session server response: " + response);
                        }
                    }

                    private static List<String> serverCommand(Path java, Path socket, Path swimHome) {
                        var command = new ArrayList<String>();
                        command.add(java.toString());
                        command.addAll(SERVER_JVM_OPTIONS);
                        command.add("-m");
                        command.add(SERVER_MODULE);
                        command.add(socket.toString());
                        command.add(swimHome.toString());
                        return List.copyOf(command);
                    }

                    private static List<String> appCommand(Path java, List<String> launchArgs) {
                        var command = new ArrayList<String>();
                        command.add(java.toString());
                        command.addAll(appJvmOptions());
                        command.add("-m");
                        command.add(APP_MODULE);
                        command.add("--swim-app");
                        command.addAll(launchArgs);
                        return List.copyOf(command);
                    }

                    private static List<String> appJvmOptions() {
                        var options = new ArrayList<String>();
                        options.addAll(APP_JVM_OPTIONS);
                        if (Runtime.version().feature() >= 26) {
                            options.add(FINAL_FIELD_MUTATION_OPTION);
                        }
                        return List.copyOf(options);
                    }

                    private static Path clientWorkingDirectory() {
                        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                    }

                    private static Map<String, String> clientEnvironment() {
                        return new TreeMap<>(System.getenv());
                    }

                    private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
                        output.writeInt(values.size());
                        for (String value : values) {
                            output.writeUTF(value);
                        }
                    }

                    private static void writeStringMap(DataOutputStream output, Map<String, String> values) throws IOException {
                        output.writeInt(values.size());
                        for (var entry : values.entrySet()) {
                            output.writeUTF(entry.getKey());
                            output.writeUTF(entry.getValue());
                        }
                    }

                    private static void copy(InputStream input, OutputStream output) throws IOException {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            output.flush();
                        }
                    }

                    private static Path defaultSocketPath() {
                        String property = System.getProperty("swim.server.socket");
                        if (property != null && !property.isBlank()) {
                            return Path.of(property);
                        }
                        String environment = System.getenv("SWIM_SERVER_SOCKET");
                        if (environment != null && !environment.isBlank()) {
                            return Path.of(environment);
                        }
                        String user = System.getProperty("user.name", "unknown").replaceAll("[^A-Za-z0-9_.-]", "_");
                        return Path.of("/tmp", "swim-" + user, "default.sock");
                    }

                    private static String sessionName() {
                        String session = System.getenv("SWIM_SESSION");
                        return normalizeName(session);
                    }

                    private static String normalizeName(String session) {
                        return session == null || session.isBlank()
                                ? "default"
                                : session.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
                    }

                    private record TerminalSize(int rows, int columns) {
                    }

                    private static final class SessionGuard {
                        private final Path socket;
                        private final String session;
                        private final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
                        private final Thread shutdownHook;

                        private SessionGuard(Path socket, String session) {
                            this.socket = socket;
                            this.session = normalizeName(session);
                            shutdownHook = new Thread(this::shutdownAbruptly, "swim-client-session-shutdown");
                            Runtime.getRuntime().addShutdownHook(shutdownHook);
                        }

                        private void close(boolean gracefulExit) {
                            if (gracefulExit) {
                                armed.set(false);
                            } else {
                                shutdownAbruptly();
                            }
                            try {
                                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                            } catch (IllegalStateException e) {
                            }
                            TerminalMode.restoreTerminal();
                        }

                        private void shutdownAbruptly() {
                            TerminalMode.restoreTerminal();
                            if (!armed.getAndSet(false)) {
                                return;
                            }
                            killSessionQuietly(socket, session);
                        }
                    }

                    private static final class TerminalMode implements AutoCloseable {
                        private static final String RESTORE_TERMINAL = "\\u001b[?1006l\\u001b[?2004l\\u001b[?25h\\u001b[?1049l\\u001b[0m\\u001b[0 q";
                        private final String state;
                        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                        private final Thread shutdownHook;

                        private TerminalMode(String state) {
                            this.state = state;
                            shutdownHook = new Thread(this::restore, "swim-terminal-restore");
                            Runtime.getRuntime().addShutdownHook(shutdownHook);
                        }

                        static TerminalMode enterRawMode() {
                            String state = runStty("-g");
                            if (state == null || state.isBlank()) {
                                return new TerminalMode(null);
                            }
                            runStty("raw", "-echo");
                            return new TerminalMode(state);
                        }

                        static TerminalSize currentSize() {
                            Integer rows = parsePositiveInt(System.getenv("SWIM_TTY_ROWS"));
                            Integer columns = parsePositiveInt(System.getenv("SWIM_TTY_COLS"));
                            if (rows != null && columns != null) {
                                return new TerminalSize(rows, columns);
                            }
                            String output = runStty("size");
                            if (output == null) {
                                return new TerminalSize(24, 80);
                            }
                            String[] parts = output.trim().split("\\\\s+");
                            if (parts.length != 2) {
                                return new TerminalSize(24, 80);
                            }
                            rows = parsePositiveInt(parts[0]);
                            columns = parsePositiveInt(parts[1]);
                            return rows == null || columns == null ? new TerminalSize(24, 80) : new TerminalSize(rows, columns);
                        }

                        @Override
                        public void close() {
                            restore();
                            try {
                                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                            } catch (IllegalStateException e) {
                            }
                        }

                        private void restore() {
                            if (!closed.compareAndSet(false, true)) {
                                return;
                            }
                            restoreTerminal();
                            if (state != null && !state.isBlank()) {
                                runStty(state);
                            }
                        }

                        private static void restoreTerminal() {
                            try {
                                System.out.print(RESTORE_TERMINAL);
                                System.out.flush();
                            } catch (RuntimeException e) {
                            }
                        }

                        private static Integer parsePositiveInt(String value) {
                            if (value == null || value.isBlank()) {
                                return null;
                            }
                            try {
                                int parsed = Integer.parseInt(value.trim());
                                return parsed > 0 ? parsed : null;
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        }

                        private static String runStty(String... args) {
                            StringBuilder command = new StringBuilder("stty");
                            for (String arg : args) {
                                command.append(' ').append(shellQuote(arg));
                            }
                            command.append(" < /dev/tty");
                            ProcessBuilder builder = new ProcessBuilder(List.of("/bin/sh", "-c", command.toString()))
                                    .redirectErrorStream(true);
                            try {
                                Process process = builder.start();
                                String output;
                                try (InputStream input = process.getInputStream()) {
                                    output = new String(input.readAllBytes());
                                }
                                if (process.waitFor() != 0) {
                                    return null;
                                }
                                return output.trim();
                            } catch (IOException e) {
                                return null;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }

                        private static String shellQuote(String value) {
                            return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
                        }
                    }
                }
                """, embeddedJava, clientOptions, finalFieldMutationOption, appOptions, serverOptions,
                javaStringLiteral(embeddedJava.toString()));
    }

    private static void runTool(String name, List<String> args) throws IOException {
        ToolProvider tool = ToolProvider.findFirst(name)
                .orElseThrow(() -> new IOException("JDK tool not available: " + name));
        var stdout = new StringWriter();
        var stderr = new StringWriter();
        int exit = tool.run(new PrintWriter(stdout, true), new PrintWriter(stderr, true), args.toArray(String[]::new));
        if (exit != 0) {
            throw new IOException(name + " failed with exit code " + exit + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
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
}
