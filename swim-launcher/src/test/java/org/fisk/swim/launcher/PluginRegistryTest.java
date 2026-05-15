package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
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

class PluginRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void registryLoadsExternalPluginsFromLibAndUnloadsCoreLast() throws Exception {
        Path root = createSyntheticInstalledBuildRoot();
        Path file = root.resolve("README.txt");
        Path events = root.resolve("plugin-registry-events.txt");
        Files.writeString(file, "hello");

        var registry = new PluginRegistry();
        var host = new RecordingHost(root);

        SwimApp app = registry.reload(root, file, host, Main.class.getClassLoader(), null);

        assertSame(app, registry.currentApp());
        assertEquals(List.of("core", "marker-plugin"), registry.availablePluginIds());
        assertEquals(List.of("core"), registry.loadedPluginIds());
        assertEquals(List.of("core-start"), Files.readAllLines(events));

        registry.unloadPlugin("marker-plugin");

        assertEquals(List.of("core"), registry.loadedPluginIds());
        assertEquals(List.of("core-start"), Files.readAllLines(events));

        registry.loadPlugin("marker-plugin", file, host);

        assertEquals(List.of("core", "marker-plugin"), registry.loadedPluginIds());
        assertEquals(List.of("core-start", "plugin-load"), Files.readAllLines(events));

        registry.unloadPlugin("marker-plugin");

        assertEquals(List.of("core"), registry.loadedPluginIds());
        assertEquals(List.of("core-start", "plugin-load", "plugin-close"), Files.readAllLines(events));

        registry.loadPlugin("marker-plugin", file, host);

        assertEquals(List.of("core", "marker-plugin"), registry.loadedPluginIds());
        assertEquals(List.of("core-start", "plugin-load", "plugin-close", "plugin-load"), Files.readAllLines(events));

        registry.unloadAll();

        assertNull(registry.currentApp());
        assertEquals(List.of("core-start", "plugin-load", "plugin-close", "plugin-load", "plugin-close", "core-close"),
                Files.readAllLines(events));
    }

    @Test
    void registryRestoresPreviousCoreWhenReplacementCoreFailsToLoad() throws Exception {
        Path root = createSyntheticInstalledBuildRoot();
        Path file = root.resolve("README.txt");
        Path events = root.resolve("plugin-registry-events.txt");
        Files.writeString(file, "hello");

        var registry = new PluginRegistry();
        var host = new RecordingHost(root);

        SwimApp firstApp = registry.reload(root, file, host, Main.class.getClassLoader(), null);
        assertSame(firstApp, registry.currentApp());
        assertEquals(List.of("core-start"), Files.readAllLines(events));

        installFailingReplacementCore(root);

        assertThrows(RuntimeException.class, () -> registry.reload(root, file, host, Main.class.getClassLoader(), null));

        assertSame(firstApp.getClass(), registry.currentApp().getClass());
        assertEquals(List.of("core-start", "core-close", "core-start"), Files.readAllLines(events));
    }

    @Test
    void registryLoadsPluginThatDependsOnSharedRuntimeLibFromCoreLayer() throws Exception {
        Path root = createSyntheticInstalledBuildRootWithPluginDependency();
        Path file = root.resolve("README.txt");
        Path events = root.resolve("plugin-registry-events.txt");
        Files.writeString(file, "hello");

        var registry = new PluginRegistry();
        var host = new RecordingHost(root);

        registry.reload(root, file, host, Main.class.getClassLoader(), null);
        registry.loadPlugin("marker-plugin", file, host);

        assertEquals(List.of("core-start", "plugin-load:ok"), Files.readAllLines(events));
    }

    private Path createSyntheticInstalledBuildRoot() throws Exception {
        Path root = tempDir.resolve("swim");
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), "<project />");

        Path compileDir = tempDir.resolve("compile");
        Path coreClasses = compileDir.resolve("core-classes");
        Path pluginClasses = compileDir.resolve("plugin-classes");
        Files.createDirectories(coreClasses);
        Files.createDirectories(pluginClasses);
        String classpath = System.getProperty("java.class.path");

        compileJava(Map.of(
                "fake/core/RecordingApp.java", """
                        package fake.core;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.StandardOpenOption;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class RecordingApp implements SwimApp {
                            private Path path;
                            private SwimHost host;
                            public void start(Path path, SwimHost host) {
                                this.path = path;
                                this.host = host;
                                append("core-start");
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return path;
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                                append("core-close");
                            }
                            private void append(String event) {
                                try {
                                    Files.writeString(host.getBuildRoot().resolve("plugin-registry-events.txt"),
                                            event + System.lineSeparator(),
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), coreClasses, List.of(classpath));

        compileJava(Map.of(
                "fake/plugin/MarkerPlugin.java", """
                        package fake.plugin;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.StandardOpenOption;
                        import org.fisk.swim.api.SwimPlugin;
                        import org.fisk.swim.api.SwimPluginContext;
                        public final class MarkerPlugin implements SwimPlugin {
                            private Path events;
                            public String getId() {
                                return "marker-plugin";
                            }
                            public boolean loadOnStartup() {
                                return false;
                            }
                            public void load(SwimPluginContext context) {
                                events = context.getHost().getBuildRoot().resolve("plugin-registry-events.txt");
                                append("plugin-load");
                            }
                            public void close() {
                                append("plugin-close");
                            }
                            private void append(String event) {
                                try {
                                    Files.writeString(events,
                                            event + System.lineSeparator(),
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), pluginClasses, List.of(classpath));

        jarDirectory(coreClasses, plugins.resolve("swim-core-0.0.1-SNAPSHOT.jar"), "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.RecordingApp\n"));
        jarDirectory(pluginClasses, plugins.resolve("marker-plugin-0.0.1-SNAPSHOT.jar"), "demo.marker.plugin",
                Map.of("META-INF/services/org.fisk.swim.api.SwimPlugin", "fake.plugin.MarkerPlugin\n"));
        return root;
    }

    private Path createSyntheticInstalledBuildRootWithPluginDependency() throws Exception {
        Path root = tempDir.resolve("swim-deps");
        Path plugins = root.resolve("plugins");
        Path runtimeLibs = plugins.resolve("runtime-libs");
        Files.createDirectories(runtimeLibs);
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), "<project />");

        Path compileDir = tempDir.resolve("compile-deps");
        Path helperClasses = compileDir.resolve("helper-classes");
        Path coreClasses = compileDir.resolve("core-classes");
        Path pluginClasses = compileDir.resolve("plugin-classes");
        Files.createDirectories(helperClasses);
        Files.createDirectories(coreClasses);
        Files.createDirectories(pluginClasses);
        String classpath = System.getProperty("java.class.path");

        compileJava(Map.of(
                "fake/dep/Helper.java", """
                        package fake.dep;
                        public final class Helper {
                            public static String value() {
                                return "ok";
                            }
                        }
                        """
        ), helperClasses, List.of(classpath));
        Path helperJar = runtimeLibs.resolve("helper-lib.jar");
        jarDirectory(helperClasses, helperJar, null, Map.of());

        compileJava(Map.of(
                "fake/core/RecordingApp.java", """
                        package fake.core;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.StandardOpenOption;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class RecordingApp implements SwimApp {
                            private SwimHost host;
                            public void start(Path path, SwimHost host) {
                                this.host = host;
                                append("core-start");
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return Path.of(".");
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                            private void append(String event) {
                                try {
                                    Files.writeString(host.getBuildRoot().resolve("plugin-registry-events.txt"),
                                            event + System.lineSeparator(),
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), coreClasses, List.of(classpath, helperJar.toString()));

        compileJava(Map.of(
                "fake/plugin/MarkerPlugin.java", """
                        package fake.plugin;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.StandardOpenOption;
                        import org.fisk.swim.api.SwimPlugin;
                        import org.fisk.swim.api.SwimPluginContext;
                        import fake.dep.Helper;
                        public final class MarkerPlugin implements SwimPlugin {
                            private Path events;
                            public String getId() {
                                return "marker-plugin";
                            }
                            public boolean loadOnStartup() {
                                return false;
                            }
                            public void load(SwimPluginContext context) {
                                events = context.getHost().getBuildRoot().resolve("plugin-registry-events.txt");
                                append("plugin-load:" + Helper.value());
                            }
                            public void close() {
                            }
                            private void append(String event) {
                                try {
                                    Files.writeString(events,
                                            event + System.lineSeparator(),
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), pluginClasses, List.of(classpath, helperJar.toString()));

        jarDirectory(coreClasses, plugins.resolve("swim-core-0.0.1-SNAPSHOT.jar"), "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.RecordingApp\n"));
        jarDirectory(pluginClasses, plugins.resolve("marker-plugin-0.0.1-SNAPSHOT.jar"), "demo.marker.plugin",
                Map.of("META-INF/services/org.fisk.swim.api.SwimPlugin", "fake.plugin.MarkerPlugin\n"));
        return root;
    }

    private void installFailingReplacementCore(Path root) throws Exception {
        Path plugins = root.resolve("plugins");
        Path compileDir = tempDir.resolve("compile-failing");
        Path failingCoreClasses = compileDir.resolve("failing-core-classes");
        Files.createDirectories(failingCoreClasses);
        String classpath = System.getProperty("java.class.path");

        compileJava(Map.of(
                "fake/core/FailingApp.java", """
                        package fake.core;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class FailingApp implements SwimApp {
                            public void start(Path path, SwimHost host) {
                                throw new IllegalStateException("boom");
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return Path.of(".");
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                        }
                        """
        ), failingCoreClasses, List.of(classpath));

        jarDirectory(failingCoreClasses, plugins.resolve("swim-core-0.0.2-SNAPSHOT.jar"), "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.FailingApp\n"));
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
                throw new IllegalStateException("Compilation failed for synthetic plugin registry sources");
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

    private record RecordingHost(Path buildRoot) implements SwimHost {
        @Override
        public void requestReload(Path path) {
        }

        @Override
        public void requestRebuildAndReload(Path path) {
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
        }

        @Override
        public void requestExit() {
        }

        @Override
        public Path getBuildRoot() {
            return buildRoot;
        }
    }
}
