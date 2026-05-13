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
        patchLauncherScript(imageRoot.resolve("bin").resolve(APP_NAME), resolveNetBeansJvmArgs(swimHome));
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
        args.add("--add-options=-XX:+UseZGC");
        for (String option : launcherOptions) {
            args.add("--add-options=" + option);
        }
        args.add("--strip-debug");
        args.add("--no-header-files");
        args.add("--no-man-pages");
        args.add("--output");
        args.add(output.toString());
        runTool("jlink", args);
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
        String osName = System.getProperty("os.name").toLowerCase();
        String executable = osName.contains("win") ? "java.exe" : "java";
        Path source = javaHomeBin().resolve(executable);
        Path target = imageRoot.resolve("bin").resolve(executable);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        target.toFile().setExecutable(true, false);
    }

    static void patchLauncherScript(Path launcherScript, List<String> launcherOptions) throws IOException {
        if (!Files.isRegularFile(launcherScript)) {
            return;
        }
        String options = "-XX:+UseZGC -Xmx1g";
        if (!launcherOptions.isEmpty()) {
            options += " " + String.join(" ", launcherOptions);
        }
        String script = """
                #!/bin/sh
                JLINK_VM_OPTIONS=%s
                DIR=`dirname $0`
                if [ -d /tmp ]; then
                    LOG_DIR=/tmp
                else
                    LOG_DIR=${TMPDIR:-.}
                fi
                LOG_FILE="$LOG_DIR/swim-$$.log"
                exec 2>>"$LOG_FILE"
                TTY_PATH=$(tty 2>&1)
                TTY_SIZE=$(stty size 2>&1)
                case "$TTY_SIZE" in
                    [0-9]*" "[0-9]*)
                        SWIM_TTY_ROWS=${TTY_SIZE%% *}
                        SWIM_TTY_COLS=${TTY_SIZE##* }
                        SWIM_TTY_PATH=$TTY_PATH
                        export SWIM_TTY_ROWS SWIM_TTY_COLS SWIM_TTY_PATH
                        ;;
                esac
                exec "$DIR/java" $JLINK_VM_OPTIONS -m org.fisk.swim.launcher/org.fisk.swim.launcher.Main "$@"
                """.formatted(shellQuote(options));
        Files.writeString(launcherScript, script, StandardCharsets.UTF_8);
        launcherScript.toFile().setExecutable(true, false);
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
