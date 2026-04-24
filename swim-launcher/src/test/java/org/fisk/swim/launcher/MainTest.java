package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.fisk.swim.api.SwimApp;
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
    void launcherCanLoadCoreLayerWithRuntimeDependencyJar() throws Exception {
        Path root = createSyntheticBuildRoot();

        ModuleLayer layer = Main.createCoreLayer(root, Main.class.getClassLoader());
        SwimApp app = ServiceLoader.load(layer, SwimApp.class).findFirst().orElseThrow();

        assertEquals("fake.core.FakeApp", app.getClass().getName());
        app.close();
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
}
