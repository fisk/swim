package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LauncherProcessIT {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(30)
    void actualEditorCanSwitchFilesDeleteSaveAndQuit() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path runtimeLibs = buildRoot.resolve("swim-launcher").resolve("target").resolve("runtime-libs");
        Path file = tempDir.resolve("switch-source.txt");
        Path other = tempDir.resolve("switch-target.txt");
        Files.writeString(file, "source");
        Files.writeString(other, "xyz");
        String javaAgentArg = jacocoAgentArg(buildRoot);

        var process = startProcess(buildRoot, launcherJar, runtimeLibs, file, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "-cp", launcherJar + ":" + runtimeLibs.resolve("*"),
                "org.fisk.swim.launcher.Main", file.toString());
        try {
            waitForStartup();
            runCommand(process, "e " + other);
            type(process, "x");
            runCommand(process, "w");
            runCommand(process, "q");

            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Launcher did not exit after file-switch workflow.\n" + process.output());
            assertEquals("source", Files.readString(file));
            assertEquals("yz", Files.readString(other));
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(30)
    void actualEditorCanDeleteSaveAndQuit() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path runtimeLibs = buildRoot.resolve("swim-launcher").resolve("target").resolve("runtime-libs");
        Path file = tempDir.resolve("undo-redo.txt");
        Files.writeString(file, "abc");
        String javaAgentArg = jacocoAgentArg(buildRoot);

        var process = startProcess(buildRoot, launcherJar, runtimeLibs, file, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "-cp", launcherJar + ":" + runtimeLibs.resolve("*"),
                "org.fisk.swim.launcher.Main", file.toString());
        try {
            waitForStartup();
            type(process, "x");
            runCommand(process, "w");
            runCommand(process, "q");

            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Launcher did not exit after delete workflow.\n" + process.output());
            assertEquals("bc", Files.readString(file));
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(20)
    void actualEditorProcessStartsInPseudoTerminal() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path runtimeLibs = buildRoot.resolve("swim-launcher").resolve("target").resolve("runtime-libs");
        Path file = buildRoot.resolve("README.md");
        String javaAgentArg = jacocoAgentArg(buildRoot);

        var process = startProcess(buildRoot, launcherJar, runtimeLibs, file, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "-cp", launcherJar + ":" + runtimeLibs.resolve("*"), "org.fisk.swim.launcher.Main", file.toString());

        Thread.sleep(2500);
        assertTrue(process.process().isAlive(), "Editor process should still be alive after startup");

        process.process().destroyForcibly();
        boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(5));
        assertTrue(exited, "Editor process did not terminate after forced destroy");
    }

    @Test
    @Timeout(20)
    void launcherProcessExitsWhenCoreRequestsExit() throws Exception {
        Path buildRoot = createSyntheticBuildRoot();
        Path launcherJar = Main.getLauncherLocation();
        Path runtimeLibs = launcherJar.getParent().resolve("runtime-libs");
        Path file = buildRoot.resolve("README.txt");
        Files.writeString(file, "hello");

        var process = startProcess(buildRoot, launcherJar, runtimeLibs, file,
                "java", "-cp", launcherJar + ":" + runtimeLibs.resolve("*"),
                "org.fisk.swim.launcher.Main", file.toString());

        boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
        assertTrue(exited, "Launcher did not exit after synthetic core requested exit.\n" + process.output());
        assertTrue(!process.output().toString().contains("Exception"),
                "Launcher process logged an exception while exiting.\n" + process.output());
    }

    private static void waitForStartup() throws InterruptedException {
        Thread.sleep(3500);
    }

    private static void runCommand(StartedProcess process, String command) throws Exception {
        type(process, ":");
        type(process, command);
        pressEnter(process);
        Thread.sleep(250);
    }

    private static void pressEnter(StartedProcess process) throws Exception {
        write(process.process(), new byte[] { '\r' });
        Thread.sleep(250);
    }

    private static void type(StartedProcess process, String text) throws Exception {
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            write(process.process(), new byte[] { value });
            Thread.sleep(60);
        }
    }

    private static void write(Process process, byte[] bytes) throws IOException {
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
    }

    private static void destroyIfAlive(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(java.time.Duration.ofSeconds(5));
        }
    }

    private StartedProcess startProcess(Path workdir, Path launcherJar, Path runtimeLibs, Path file, String... command) throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(launcherJar), "Launcher jar missing");
        Assumptions.assumeTrue(Files.isDirectory(runtimeLibs), "Launcher runtime libs missing");
        Assumptions.assumeTrue(Files.isRegularFile(file), "Input file missing");

        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir.toFile());
        processBuilder.redirectErrorStream(true);
        var process = processBuilder.start();
        var output = new StringBuilder();
        var gobbler = new Thread(() -> readOutput(process, output), "launcher-process-it-output");
        gobbler.setDaemon(true);
        gobbler.start();
        return new StartedProcess(process, output);
    }

    private static String jacocoAgentArg(Path buildRoot) {
        Path agent = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "jacoco",
                "org.jacoco.agent", "0.8.13", "org.jacoco.agent-0.8.13-runtime.jar");
        Assumptions.assumeTrue(Files.isRegularFile(agent), "JaCoCo agent jar missing");
        Path execFile = buildRoot.resolve("swim-core").resolve("target").resolve("jacoco-it.exec");
        return "-javaagent:" + agent + "=destfile=" + execFile + ",append=true";
    }

    private record StartedProcess(Process process, StringBuilder output) {
    }

    private Path createSyntheticBuildRoot() throws Exception {
        Path root = tempDir.resolve("swim");
        Path target = root.resolve("swim-core").resolve("target");
        Path runtimeLibs = target.resolve("runtime-libs");
        Files.createDirectories(runtimeLibs);
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.createDirectories(root.resolve("swim-core"));

        Path compileDir = tempDir.resolve("compile-it");
        Path coreClasses = compileDir.resolve("core-classes");
        Files.createDirectories(coreClasses);

        compileJava(Map.of(
                "fake/core/ExitApp.java", """
                        package fake.core;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class ExitApp implements SwimApp {
                            public void start(Path path, SwimHost host) {
                                Thread thread = new Thread(() -> {
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    host.requestExit();
                                });
                                thread.setDaemon(true);
                                thread.start();
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
        ), coreClasses, List.of(System.getProperty("java.class.path")));

        Path coreJar = target.resolve("swim-core-0.0.1-SNAPSHOT.jar");
        jarDirectory(coreClasses, coreJar, "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.ExitApp\n"));
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
                throw new IllegalStateException("Compilation failed for synthetic launcher integration sources");
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

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(buffer, 0, read);
            }
        } catch (IOException e) {
        }
    }
}
