package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.session.SwimServerSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path tempDir;

    @Test
    void findsBuildRootFromNestedLauncherPath() throws IOException {
        Path root = tempDir.resolve("swim");
        Path nested = root.resolve("target").resolve("swim-0.0.1-SNAPSHOT.jar");
        Files.createDirectories(root.resolve("swim-core"));
        Files.createDirectories(nested.getParent());
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.writeString(nested, "jar");

        assertEquals(root, Main.findBuildRoot(nested));
    }

    @Test
    void returnsNullWhenNoBuildRootMarkersExist() throws IOException {
        Path path = tempDir.resolve("elsewhere").resolve("target").resolve("swim.jar");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "jar");

        assertNull(Main.findBuildRoot(path));
    }

    @Test
    void coreModulePathKeepsRuntimeDependenciesEvenWhenJarNameLooksShared() throws Exception {
        Path root = createSyntheticBuildRoot();

        List<Path> modulePath = Main.getCoreModulePath(root);

        assertTrue(modulePath.stream().anyMatch(path -> path.getFileName().toString().equals("swim-core-0.0.1-SNAPSHOT.jar")));
        assertTrue(modulePath.stream().anyMatch(path -> path.getFileName().toString().equals("lanterna-9.9.9.jar")));
        assertFalse(modulePath.stream().anyMatch(path -> path.getFileName().toString().startsWith("swim-launcher-")));
        assertFalse(modulePath.stream().anyMatch(path -> path.getFileName().toString().startsWith("swim-session-")));
    }

    @Test
    void coreModulePathPrefersInstalledBinArtifactsWhenPresent() throws Exception {
        Path root = tempDir.resolve("swim");
        Path target = root.resolve("swim-core").resolve("target");
        Path installedCore = root.resolve("plugins");
        Files.createDirectories(target.resolve("runtime-libs"));
        Files.createDirectories(installedCore.resolve("runtime-libs"));
        Files.writeString(target.resolve("swim-core-0.0.1-SNAPSHOT.jar"), "target");
        Files.writeString(installedCore.resolve("swim-core-0.0.2-SNAPSHOT.jar"), "bin");
        Files.writeString(installedCore.resolve("runtime-libs").resolve("lanterna-9.9.9.jar"), "lib");

        List<Path> modulePath = Main.getCoreModulePath(root);

        assertEquals(installedCore.resolve("swim-core-0.0.2-SNAPSHOT.jar"), modulePath.get(0));
        assertTrue(modulePath.contains(installedCore.resolve("runtime-libs").resolve("lanterna-9.9.9.jar")));
    }

    @Test
    void launcherCanLoadCoreLayerWithRuntimeDependencyJar() throws Exception {
        Path root = createSyntheticBuildRoot();

        ModuleLayer layer = Main.createCoreLayer(root, Main.class.getClassLoader());
        SwimApp app = ServiceLoader.load(layer, SwimApp.class).findFirst().orElseThrow();

        assertEquals("fake.core.FakeApp", app.getClass().getName());
        app.close();
    }

    @Test
    void launcherCanLoadCoreLayerWhenCoreRequiresSystemModuleOutsideBootGraph() throws Exception {
        Path root = createSyntheticBuildRootRequiringSqlRowset();

        ModuleLayer layer = Main.createCoreLayer(root, Main.class.getClassLoader());
        SwimApp app = ServiceLoader.load(layer, SwimApp.class).findFirst().orElseThrow();

        assertEquals("fake.core.RowsetApp", app.getClass().getName());
        app.close();
    }

    @Test
    void sharedLibFilterExcludesBootLayerSwimArtifacts() {
        assertTrue(Main.isSharedLib(Path.of("swim-launcher-0.0.1-SNAPSHOT.jar")));
        assertTrue(Main.isSharedLib(Path.of("swim-session-0.0.1-SNAPSHOT.jar")));
        assertFalse(Main.isSharedLib(Path.of("lanterna-3.1.3.jar")));
    }

    @Test
    void findsNewestCoreJarInTargetDirectory() throws IOException {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Path older = target.resolve("swim-core-0.0.1-SNAPSHOT.jar");
        Path newer = target.resolve("swim-core-0.0.2-SNAPSHOT.jar");
        Files.writeString(older, "old");
        Files.writeString(newer, "new");

        assertEquals(newer, Main.findCoreJar(target));
    }

    @Test
    void findsNewestCoreJarUsingNumericVersionOrderingAndIgnoresTestJars() throws IOException {
        Path target = tempDir.resolve("versioned-target");
        Files.createDirectories(target);
        Path older = target.resolve("swim-core-0.0.9.jar");
        Path newer = target.resolve("swim-core-0.0.10.jar");
        Path tests = target.resolve("swim-core-9.9.9-tests.jar");
        Files.writeString(older, "old");
        Files.writeString(newer, "new");
        Files.writeString(tests, "tests");

        assertEquals(newer, Main.findCoreJar(target));
    }

    @Test
    void launcherLocationResolvesToExistingArtifactOrClassesDirectory() {
        assertNotNull(Main.getLauncherLocation());
        assertTrue(Files.exists(Main.getLauncherLocation()));
    }

    @Test
    void launcherImageInstallerFiltersUnsupportedNetBeansFlags() throws Exception {
        Path nbcodeConf = tempDir.resolve("deps").resolve("oracle.oracle-java").resolve("nbcode").resolve("etc").resolve("nbcode.conf");
        Files.createDirectories(nbcodeConf.getParent());
        Files.writeString(nbcodeConf, """
                default_options="-J--add-exports=jdk.jdeps/com.sun.tools.classfile=ALL-UNNAMED -J--add-opens=java.base/java.net=ALL-UNNAMED -J-Djava.awt.headless=true"
                """);

        List<String> args = LauncherImageInstaller.resolveNetBeansJvmArgs(tempDir);

        assertTrue(args.contains("--add-opens=java.base/java.net=ALL-UNNAMED"));
        assertTrue(args.contains("-Djava.awt.headless=true"));
        assertFalse(args.contains("--add-exports=jdk.jdeps/com.sun.tools.classfile=ALL-UNNAMED"));
    }

    @Test
    void launcherImageInstallerAddsModulesReferencedOnlyByJvmOptions() {
        List<String> modules = LauncherImageInstaller.collectJlinkModules(List.of(
                "--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED",
                "--add-exports=jdk.jdeps/com.sun.tools.javap=ALL-UNNAMED",
                "--add-modules=java.instrument"));

        assertTrue(modules.contains("org.fisk.swim.launcher"));
        assertTrue(modules.contains("org.fisk.swim.session"));
        assertTrue(modules.contains("jdk.jshell"));
        assertTrue(modules.contains("jdk.jdeps"));
        assertTrue(modules.contains("java.instrument"));
    }

    @Test
    void sessionServerCommandUsesDedicatedJvmPolicy() {
        List<String> command = SwimJavaCommand.serverCommand(Path.of("server.sock"), Path.of("swim-root"));

        assertTrue(command.contains("-XX:+UseZGC"));
        assertTrue(command.contains("-Xmx4G"));
        assertTrue(command.contains("-XX:SoftMaxHeapSize=1G"));
        assertTrue(command.contains("--enable-native-access=org.fisk.swim.session"));
        assertTrue(command.contains("org.fisk.swim.session/org.fisk.swim.session.server.SwimSessionServerMain"));
        assertFalse(command.contains("--swim-server"));
    }

    @Test
    void launcherImageInstallerCopiesJavaLauncherIntoImage() throws Exception {
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";

        LauncherImageInstaller.installJavaLauncher(tempDir);

        assertTrue(Files.isRegularFile(bin.resolve(executable)));
        assertTrue(Files.isExecutable(bin.resolve(executable)));
    }

    @Test
    void launcherImageInstallerInstallsSourceLauncher() throws Exception {
        Path launcher = tempDir.resolve("bin").resolve("swim");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "#!/bin/sh\nJLINK_VM_OPTIONS=\nDIR=`dirname $0`\n$DIR/java $JLINK_VM_OPTIONS -m org.fisk.swim.launcher/org.fisk.swim.launcher.Main \"$@\"\n");

        LauncherImageInstaller.installSourceLauncher(launcher, List.of(
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "-Djava.awt.headless=true"));

        String content = Files.readString(launcher);
        Path embeddedJava = launcher.getParent().resolve("java").toAbsolutePath().normalize();
        assertTrue(content.startsWith("#!" + embeddedJava + " --source 25"));
        assertFalse(content.startsWith("#!/usr/bin/env -S java"));
        assertTrue(content.contains("class swim"));
        assertTrue(content.contains("private static final List<String> APP_JVM_OPTIONS = List.of(\"-XX:+UseZGC\", \"-Xmx1g\", \"--add-opens=java.base/java.net=ALL-UNNAMED\", \"-Djava.awt.headless=true\")"));
        assertTrue(content.contains("private static final List<String> SERVER_JVM_OPTIONS = List.of(\"-XX:+UseZGC\", \"-Xmx4G\", \"-XX:SoftMaxHeapSize=1G\", \"--enable-native-access=org.fisk.swim.session\")"));
        assertTrue(content.contains("private static final String MAGIC = \"SWIM_SESSION_6\""));
        assertTrue(content.contains("clientWorkingDirectory()"));
        assertTrue(content.contains("clientEnvironment()"));
        assertTrue(content.contains("relayResize(socket, request.sessionName(), terminalSize)"));
        assertTrue(content.contains("output.writeUTF(\"resize\")"));
        assertTrue(content.contains("redirectInput(ProcessBuilder.Redirect.from(Path.of(\"/dev/null\").toFile()))"));
        assertFalse(content.contains("redirectInput(ProcessBuilder.Redirect.DISCARD)"));
        assertTrue(content.contains("\"--attach\""));
        assertTrue(content.contains("\"--kill-session\""));
        assertTrue(Files.isExecutable(launcher));
    }

    @Test
    void sourceLauncherSelfTestCompiles() throws Exception {
        Path launcher = tempDir.resolve("bin").resolve("swim");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "placeholder");
        LauncherImageInstaller.installSourceLauncher(launcher, List.of());

        String java = Path.of(System.getProperty("java.home")).resolve("bin").resolve("java").toString();
        Process process = new ProcessBuilder(java, "--source", "25", launcher.toString(),
                "--swim-source-client-self-test")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), output);
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void sourceLauncherRunsThroughEmbeddedJavaShebang() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"),
                "source launcher shebang is Unix-only");
        Path launcher = tempDir.resolve("embedded").resolve("bin").resolve("swim");
        Files.createDirectories(launcher.getParent());
        Path embeddedJava = launcher.getParent().resolve("java");
        Path currentJava = Path.of(System.getProperty("java.home")).resolve("bin").resolve("java")
                .toAbsolutePath().normalize();
        try {
            Files.createSymbolicLink(embeddedJava, currentJava);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }
        try {
            Files.writeString(launcher, "placeholder");
            LauncherImageInstaller.installSourceLauncher(launcher, List.of());

            Process process = new ProcessBuilder(launcher.toString(), "--swim-source-client-self-test")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(process.waitFor(10, TimeUnit.SECONDS), output);
            assertEquals(0, process.exitValue(), output);
        } finally {
            Files.deleteIfExists(embeddedJava);
        }
    }

    @Test
    void swimSessionClientAttachModeUsesNamedSessionWithoutLaunchArgs() throws Exception {
        Path socket = tempDir.resolve("attach.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeSessionServer(socket, input -> {
            assertEquals(SwimServerSessions.MAGIC, input.readUTF());
            assertEquals("ping", input.readUTF());
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.flush();
        }, input -> {
            assertEquals(SwimServerSessions.MAGIC, input.readUTF());
            assertEquals("attach", input.readUTF());
            String session = input.readUTF();
            String workingDirectory = input.readUTF();
            int environmentCount = input.readInt();
            var environment = new java.util.HashMap<String, String>();
            for (int i = 0; i < environmentCount; i++) {
                environment.put(input.readUTF(), input.readUTF());
            }
            input.readInt();
            input.readInt();
            int launchArgCount = input.readInt();
            for (int i = 0; i < launchArgCount; i++) {
                input.readUTF();
            }
            int commandArgCount = input.readInt();
            var commandArgs = new ArrayList<String>();
            for (int i = 0; i < commandArgCount; i++) {
                commandArgs.add(input.readUTF());
            }
            request.set(List.of(session, workingDirectory, String.valueOf(environment.containsKey("PATH")),
                    String.valueOf(launchArgCount), commandArgs.getLast()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.flush();
        });

        int exit = new SwimSessionClient(tempDir, socket).run(new String[] { "--attach", "review" });
        server.join(1000);

        assertEquals(0, exit);
        assertEquals(List.of("review", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString(),
                "true", "0", "--swim-app"), request.get());
    }

    @Test
    void swimSessionClientKillModeUsesControlProtocolWithoutStartingServer() throws Exception {
        Path socket = tempDir.resolve("kill.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeSessionServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.writeUTF("Killed SWIM session review.");
            output.flush();
        });

        var outputBuffer = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(outputBuffer, true, StandardCharsets.UTF_8));
            exit = new SwimSessionClient(tempDir, socket).run(new String[] { "--kill-session", "review" });
        } finally {
            System.setOut(previousOut);
        }
        server.join(1000);

        assertEquals(0, exit);
        assertEquals(List.of(SwimServerSessions.MAGIC, "kill", "review"), request.get());
        assertEquals("Killed SWIM session review." + System.lineSeparator(),
                outputBuffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkArgumentsCreatesMissingFileAndReturnsAbsolutePath() throws Exception {
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        Path file = tempDir.resolve("new-file.txt");

        List<Path> result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class },
                (Object) new String[] { file.toString() });

        assertEquals(List.of(file.toAbsolutePath()), result);
        assertTrue(Files.exists(file));
    }

    @Test
    void checkArgumentsAcceptsNoArguments() throws Exception {
        var output = new ByteArrayOutputStream();
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir, new PrintStream(output, true, StandardCharsets.UTF_8));

        List<Path> result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class },
                (Object) new String[0]);

        assertEquals(List.of(), result);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkArgumentsCreatesMultipleMissingFiles() throws Exception {
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");

        List<Path> result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class },
                (Object) new String[] { first.toString(), second.toString() });

        assertEquals(List.of(first.toAbsolutePath(), second.toAbsolutePath()), result);
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void checkArgumentsAcceptsExistingDirectory() throws Exception {
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        Path directory = Files.createDirectories(tempDir.resolve("project-dir"));

        List<Path> result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class },
                (Object) new String[] { directory.toString() });

        assertEquals(List.of(directory.toAbsolutePath()), result);
    }

    @Test
    void determineBuildRootFallsBackToUserDirWhenLauncherLocationMisses() throws Exception {
        Path root = tempDir.resolve("swim");
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir.resolve("missing").resolve("launcher.jar"));
        String previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", root.toString());
        try {
            Path buildRoot = invokePrivate(main, "determineBuildRoot", new Class<?>[0]);

            assertEquals(root, buildRoot);
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    void determineBuildRootFindsRootFromPackagedRuntimeLocation() throws Exception {
        Path root = tempDir.resolve("swim");
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Path runtimeHome = root.resolve("image");
        Files.createDirectories(runtimeHome);
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> runtimeHome);

        Path buildRoot = invokePrivate(main, "determineBuildRoot", new Class<?>[0]);

        assertEquals(root, buildRoot);
    }

    @Test
    void determineBuildRootThrowsWhenLauncherLocationAndUserDirMiss() {
        Main main = new Main(new FakePluginController(), buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir.resolve("missing").resolve("launcher.jar"));
        String previousUserDir = System.getProperty("user.dir");
        Path cwd = tempDir.resolve("cwd");
        try {
            System.setProperty("user.dir", cwd.toString());

            assertThrows(IllegalStateException.class, () -> invokePrivate(main, "determineBuildRoot", new Class<?>[0]));
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    void requestReloadStartsNewAppRefreshesAndClosesPreviousApp() throws Exception {
        FakeApp previous = new FakeApp();
        FakeApp next = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = previous;
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            assertEquals("true", System.getProperty(Main.RELOAD_SESSION_PROPERTY));
            previous.close();
            if (beforeLoad != null) {
                beforeLoad.run();
            }
            next.start(path, host);
            next.refresh(true);
            return next;
        };
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main(plugins, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, taskRunner, () -> tempDir);
        Path path = tempDir.resolve("project.txt");

        main.requestReload(path);

        assertEquals(List.of("swim-reload"), taskRunner.names);
        assertEquals(List.of(false), taskRunner.daemons);
        assertEquals(path, next.startedPath);
        assertSame(main, next.startedHost);
        assertEquals(List.of(true), next.refreshCalls);
        assertEquals(List.of("Reloaded SWIM core"), next.messages);
        assertTrue(previous.checkpointed);
        assertTrue(previous.closed);
        assertSame(next, main.getLoadedApp());
        assertNull(System.getProperty(Main.RELOAD_SESSION_PROPERTY));
    }

    @Test
    void initialLoadDoesNotSetReloadSessionProperty() throws Exception {
        FakeApp next = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            assertNull(System.getProperty(Main.RELOAD_SESSION_PROPERTY));
            assertNull(beforeLoad);
            next.start(path, host);
            return next;
        };
        Main main = new Main(plugins, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        Path path = tempDir.resolve("project.txt");

        invokePrivate(main, "reload", new Class<?>[] { Path.class, String.class }, path, "Loaded SWIM core");

        assertEquals(path, next.startedPath);
        assertEquals(List.of("Loaded SWIM core"), next.messages);
        assertSame(next, main.getLoadedApp());
        assertNull(System.getProperty(Main.RELOAD_SESSION_PROPERTY));
    }

    @Test
    void initialLoadPassesAllLaunchPathsToPluginController() throws Exception {
        FakeApp next = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            next.start(path, host);
            return next;
        };
        Main main = new Main(plugins, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        List<Path> paths = List.of(tempDir.resolve("one.txt"), tempDir.resolve("two.txt"));

        invokePrivate(main, "reload", new Class<?>[] { List.class, String.class }, paths, "Loaded SWIM core");

        assertEquals(paths, plugins.lastReloadPaths);
        assertEquals(paths.getFirst(), next.startedPath);
        assertEquals(List.of("Loaded SWIM core"), next.messages);
    }

    @Test
    void standardInputRefreshOnlyRunsWhenAnAppIsAlreadyLoaded() {
        FakePluginController plugins = new FakePluginController();

        assertFalse(Main.shouldRefreshStandardInput(plugins));

        plugins.currentApp = new FakeApp();

        assertTrue(Main.shouldRefreshStandardInput(plugins));
    }

    @Test
    void requestReloadFailureShowsMessageOnExistingApp() throws Exception {
        FakeApp previous = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = previous;
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            previous.close();
            throw new IllegalStateException("boom");
        };
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main(plugins, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, taskRunner, () -> tempDir);

        main.requestReload(tempDir.resolve("project.txt"));

        assertEquals(List.of("Reload failed"), previous.messages);
        assertTrue(previous.checkpointed);
        assertTrue(previous.closed);
        assertSame(previous, main.getLoadedApp());
    }

    @Test
    void requestRebuildAndReloadReportsBuildFailureWithoutReloading() throws Exception {
        FakeApp previous = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = previous;
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        AtomicReference<Path> rebuildPath = new AtomicReference<>();
        Main main = new Main(plugins, buildRoot -> {
            rebuildPath.set(buildRoot);
            return false;
        }, taskRunner, () -> tempDir);
        setBuildRoot(main, tempDir.resolve("build-root"));

        main.requestRebuildAndReload(tempDir.resolve("project.txt"));

        assertEquals(tempDir.resolve("build-root"), rebuildPath.get());
        assertEquals(List.of("Rebuilding SWIM...", "Build failed"), previous.messages);
        assertFalse(previous.closed);
        assertSame(previous, main.getLoadedApp());
    }

    @Test
    void requestRebuildAndReloadReportsReloadFailureAfterSuccessfulBuild() throws Exception {
        FakeApp previous = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = previous;
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            previous.close();
            throw new IllegalStateException("boom");
        };
        Main main = new Main(plugins, buildRoot -> true, (name, daemon, task) -> task.run(), () -> tempDir);

        main.requestRebuildAndReload(tempDir.resolve("project.txt"));

        assertEquals(List.of("Rebuilding SWIM...", "Reload failed after rebuild"), previous.messages);
        assertTrue(previous.checkpointed);
        assertTrue(previous.closed);
        assertSame(previous, main.getLoadedApp());
    }

    @Test
    void requestRebuildAndReloadReplacesRunningAppAfterSuccessfulBuild() throws Exception {
        FakeApp previous = new FakeApp();
        FakeApp next = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = previous;
        plugins.reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            previous.close();
            if (beforeLoad != null) {
                beforeLoad.run();
            }
            next.start(path, host);
            next.refresh(true);
            return next;
        };
        Main main = new Main(plugins, buildRoot -> true, (name, daemon, task) -> task.run(), () -> tempDir);
        Path path = tempDir.resolve("project.txt");

        main.requestRebuildAndReload(path);

        assertEquals(List.of("Rebuilding SWIM..."), previous.messages);
        assertTrue(previous.checkpointed);
        assertTrue(previous.closed);
        assertEquals(path, next.startedPath);
        assertEquals(List.of(true), next.refreshCalls);
        assertEquals(List.of("Rebuilt and reloaded SWIM"), next.messages);
        assertSame(next, main.getLoadedApp());
    }

    @Test
    void requestExitCountsDownLatchClearsLoadedAppAndClosesInDaemonTask() throws Exception {
        FakeApp app = new FakeApp();
        FakePluginController plugins = new FakePluginController();
        plugins.currentApp = app;
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main(plugins, buildRoot -> false, taskRunner, () -> tempDir);

        main.requestExit();

        assertNull(main.getLoadedApp());
        assertTrue(app.closed);
        assertTrue(plugins.unloadAllCalled);
        assertEquals(List.of("swim-close"), taskRunner.names);
        assertEquals(List.of(true), taskRunner.daemons);
        assertEquals(0L, getExitLatch(main).getCount());
    }

    private static Thread fakeSessionServer(Path socket, ServerHandler... handlers) throws Exception {
        var ready = new CountDownLatch(1);
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                Files.deleteIfExists(socket);
                try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                    server.bind(UnixDomainSocketAddress.of(socket));
                    ready.countDown();
                    for (ServerHandler handler : handlers) {
                        try (var channel = server.accept()) {
                            handler.handle(new ChannelInput(channel,
                                    new DataInputStream(Channels.newInputStream(channel))));
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        return thread;
    }

    @FunctionalInterface
    private interface ServerHandler {
        void handle(ChannelInput input) throws Exception;
    }

    private record ChannelInput(java.nio.channels.SocketChannel channel, DataInputStream input) {
        String readUTF() throws Exception {
            return input.readUTF();
        }

        int readInt() throws Exception {
            return input.readInt();
        }
    }

    private Path createSyntheticBuildRoot() throws Exception {
        Path root = tempDir.resolve("swim");
        Path target = root.resolve("swim-core").resolve("target");
        Path runtimeLibs = target.resolve("runtime-libs");
        Files.createDirectories(runtimeLibs);
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.createDirectories(root.resolve("swim-core"));

        Path compileDir = tempDir.resolve("compile");
        Path helperClasses = compileDir.resolve("helper-classes");
        Path coreClasses = compileDir.resolve("core-classes");
        Files.createDirectories(helperClasses);
        Files.createDirectories(coreClasses);

        compileJava(Map.of(
                "fake/dep/Helper.java", """
                        package fake.dep;
                        public final class Helper {
                            public static String value() {
                                return "ok";
                            }
                        }
                        """
        ), helperClasses, List.of(System.getProperty("java.class.path")));

        Path helperJar = runtimeLibs.resolve("lanterna-9.9.9.jar");
        jarDirectory(helperClasses, helperJar, null, Map.of());
        Files.writeString(runtimeLibs.resolve("swim-session-0.0.1-SNAPSHOT.jar"), "shared");

        compileJava(Map.of(
                "fake/core/FakeApp.java", """
                        package fake.core;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        import fake.dep.Helper;
                        public final class FakeApp implements SwimApp {
                            private String value = Helper.value();
                            public void start(Path path, SwimHost host) {
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return Path.of(value);
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                        }
                        """
        ), coreClasses, List.of(System.getProperty("java.class.path"), helperJar.toString()));

        Path coreJar = target.resolve("swim-core-0.0.1-SNAPSHOT.jar");
        jarDirectory(coreClasses, coreJar, "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.FakeApp\n"));
        return root;
    }

    private Path createSyntheticBuildRootRequiringSqlRowset() throws Exception {
        Path root = tempDir.resolve("swim-rowset");
        Path target = root.resolve("swim-core").resolve("target");
        Path runtimeLibs = target.resolve("runtime-libs");
        Files.createDirectories(runtimeLibs);
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.createDirectories(root.resolve("swim-core"));

        Path compileDir = tempDir.resolve("compile-rowset");
        Path helperClasses = compileDir.resolve("helper-classes");
        Path coreClasses = compileDir.resolve("core-classes");
        Files.createDirectories(helperClasses);
        Files.createDirectories(coreClasses);

        compileJavaModule(Map.of(
                "module-info.java", """
                        module fake.helper {
                            requires java.sql.rowset;
                            exports fake.dep;
                        }
                        """,
                "fake/dep/Helper.java", """
                        package fake.dep;
                        import javax.sql.rowset.CachedRowSet;
                        import javax.sql.rowset.RowSetProvider;
                        public final class Helper {
                            public static String value() {
                                try {
                                    CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
                                    return rowSet == null ? "missing" : "ok";
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), helperClasses, List.of());

        Path helperJar = runtimeLibs.resolve("rowset-helper.jar");
        jarDirectory(helperClasses, helperJar, null, Map.of());

        compileJava(Map.of(
                "fake/core/RowsetApp.java", """
                        package fake.core;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        import fake.dep.Helper;
                        public final class RowsetApp implements SwimApp {
                            private Path path;
                            public void start(Path path, SwimHost host) {
                                this.path = path;
                                if (!"ok".equals(Helper.value())) {
                                    throw new IllegalStateException("rowset helper unavailable");
                                }
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return path;
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                        }
                        """
        ), coreClasses, List.of(System.getProperty("java.class.path"), helperJar.toString()));

        Path coreJar = target.resolve("swim-core-0.0.1-SNAPSHOT.jar");
        jarDirectory(coreClasses, coreJar, "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.RowsetApp\n"));
        return root;
    }

    private static void compileJava(Map<String, String> sources, Path classesDir, List<String> classpathEntries) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available");
        }
        Path sourcesDir = classesDir.getParent().resolve(classesDir.getFileName().toString() + "-src");
        Files.createDirectories(sourcesDir);
        for (var entry : sources.entrySet()) {
            Path file = sourcesDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sourceFiles = Files.walk(sourcesDir)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
            var units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            String classpath = String.join(System.getProperty("path.separator"), classpathEntries);
            var task = compiler.getTask(null, fileManager, null,
                    List.of("-d", classesDir.toString(), "-classpath", classpath),
                    null, units);
            if (!task.call()) {
                throw new IllegalStateException("Compilation failed for synthetic launcher test sources");
            }
        }
    }

    private static void compileJavaModule(Map<String, String> sources, Path classesDir, List<String> modulePathEntries) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available");
        }
        Path sourcesDir = classesDir.getParent().resolve(classesDir.getFileName().toString() + "-src");
        Files.createDirectories(sourcesDir);
        for (var entry : sources.entrySet()) {
            Path file = sourcesDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sourceFiles = Files.walk(sourcesDir)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
            var units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            String modulePath = String.join(System.getProperty("path.separator"), modulePathEntries);
            var task = compiler.getTask(null, fileManager, null,
                    List.of("-d", classesDir.toString(), "--module-path", modulePath),
                    null, units);
            if (!task.call()) {
                throw new IllegalStateException("Compilation failed for synthetic launcher module sources");
            }
        }
    }

    private static void jarDirectory(Path classesDir, Path jarFile, String automaticModuleName, Map<String, String> extraFiles) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticModuleName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        }
        Files.createDirectories(jarFile.getParent());
        try (OutputStream output = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> addJarEntry(jar, classesDir, path));
            for (var entry : extraFiles.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                try {
                    jar.putNextEntry(jarEntry);
                    jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void addJarEntry(JarOutputStream jar, Path root, Path file) {
        try {
            String name = root.relativize(file).toString().replace('\\', '/');
            jar.putNextEntry(new JarEntry(name));
            jar.write(Files.readAllBytes(file));
            jar.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static void setBuildRoot(Main main, Path buildRoot) throws Exception {
        Field field = Main.class.getDeclaredField("_buildRoot");
        field.setAccessible(true);
        field.set(main, buildRoot);
    }

    private static CountDownLatch getExitLatch(Main main) throws Exception {
        Field field = Main.class.getDeclaredField("_exitLatch");
        field.setAccessible(true);
        return (CountDownLatch) field.get(main);
    }

    private static final class RecordingTaskRunner implements Main.TaskRunner {
        private final List<String> names = new ArrayList<>();
        private final List<Boolean> daemons = new ArrayList<>();

        @Override
        public void start(String name, boolean daemon, Runnable task) {
            names.add(name);
            daemons.add(daemon);
            task.run();
        }
    }

    private static final class FakePluginController implements Main.PluginController {
        private ReloadAction reloadAction = (buildRoot, path, host, parentLoader, beforeLoad) -> {
            throw new AssertionError("reload not expected");
        };
        private SwimApp currentApp;
        private boolean unloadAllCalled;
        private List<Path> lastReloadPaths;

        @Override
        public SwimApp reload(Path buildRoot, List<Path> paths, SwimHost host, ClassLoader parentLoader, Runnable beforeLoad) {
            lastReloadPaths = paths == null ? List.of() : List.copyOf(paths);
            Path path = lastReloadPaths.isEmpty() ? null : lastReloadPaths.getFirst();
            SwimApp reloaded = reloadAction.reload(buildRoot, path, host, parentLoader, beforeLoad);
            currentApp = reloaded;
            return reloaded;
        }

        @Override
        public SwimApp currentApp() {
            return currentApp;
        }

        @Override
        public void loadPlugin(String id, Path path, SwimHost host) {
        }

        @Override
        public List<String> loadedPluginIds() {
            return currentApp == null ? List.of() : List.of("core");
        }

        @Override
        public void unloadAll() {
            unloadAllCalled = true;
            SwimApp app = currentApp;
            currentApp = null;
            if (app != null) {
                app.close();
            }
        }
    }

    @FunctionalInterface
    private interface ReloadAction {
        SwimApp reload(Path buildRoot, Path path, SwimHost host, ClassLoader parentLoader, Runnable beforeLoad);
    }

    private static final class FakeApp implements SwimApp {
        private Path startedPath;
        private SwimHost startedHost;
        private final List<Boolean> refreshCalls = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private boolean closed;
        private boolean checkpointed;

        @Override
        public void start(Path path, SwimHost host) {
            startedPath = path;
            startedHost = host;
        }

        @Override
        public void refresh(boolean forced) {
            refreshCalls.add(forced);
        }

        @Override
        public Path getCurrentPath() {
            return startedPath;
        }

        @Override
        public void showMessage(String message) {
            messages.add(message);
        }

        @Override
        public void checkpointForReload() {
            checkpointed = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
