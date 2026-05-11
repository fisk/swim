package org.fisk.swim.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LauncherScriptInstaller {
    private static final Pattern DEFAULT_OPTIONS_PATTERN =
            Pattern.compile("(?m)^default_options=\"(.*)\"$");

    private LauncherScriptInstaller() {
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
        Path script = swimHome.resolve("bin").resolve("swim");
        Files.createDirectories(script.getParent());
        Files.writeString(script, scriptContents(swimHome), StandardCharsets.UTF_8);
        makeExecutable(script);
    }

    static String scriptContents(Path swimHome) throws IOException {
        String nbJavaArgs = String.join(" ", resolveNetBeansJvmArgs(swimHome));
        return """
                #!/bin/sh
                set -eu

                SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
                SWIM_HOME=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
                LAUNCHER_JAR=$(ls "$SWIM_HOME"/bin/launcher/swim-launcher-*.jar 2>/dev/null | head -n 1 || true)

                if [ -z "${LAUNCHER_JAR}" ]; then
                    echo "swim: launcher jar not found under $SWIM_HOME/bin/launcher" >&2
                    exit 1
                fi

                if [ -n "${JAVA_HOME:-}" ]; then
                    JAVA_BIN="$JAVA_HOME/bin/java"
                else
                    JAVA_BIN=java
                fi

                if [ -d /tmp ]; then
                    LOG_DIR=/tmp
                else
                    LOG_DIR=${TMPDIR:-.}
                fi
                LOG_FILE="$LOG_DIR/swim-$$.log"
                exec 2>>"$LOG_FILE"

                NB_JAVA_ARGS='%s'

                EXTRA_JAVA_ARGS=${SWIM_JAVA_ARGS:-}
                eval "exec \\"$JAVA_BIN\\" -XX:+UseZGC $NB_JAVA_ARGS $EXTRA_JAVA_ARGS --module-path \\"$LAUNCHER_JAR\\" -m org.fisk.swim.launcher/org.fisk.swim.launcher.Main \\"$@\\""
                """.formatted(nbJavaArgs);
    }

    private static List<String> resolveNetBeansJvmArgs(Path swimHome) throws IOException {
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
                            args.add(shellQuote(jvmArg));
                        }
                    }
                }
            }
        }
        if (ModuleLayer.boot().findModule("java.instrument").isPresent()) {
            args.add(shellQuote("--add-modules=java.instrument"));
        }
        return args;
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void makeExecutable(Path script) throws IOException {
        try {
            Files.setPosixFilePermissions(script, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException e) {
            if (!script.toFile().setExecutable(true, false)) {
                throw new IOException("Unable to mark launcher script executable: " + script);
            }
        }
    }
}
