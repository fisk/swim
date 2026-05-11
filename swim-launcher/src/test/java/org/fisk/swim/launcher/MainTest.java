package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
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
    }

    @Test
    void coreModulePathPrefersInstalledBinArtifactsWhenPresent() throws Exception {
        Path root = tempDir.resolve("swim");
        Path target = root.resolve("swim-core").resolve("target");
        Path installedCore = root.resolve("lib");
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
    void sharedLibFilterOnlyExcludesLauncherArtifacts() {
        assertTrue(Main.isSharedLib(Path.of("swim-launcher-0.0.1-SNAPSHOT.jar")));
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
    void launcherScriptInstallerCreatesExecutableScript() throws Exception {
        LauncherScriptInstaller.install(tempDir);

        Path script = tempDir.resolve("bin").resolve("swim");
        assertTrue(Files.isRegularFile(script));
        assertTrue(Files.isExecutable(script));
        String content = Files.readString(script);
        assertTrue(content.contains("SWIM_JAVA_ARGS"));
        assertTrue(content.contains("org.fisk.swim.launcher/org.fisk.swim.launcher.Main"));
        assertTrue(content.contains("--add-modules=java.instrument"));
        assertFalse(content.contains("com.sun.tools.classfile"));
        assertTrue(content.contains("LOG_FILE=\"$LOG_DIR/swim-$$.log\""));
        assertTrue(content.contains("exec 2>>\"$LOG_FILE\""));
    }

    @Test
    void checkArgumentsCreatesMissingFileAndReturnsAbsolutePath() throws Exception {
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("app loading not expected");
        }, buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);
        Path file = tempDir.resolve("new-file.txt");

        Path result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class }, (Object) new String[] { file.toString() });

        assertEquals(file.toAbsolutePath(), result);
        assertTrue(Files.exists(file));
    }

    @Test
    void checkArgumentsRejectsWrongNumberOfArguments() throws Exception {
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("app loading not expected");
        }, buildRoot -> false, (name, daemon, task) -> {
            throw new AssertionError("tasks not expected");
        }, () -> tempDir);

        Path result = invokePrivate(main, "checkArguments", new Class<?>[] { String[].class }, (Object) new String[] { "a", "b" });

        assertNull(result);
    }

    @Test
    void determineBuildRootFallsBackToUserDirWhenLauncherLocationMisses() throws Exception {
        Path root = tempDir.resolve("swim");
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("app loading not expected");
        }, buildRoot -> false, (name, daemon, task) -> {
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
    void determineBuildRootThrowsWhenLauncherLocationAndUserDirMiss() {
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("app loading not expected");
        }, buildRoot -> false, (name, daemon, task) -> {
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
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main((buildRoot, parentLoader) -> next, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, taskRunner, () -> tempDir);
        setLoadedApp(main, previous);
        Path path = tempDir.resolve("project.txt");

        main.requestReload(path);

        assertEquals(List.of("swim-reload"), taskRunner.names);
        assertEquals(List.of(false), taskRunner.daemons);
        assertEquals(path, next.startedPath);
        assertSame(main, next.startedHost);
        assertEquals(List.of(true), next.refreshCalls);
        assertEquals(List.of("Reloaded SWIM core"), next.messages);
        assertTrue(previous.closed);
        assertSame(next, getLoadedApp(main));
    }

    @Test
    void requestReloadFailureShowsMessageOnExistingApp() throws Exception {
        FakeApp previous = new FakeApp();
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new IllegalStateException("boom");
        }, buildRoot -> {
            throw new AssertionError("rebuild not expected");
        }, taskRunner, () -> tempDir);
        setLoadedApp(main, previous);

        main.requestReload(tempDir.resolve("project.txt"));

        assertEquals(List.of("Reload failed"), previous.messages);
        assertFalse(previous.closed);
        assertSame(previous, getLoadedApp(main));
    }

    @Test
    void requestRebuildAndReloadReportsBuildFailureWithoutReloading() throws Exception {
        FakeApp previous = new FakeApp();
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        AtomicReference<Path> rebuildPath = new AtomicReference<>();
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("reload not expected");
        }, buildRoot -> {
            rebuildPath.set(buildRoot);
            return false;
        }, taskRunner, () -> tempDir);
        setLoadedApp(main, previous);
        setBuildRoot(main, tempDir.resolve("build-root"));

        main.requestRebuildAndReload(tempDir.resolve("project.txt"));

        assertEquals(tempDir.resolve("build-root"), rebuildPath.get());
        assertEquals(List.of("Rebuilding SWIM...", "Build failed"), previous.messages);
        assertFalse(previous.closed);
        assertSame(previous, getLoadedApp(main));
    }

    @Test
    void requestRebuildAndReloadReportsReloadFailureAfterSuccessfulBuild() throws Exception {
        FakeApp previous = new FakeApp();
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new IllegalStateException("boom");
        }, buildRoot -> true, (name, daemon, task) -> task.run(), () -> tempDir);
        setLoadedApp(main, previous);

        main.requestRebuildAndReload(tempDir.resolve("project.txt"));

        assertEquals(List.of("Rebuilding SWIM...", "Reload failed after rebuild"), previous.messages);
        assertFalse(previous.closed);
        assertSame(previous, getLoadedApp(main));
    }

    @Test
    void requestRebuildAndReloadReplacesRunningAppAfterSuccessfulBuild() throws Exception {
        FakeApp previous = new FakeApp();
        FakeApp next = new FakeApp();
        Main main = new Main((buildRoot, parentLoader) -> next, buildRoot -> true, (name, daemon, task) -> task.run(), () -> tempDir);
        setLoadedApp(main, previous);
        Path path = tempDir.resolve("project.txt");

        main.requestRebuildAndReload(path);

        assertEquals(List.of("Rebuilding SWIM..."), previous.messages);
        assertTrue(previous.closed);
        assertEquals(path, next.startedPath);
        assertEquals(List.of(true), next.refreshCalls);
        assertEquals(List.of("Rebuilt and reloaded SWIM"), next.messages);
        assertSame(next, getLoadedApp(main));
    }

    @Test
    void requestExitCountsDownLatchClearsLoadedAppAndClosesInDaemonTask() throws Exception {
        FakeApp app = new FakeApp();
        RecordingTaskRunner taskRunner = new RecordingTaskRunner();
        Main main = new Main((buildRoot, parentLoader) -> {
            throw new AssertionError("app loading not expected");
        }, buildRoot -> false, taskRunner, () -> tempDir);
        setLoadedApp(main, app);

        main.requestExit();

        assertNull(getLoadedApp(main));
        assertTrue(app.closed);
        assertEquals(List.of("swim-close"), taskRunner.names);
        assertEquals(List.of(true), taskRunner.daemons);
        assertEquals(0L, getExitLatch(main).getCount());
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

    private static void setLoadedApp(Main main, SwimApp app) throws Exception {
        Field field = Main.class.getDeclaredField("_loadedApp");
        field.setAccessible(true);
        field.set(main, createLoadedApp(app));
    }

    private static SwimApp getLoadedApp(Main main) throws Exception {
        Field field = Main.class.getDeclaredField("_loadedApp");
        field.setAccessible(true);
        Object loadedApp = field.get(main);
        if (loadedApp == null) {
            return null;
        }
        Method appMethod = loadedApp.getClass().getDeclaredMethod("app");
        appMethod.setAccessible(true);
        return (SwimApp) appMethod.invoke(loadedApp);
    }

    private static CountDownLatch getExitLatch(Main main) throws Exception {
        Field field = Main.class.getDeclaredField("_exitLatch");
        field.setAccessible(true);
        return (CountDownLatch) field.get(main);
    }

    private static Object createLoadedApp(SwimApp app) throws Exception {
        Constructor<?> constructor = Class.forName("org.fisk.swim.launcher.Main$LoadedApp").getDeclaredConstructor(SwimApp.class);
        constructor.setAccessible(true);
        return constructor.newInstance(app);
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

    private static final class FakeApp implements SwimApp {
        private Path startedPath;
        private SwimHost startedHost;
        private final List<Boolean> refreshCalls = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private boolean closed;

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
        public void close() {
            closed = true;
        }
    }
}
