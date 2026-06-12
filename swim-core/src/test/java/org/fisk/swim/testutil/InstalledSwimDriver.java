package org.fisk.swim.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.fisk.swim.launcher.Main;
import org.fisk.swim.session.SwimServerSessions;
import org.junit.jupiter.api.Assumptions;

public final class InstalledSwimDriver {
    private static final Path TEST_SOCKET_PATH = Path.of("/tmp",
            "swim-it-" + safePathToken(System.getProperty("user.name", "unknown"))
                    + "-" + ProcessHandle.current().pid(),
            "default.sock");

    private InstalledSwimDriver() {
    }

    public static Path buildRoot() {
        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for tmux integration test");
        return buildRoot;
    }

    public static Path launcherBinary() {
        Path launcherBinary = buildRoot().resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");
        return launcherBinary;
    }

    public static Path pluginsDirectory() {
        return buildRoot().resolve("plugins");
    }

    public static void assumeTmuxAvailable() {
        Assumptions.assumeTrue(tmuxAvailable(), "tmux is required for tmux-driven editor integration tests");
    }

    public static void assumePluginAvailable(String pluginJarName) {
        Assumptions.assumeTrue(Files.isRegularFile(pluginsDirectory().resolve(pluginJarName)),
                "Required plugin artifact missing: " + pluginJarName);
    }

    public static TmuxSession start(Path scratchRoot, Path workdir, String... arguments) throws Exception {
        return startWithHome(createHome(scratchRoot), workdir, Map.of(), arguments);
    }

    public static TmuxSession start(Path scratchRoot, Path workdir, Map<String, String> extraEnvironment, String... arguments)
            throws Exception {
        return startWithHome(createHome(scratchRoot), workdir, extraEnvironment, arguments);
    }

    public static Path createHome(Path scratchRoot) throws IOException {
        Path home = scratchRoot.resolve("home-" + System.nanoTime());
        Files.createDirectories(home.resolve(".swim"));
        return home;
    }

    public static TmuxSession startWithHome(Path home, Path workdir, String... arguments) throws Exception {
        return startWithHome(home, workdir, Map.of(), arguments);
    }

    public static TmuxSession startWithHome(Path home, Path workdir, Map<String, String> extraEnvironment, String... arguments)
            throws Exception {
        assumeTmuxAvailable();
        Path launcherBinary = launcherBinary();
        Files.createDirectories(home.resolve(".swim"));
        var environment = new LinkedHashMap<String, String>();
        environment.put("HOME", home.toString());
        Path socketPath = TEST_SOCKET_PATH;
        environment.put(SwimServerSessions.ENV_SOCKET, socketPath.toString());
        environment.put(SwimServerSessions.ENV_SESSION, "it-" + Long.toUnsignedString(System.nanoTime(), 36));
        String currentPath = System.getenv("PATH");
        if (currentPath != null && !currentPath.isBlank()) {
            environment.put("PATH", currentPath);
        }
        String javaToolOptions = "-Duser.home=" + home
                + " -Dswim.log.level=debug"
                + " -D" + SwimServerSessions.PROPERTY_SOCKET + "=" + socketPath;
        String existingJavaToolOptions = System.getenv("JAVA_TOOL_OPTIONS");
        if (existingJavaToolOptions != null && !existingJavaToolOptions.isBlank()) {
            javaToolOptions = existingJavaToolOptions + " " + javaToolOptions;
        }
        String extraJavaToolOptions = extraEnvironment.get("JAVA_TOOL_OPTIONS");
        if (extraJavaToolOptions != null && !extraJavaToolOptions.isBlank()) {
            javaToolOptions = javaToolOptions + " " + extraJavaToolOptions;
        }
        environment.put("JAVA_TOOL_OPTIONS", javaToolOptions);
        for (var entry : extraEnvironment.entrySet()) {
            if (!"JAVA_TOOL_OPTIONS".equals(entry.getKey())) {
                environment.put(entry.getKey(), entry.getValue());
            }
        }

        String[] command = new String[arguments.length + 1];
        command[0] = launcherBinary.toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return TmuxSession.start(workdir, environment, command);
    }

    private static boolean tmuxAvailable() {
        try {
            var process = new ProcessBuilder("tmux", "-V")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String safePathToken(String value) {
        return (value == null || value.isBlank() ? "unknown" : value)
                .replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
