package org.fisk.swim.launcher;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPanel;

public class Main implements SwimHost {
    private static final String CORE_MODULE = "org.fisk.swim.core";
    static final String RELOAD_SESSION_PROPERTY = "swim.session.restore_on_reload";
    private static final Set<String> SHARED_LIB_PREFIXES = Set.of(
            "swim-launcher-");

    @FunctionalInterface
    interface Rebuilder {
        boolean rebuild(Path buildRoot);
    }

    @FunctionalInterface
    interface TaskRunner {
        void start(String name, boolean daemon, Runnable task);
    }

    interface PluginController {
        SwimApp reload(Path buildRoot, Path path, SwimHost host, ClassLoader parentLoader, Runnable beforeLoad);
        SwimApp currentApp();
        List<String> loadedPluginIds();
        void loadPlugin(String id, Path path, SwimHost host);
        void unloadAll();
    }

    private final Object _reloadLock = new Object();
    private final PluginController _plugins;
    private final Rebuilder _rebuilder;
    private final TaskRunner _taskRunner;
    private final Supplier<Path> _launcherLocationSupplier;
    private final CountDownLatch _exitLatch = new CountDownLatch(1);
    private final Map<String, SwimPanel> _panels = new ConcurrentHashMap<>();
    private volatile boolean _reloading;
    private Path _buildRoot;

    public Main() {
        this(new PluginRegistry(), Main::rebuild, Main::startThread, Main::getLauncherLocation);
    }

    Main(PluginController plugins, Rebuilder rebuilder, TaskRunner taskRunner, Supplier<Path> launcherLocationSupplier) {
        _plugins = plugins;
        _rebuilder = rebuilder;
        _taskRunner = taskRunner;
        _launcherLocationSupplier = launcherLocationSupplier;
    }

    public static void main(String[] args) {
        new Main().run(args);
    }

    private void run(String[] args) {
        Path path = null;
        if (args.length == 1) {
            path = checkArguments(args);
            if (path == null) {
                return;
            }
        } else if (args.length > 1) {
            System.out.println("swim: Wrong number of arguments: " + args.length + ".");
            System.out.println("Try: swim <file_path>");
            return;
        }

        _buildRoot = determineBuildRoot();
        reload(path, "Loaded SWIM core");
        try {
            _exitLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    private Path checkArguments(String[] args) {
        if (args.length != 1) {
            System.out.println("swim: Wrong number of arguments: " + args.length + ".");
            System.out.println("Try: swim <file_path>");
            return null;
        }

        try {
            var path = Path.of(args[0]).toAbsolutePath();
            var file = path.toFile();
            if (!file.exists() && !file.createNewFile()) {
                System.out.println("swim: No such file: " + path);
                return null;
            }
            return path;
        } catch (Throwable e) {
            return null;
        }
    }

    public static Path findBuildRoot(Path start) {
        Path path = start.toAbsolutePath();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("swim-core"))) {
                return path;
            }
            path = path.getParent();
        }
        return null;
    }

    public static Path getLauncherLocation() {
        try {
            var codeSource = Main.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                var uri = codeSource.getLocation().toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return Path.of(uri).toAbsolutePath();
                }
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
        }
        return Path.of(System.getProperty("java.home")).toAbsolutePath();
    }

    private Path determineBuildRoot() {
        Path launcherLocation = _launcherLocationSupplier.get();
        Path root = findBuildRoot(launcherLocation);
        if (root != null) {
            return root;
        }

        Path cwdRoot = findBuildRoot(Path.of(System.getProperty("user.dir")));
        if (cwdRoot != null) {
            return cwdRoot;
        }

        throw new IllegalStateException("Unable to locate SWIM build root from " + launcherLocation);
    }

    static List<Path> getCoreModulePath(Path buildRoot) {
        Path coreTarget = resolveCoreArtifactDirectory(buildRoot);
        var paths = new ArrayList<Path>();
        paths.add(findCoreJar(coreTarget));
        Path libs = coreTarget.resolve("runtime-libs");
        if (Files.isDirectory(libs)) {
            try (var stream = Files.list(libs)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .filter(path -> !isSharedLib(path))
                        .sorted()
                        .forEach(paths::add);
            } catch (IOException e) {
                throw new RuntimeException("Unable to inspect core libs directory " + libs, e);
            }
        }
        return paths;
    }

    static Path resolveCoreArtifactDirectory(Path buildRoot) {
        Path installedCore = buildRoot.resolve("plugins");
        if (Files.isDirectory(installedCore)) {
            return installedCore;
        }
        return buildRoot.resolve("swim-core").resolve("target");
    }

    private List<Path> getCoreModulePath() {
        return getCoreModulePath(_buildRoot);
    }

    static boolean isSharedLib(Path path) {
        String name = path.getFileName().toString();
        for (var prefix : SHARED_LIB_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static Path findCoreJar(Path coreTarget) {
        try (var stream = Files.list(coreTarget)) {
            Optional<Path> result = stream
                    .filter(path -> path.getFileName().toString().startsWith("swim-core-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-tests.jar"))
                    .max(Main::compareJarCandidates);
            return result.orElseThrow(() -> new IllegalStateException("Unable to find built swim-core jar in " + coreTarget));
        } catch (IOException e) {
            throw new RuntimeException("Unable to inspect " + coreTarget, e);
        }
    }

    private static int compareJarCandidates(Path left, Path right) {
        return compareVersionTokens(jarCandidateStem(left), jarCandidateStem(right));
    }

    private static String jarCandidateStem(Path path) {
        String name = path.getFileName().toString();
        return name.substring("swim-core-".length(), name.length() - ".jar".length());
    }

    static int compareVersionTokens(String left, String right) {
        var leftParts = left.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)|[.-]");
        var rightParts = right.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)|[.-]");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            String l = i < leftParts.length ? leftParts[i] : "";
            String r = i < rightParts.length ? rightParts[i] : "";
            if (l.equals(r)) {
                continue;
            }
            boolean lNumeric = l.chars().allMatch(Character::isDigit) && !l.isEmpty();
            boolean rNumeric = r.chars().allMatch(Character::isDigit) && !r.isEmpty();
            if (lNumeric && rNumeric) {
                int cmp = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
                if (cmp != 0) {
                    return cmp;
                }
                continue;
            }
            int cmp = l.compareTo(r);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    static ModuleLayer createCoreLayer(Path buildRoot, ClassLoader parentLoader) {
        var modulePath = getCoreModulePath(buildRoot).toArray(Path[]::new);
        var finder = ModuleFinder.of(modulePath);
        var roots = new HashSet<>(finder.findAll().stream()
                .map(ModuleReference::descriptor)
                .map(descriptor -> descriptor.name())
                .collect(java.util.stream.Collectors.toSet()));
        roots.add(CORE_MODULE);
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), roots);
        return ModuleLayer.defineModulesWithOneLoader(configuration,
                List.of(ModuleLayer.boot()),
                parentLoader).layer();
    }

    private void reload(Path path, String successMessage) {
        synchronized (_reloadLock) {
            _reloading = true;
            String previousReloadFlag = System.getProperty(RELOAD_SESSION_PROPERTY);
            System.setProperty(RELOAD_SESSION_PROPERTY, "true");
            try {
                Runnable beforeLoad = shouldRefreshStandardInput() ? Main::refreshStandardInput : null;
                SwimApp next = _plugins.reload(_buildRoot, path, this, getClass().getClassLoader(), beforeLoad);
                if (successMessage != null) {
                    next.showMessage(successMessage);
                }
            } finally {
                if (previousReloadFlag == null) {
                    System.clearProperty(RELOAD_SESSION_PROPERTY);
                } else {
                    System.setProperty(RELOAD_SESSION_PROPERTY, previousReloadFlag);
                }
                _reloading = false;
            }
        }
    }

    static boolean shouldRefreshStandardInput(PluginController plugins) {
        return plugins != null && plugins.currentApp() != null;
    }

    private boolean shouldRefreshStandardInput() {
        return shouldRefreshStandardInput(_plugins);
    }

    private static void refreshStandardInput() {
        if (System.getProperty("surefire.test.class.path") != null) {
            return;
        }
        System.setIn(new BufferedInputStream(new FileInputStream(FileDescriptor.in)));
    }

    private static boolean rebuild(Path buildRoot) {
        if (buildRoot == null) {
            return false;
        }
        var command = List.of("mvn", "-q", "-DskipTests", "package");
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(buildRoot.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            var process = processBuilder.start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                }
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean rebuild() {
        return _rebuilder.rebuild(_buildRoot);
    }

    private static void startThread(String name, boolean daemon, Runnable task) {
        var thread = new Thread(task, name);
        thread.setDaemon(daemon);
        thread.start();
    }

    @Override
    public void requestReload(Path path) {
        _taskRunner.start("swim-reload", false, () -> {
            try {
                reload(path, "Reloaded SWIM core");
            } catch (RuntimeException e) {
                SwimApp loaded = _plugins.currentApp();
                if (loaded != null) {
                    loaded.showMessage("Reload failed");
                }
            }
        });
    }

    @Override
    public void requestRebuildAndReload(Path path) {
        _taskRunner.start("swim-rebuild", false, () -> {
            SwimApp loaded = _plugins.currentApp();
            if (loaded != null) {
                loaded.showMessage("Rebuilding SWIM...");
            }
            if (!rebuild()) {
                loaded = _plugins.currentApp();
                if (loaded != null) {
                    loaded.showMessage("Build failed");
                }
                return;
            }
            try {
                reload(path, "Rebuilt and reloaded SWIM");
            } catch (RuntimeException e) {
                loaded = _plugins.currentApp();
                if (loaded != null) {
                    loaded.showMessage("Reload failed after rebuild");
                }
            }
        });
    }

    @Override
    public void requestLoadPlugin(String pluginId, Path path) {
        try {
            _plugins.loadPlugin(pluginId, path, this);
        } catch (RuntimeException e) {
            SwimApp loaded = _plugins.currentApp();
            if (loaded != null) {
                String detail = detailedPluginLoadMessage(e);
                loaded.showMessage(detail == null || detail.isBlank()
                        ? "Plugin load failed: " + pluginId
                        : "Plugin load failed: " + pluginId + " (" + detail + ")");
            }
        }
    }

    @Override
    public void registerPanel(String pluginId, SwimPanel panel) {
        if (pluginId == null || panel == null) {
            return;
        }
        _panels.put(pluginId, panel);
    }

    @Override
    public void unregisterPanel(String pluginId) {
        if (pluginId == null) {
            return;
        }
        _panels.remove(pluginId);
    }

    @Override
    public SwimPanel getPanel(String pluginId) {
        return _panels.get(pluginId);
    }

    @Override
    public boolean isReloading() {
        return _reloading;
    }

    @Override
    public void requestExit() {
        SwimApp loaded = _plugins.currentApp();
        if (loaded != null) {
            _taskRunner.start("swim-close", true, () -> {
                try {
                    _plugins.unloadAll();
                } finally {
                    _exitLatch.countDown();
                }
            });
            return;
        }
        _exitLatch.countDown();
    }

    @Override
    public Path getBuildRoot() {
        return _buildRoot;
    }

    SwimApp getLoadedApp() {
        return _plugins.currentApp();
    }

    private static String detailedPluginLoadMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    List<String> getLoadedPluginIds() {
        return _plugins.loadedPluginIds();
    }
}
