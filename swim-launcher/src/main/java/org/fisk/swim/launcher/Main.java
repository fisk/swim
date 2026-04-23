package org.fisk.swim.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;

public class Main implements SwimHost {
    private static final String CORE_MODULE = "org.fisk.swim.core";
    private static final Set<String> SHARED_LIB_PREFIXES = Set.of(
            "swim-launcher-");

    private record LoadedApp(ModuleLayer layer, SwimApp app) {
    }

    private final Object _reloadLock = new Object();
    private volatile LoadedApp _loadedApp;
    private final CountDownLatch _exitLatch = new CountDownLatch(1);
    private Path _buildRoot;

    public static void main(String[] args) {
        new Main().run(args);
    }

    private void run(String[] args) {
        Path path = checkArguments(args);
        if (path == null) {
            return;
        }

        _buildRoot = determineBuildRoot();
        reload(path, "Loaded SWIM core");
        try {
            _exitLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    static Path findBuildRoot(Path start) {
        Path path = start.toAbsolutePath();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("pom.xml")) && Files.isDirectory(path.resolve("swim-core"))) {
                return path;
            }
            path = path.getParent();
        }
        return null;
    }

    static Path getLauncherLocation() {
        try {
            return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unable to determine launcher location", e);
        }
    }

    private Path determineBuildRoot() {
        Path launcherLocation = getLauncherLocation();
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

    private List<Path> getCoreModulePath() {
        Path coreTarget = _buildRoot.resolve("swim-core").resolve("target");
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

    private boolean isSharedLib(Path path) {
        String name = path.getFileName().toString();
        for (var prefix : SHARED_LIB_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Path findCoreJar(Path coreTarget) {
        try (var stream = Files.list(coreTarget)) {
            Optional<Path> result = stream
                    .filter(path -> path.getFileName().toString().startsWith("swim-core-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-tests.jar"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
            return result.orElseThrow(() -> new IllegalStateException("Unable to find built swim-core jar in " + coreTarget));
        } catch (IOException e) {
            throw new RuntimeException("Unable to inspect " + coreTarget, e);
        }
    }

    private LoadedApp loadApp() {
        var modulePath = getCoreModulePath().toArray(Path[]::new);
        var finder = ModuleFinder.of(modulePath);
        var roots = new HashSet<>(finder.findAll().stream()
                .map(ModuleReference::descriptor)
                .map(descriptor -> descriptor.name())
                .collect(java.util.stream.Collectors.toSet()));
        roots.add(CORE_MODULE);
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), roots);
        ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(configuration,
                List.of(ModuleLayer.boot()),
                getClass().getClassLoader()).layer();
        ServiceLoader<SwimApp> loader = ServiceLoader.load(layer, SwimApp.class);
        var app = loader.findFirst().orElseThrow(() -> new IllegalStateException("No SwimApp service found in " + CORE_MODULE));
        return new LoadedApp(layer, app);
    }

    private void reload(Path path, String successMessage) {
        synchronized (_reloadLock) {
            LoadedApp next = loadApp();
            next.app().start(path, this);
            next.app().refresh(true);
            LoadedApp previous = _loadedApp;
            _loadedApp = next;
            if (previous != null) {
                previous.app().close();
            }
            if (successMessage != null) {
                next.app().showMessage(successMessage);
            }
        }
    }

    private boolean rebuild() {
        if (_buildRoot == null) {
            return false;
        }
        var command = List.of("mvn", "-q", "-DskipTests", "package");
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(_buildRoot.toFile());
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

    @Override
    public void requestReload(Path path) {
        new Thread(() -> {
            try {
                reload(path, "Reloaded SWIM core");
            } catch (RuntimeException e) {
                LoadedApp loaded = _loadedApp;
                if (loaded != null) {
                    loaded.app().showMessage("Reload failed");
                }
            }
        }, "swim-reload").start();
    }

    @Override
    public void requestRebuildAndReload(Path path) {
        new Thread(() -> {
            LoadedApp loaded = _loadedApp;
            if (loaded != null) {
                loaded.app().showMessage("Rebuilding SWIM...");
            }
            if (!rebuild()) {
                loaded = _loadedApp;
                if (loaded != null) {
                    loaded.app().showMessage("Build failed");
                }
                return;
            }
            try {
                reload(path, "Rebuilt and reloaded SWIM");
            } catch (RuntimeException e) {
                loaded = _loadedApp;
                if (loaded != null) {
                    loaded.app().showMessage("Reload failed after rebuild");
                }
            }
        }, "swim-rebuild").start();
    }

    @Override
    public void requestExit() {
        LoadedApp loaded = _loadedApp;
        if (loaded != null) {
            loaded.app().close();
            _loadedApp = null;
        }
        _exitLatch.countDown();
    }

    @Override
    public Path getBuildRoot() {
        return _buildRoot;
    }
}
