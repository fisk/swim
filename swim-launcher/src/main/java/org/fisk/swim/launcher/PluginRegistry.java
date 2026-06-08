package org.fisk.swim.launcher;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimNemoToolRegistry;
import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;

final class PluginRegistry implements Main.PluginController {
    private static final String CORE_MODULE = "org.fisk.swim.core";
    private static final String CORE_PLUGIN_ID = "core";
    private static final Comparator<PluginBinding> LOAD_ORDER = Comparator
            .comparingInt(PluginBinding::loadOrder)
            .thenComparing(PluginBinding::id);

    private LoadedPlugins _loadedPlugins = LoadedPlugins.empty();

    @Override
    public synchronized SwimApp reload(Path buildRoot, List<Path> paths, SwimHost host, ClassLoader parentLoader,
            Runnable beforeLoad) {
        LoadedPlugins previous = _loadedPlugins;
        LoadedPlugins next = discover(buildRoot, parentLoader);
        try {
            previous.unloadAll();
            if (beforeLoad != null) {
                beforeLoad.run();
            }
            _loadedPlugins = next;
            next.loadAll(paths, host);
            return next.app();
        } catch (RuntimeException | Error e) {
            try {
                previous.loadAll(paths, host);
                _loadedPlugins = previous;
            } catch (RuntimeException | Error restoreFailure) {
                e.addSuppressed(restoreFailure);
                _loadedPlugins = LoadedPlugins.empty();
            }
            throw e;
        }
    }

    @Override
    public synchronized SwimApp currentApp() {
        return _loadedPlugins.app();
    }

    @Override
    public synchronized List<String> loadedPluginIds() {
        return _loadedPlugins.loadedPluginIds();
    }

    @Override
    public synchronized void loadPlugin(String id, Path path, SwimHost host) {
        _loadedPlugins.loadPlugin(id, path, host);
    }

    synchronized List<String> availablePluginIds() {
        return _loadedPlugins.availablePluginIds();
    }

    synchronized void unloadPlugin(String id) {
        _loadedPlugins.unloadPlugin(id);
    }

    synchronized void reloadPlugin(String id, Path path, SwimHost host) {
        _loadedPlugins.reloadPlugin(id, path, host);
    }

    @Override
    public synchronized void unloadAll() {
        LoadedPlugins loaded = _loadedPlugins;
        _loadedPlugins = LoadedPlugins.empty();
        loaded.unloadAll();
    }

    static List<Path> findPluginJars(Path buildRoot) {
        Path pluginRoot = Main.resolveCoreArtifactDirectory(buildRoot);
        if (!Files.isDirectory(pluginRoot)) {
            return List.of();
        }
        boolean installedPlugins = Files.isDirectory(buildRoot.resolve("plugins"));
        if (!installedPlugins) {
            return List.of(Main.findCoreJar(pluginRoot));
        }

        Map<String, Path> jarsByModule = new HashMap<>();
        try (var stream = Files.list(pluginRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-tests.jar"))
                    .filter(path -> !Main.isSharedLib(path))
                    .forEach(path -> {
                        String moduleName = describeModule(path);
                        jarsByModule.merge(moduleName, path, PluginRegistry::preferNewerJar);
                    });
        } catch (IOException e) {
            throw new RuntimeException("Unable to inspect plugin directory " + pluginRoot, e);
        }
        return jarsByModule.values().stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
    }

    private static Path preferNewerJar(Path left, Path right) {
        try {
            int compareTime = Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
            if (compareTime > 0) {
                return left;
            }
            if (compareTime < 0) {
                return right;
            }
        } catch (IOException e) {
        }
        return left.getFileName().toString().compareTo(right.getFileName().toString()) >= 0 ? left : right;
    }

    private static LoadedPlugins discover(Path buildRoot, ClassLoader parentLoader) {
        ModuleLayer coreLayer = Main.createCoreLayer(buildRoot, parentLoader);
        var coreProvider = ServiceLoader.load(coreLayer, SwimApp.class)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SwimApp service found in " + CORE_MODULE));

        var bindings = new ArrayList<PluginBinding>();
        bindings.add(new CorePluginBinding(coreProvider));
        bindings.addAll(discoverCorePlugins(coreLayer));
        bindings.addAll(discoverExternalPlugins(buildRoot, parentLoader, coreLayer));
        bindings.sort(LOAD_ORDER);
        validateUniqueIds(bindings);
        return new LoadedPlugins(bindings);
    }

    private static List<PluginBinding> discoverCorePlugins(ModuleLayer coreLayer) {
        var bindings = new ArrayList<PluginBinding>();
        for (var provider : ServiceLoader.load(coreLayer, SwimPlugin.class).stream().toList()) {
            SwimPlugin plugin = provider.get();
            bindings.add(new ExtensionPluginBinding(
                    plugin.getId(),
                    plugin.getLoadOrder(),
                    plugin.loadOnStartup(),
                    provider,
                    plugin));
        }
        return bindings;
    }

    private static List<PluginBinding> discoverExternalPlugins(Path buildRoot, ClassLoader parentLoader, ModuleLayer coreLayer) {
        List<Path> pluginJars = findPluginJars(buildRoot).stream()
                .filter(path -> !CORE_MODULE.equals(describeModule(path)))
                .toList();
        if (pluginJars.isEmpty()) {
            return List.of();
        }

        ModuleLayer pluginLayer = createPluginLayer(buildRoot, pluginJars, coreLayer, parentLoader);
        var bindings = new ArrayList<PluginBinding>();
        for (var provider : ServiceLoader.load(pluginLayer, SwimPlugin.class).stream().toList()) {
            SwimPlugin plugin = provider.get();
            bindings.add(new ExtensionPluginBinding(
                    plugin.getId(),
                    plugin.getLoadOrder(),
                    plugin.loadOnStartup(),
                    provider,
                    plugin));
        }
        return bindings;
    }

    static ModuleLayer createPluginLayer(Path buildRoot, List<Path> pluginJars, ModuleLayer coreLayer, ClassLoader parentLoader) {
        var finder = ModuleFinder.of(pluginJars.toArray(Path[]::new));
        var roots = new HashSet<String>();
        for (Path path : pluginJars) {
            roots.add(describeModule(path));
        }
        Configuration configuration = Configuration.resolve(
                finder,
                List.of(ModuleLayer.boot().configuration(), coreLayer.configuration()),
                ModuleFinder.of(),
                roots);
        return ModuleLayer.defineModulesWithOneLoader(
                configuration,
                List.of(ModuleLayer.boot(), coreLayer),
                parentLoader).layer();
    }

    private static void validateUniqueIds(List<PluginBinding> bindings) {
        var ids = new HashSet<String>();
        for (var binding : bindings) {
            if (!ids.add(binding.id())) {
                throw new IllegalStateException("Duplicate plugin id: " + binding.id());
            }
        }
    }

    private static String describeModule(Path path) {
        return ModuleFinder.of(path).findAll().stream()
                .map(ModuleReference::descriptor)
                .map(descriptor -> descriptor.name())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to determine module name for " + path));
    }

    private interface PluginBinding {
        String id();
        int loadOrder();
        boolean loadOnStartup();
        void load(DefaultPluginContext context);
        void unload();
        boolean isLoaded();
        default SwimApp app() {
            return null;
        }
    }

    private static final class LoadedPlugins {
        private final List<PluginBinding> _bindings;

        private LoadedPlugins(List<PluginBinding> bindings) {
            _bindings = List.copyOf(bindings);
        }

        static LoadedPlugins empty() {
            return new LoadedPlugins(List.of());
        }

        SwimApp app() {
            for (var binding : _bindings) {
                SwimApp app = binding.app();
                if (app != null) {
                    return app;
                }
            }
            return null;
        }

        List<String> loadedPluginIds() {
            return _bindings.stream()
                    .filter(PluginBinding::isLoaded)
                    .map(PluginBinding::id)
                    .toList();
        }

        List<String> availablePluginIds() {
            return _bindings.stream()
                    .map(PluginBinding::id)
                    .toList();
        }

        void loadAll(List<Path> paths, SwimHost host) {
            var started = new ArrayList<PluginBinding>();
            try {
                for (var binding : _bindings) {
                    if (!binding.loadOnStartup()) {
                        continue;
                    }
                    binding.load(createContext(paths, host, binding.id()));
                    started.add(binding);
                }
                SwimApp app = app();
                if (app != null) {
                    app.refresh(true);
                }
            } catch (RuntimeException | Error e) {
                unloadReverse(started);
                throw e;
            }
        }

        void unloadAll() {
            unloadReverse(_bindings);
        }

        private static void unloadReverse(List<PluginBinding> bindings) {
            RuntimeException failure = null;
            for (int i = bindings.size() - 1; i >= 0; i--) {
                PluginBinding binding = bindings.get(i);
                if (!binding.isLoaded()) {
                    continue;
                }
                try {
                    binding.unload();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        void loadPlugin(String id, Path path, SwimHost host) {
            if (CORE_PLUGIN_ID.equals(id)) {
                findBinding(id).load(createContext(path == null ? List.of() : List.of(path), host, id));
                return;
            }
            if (app() == null) {
                throw new IllegalStateException("Core plugin must be loaded before loading " + id);
            }
            findBinding(id).load(createContext(path == null ? List.of() : List.of(path), host, id));
        }

        void unloadPlugin(String id) {
            if (CORE_PLUGIN_ID.equals(id)) {
                unloadAll();
                return;
            }
            findBinding(id).unload();
        }

        void reloadPlugin(String id, Path path, SwimHost host) {
            if (CORE_PLUGIN_ID.equals(id)) {
                unloadAll();
                loadAll(path == null ? List.of() : List.of(path), host);
                return;
            }
            PluginBinding binding = findBinding(id);
            binding.unload();
            loadPlugin(id, path, host);
        }

        private PluginBinding findBinding(String id) {
            return _bindings.stream()
                    .filter(binding -> binding.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown plugin id: " + id));
        }

        private DefaultPluginContext createContext(List<Path> paths, SwimHost host, String pluginId) {
            List<Path> initialPaths = paths == null ? List.of() : List.copyOf(paths);
            Path initialPath = initialPaths.isEmpty() ? null : initialPaths.getFirst();
            return new DefaultPluginContext(pluginId, host, initialPaths, () -> {
                SwimApp app = app();
                if (app == null) {
                    return initialPath;
                }
                Path currentPath = app.getCurrentPath();
                return currentPath == null ? initialPath : currentPath;
            });
        }
    }

    private static final class DefaultPluginContext implements SwimPluginContext {
        private final String _pluginId;
        private final SwimHost _host;
        private final List<Path> _initialPaths;
        private final Supplier<Path> _currentPathSupplier;

        private DefaultPluginContext(String pluginId, SwimHost host, List<Path> initialPaths, Supplier<Path> currentPathSupplier) {
            _pluginId = pluginId;
            _host = host;
            _initialPaths = initialPaths == null ? List.of() : List.copyOf(initialPaths);
            _currentPathSupplier = currentPathSupplier;
        }

        @Override
        public String getPluginId() {
            return _pluginId;
        }

        @Override
        public SwimHost getHost() {
            return _host;
        }

        @Override
        public Path getInitialPath() {
            return _initialPaths.isEmpty() ? null : _initialPaths.getFirst();
        }

        @Override
        public List<Path> getInitialPaths() {
            return _initialPaths;
        }

        @Override
        public Path getCurrentPath() {
            Path currentPath = _currentPathSupplier.get();
            return currentPath == null ? getInitialPath() : currentPath;
        }
    }

    private static final class CorePluginBinding implements PluginBinding {
        private final ServiceLoader.Provider<SwimApp> _provider;
        private SwimApp _app;
        private boolean _loaded;

        private CorePluginBinding(ServiceLoader.Provider<SwimApp> provider) {
            _provider = provider;
        }

        @Override
        public String id() {
            return CORE_PLUGIN_ID;
        }

        @Override
        public int loadOrder() {
            return 0;
        }

        @Override
        public boolean loadOnStartup() {
            return true;
        }

        @Override
        public void load(DefaultPluginContext context) {
            if (_loaded) {
                return;
            }
            SwimNemoToolRegistry.unregisterPlugin(CORE_PLUGIN_ID);
            SwimApp app = _provider.get();
            _app = app;
            try {
                app.start(context.getInitialPaths(), context.getHost());
                _loaded = true;
            } catch (RuntimeException | Error e) {
                _app = null;
                _loaded = false;
                SwimNemoToolRegistry.unregisterPlugin(CORE_PLUGIN_ID);
                throw e;
            }
        }

        @Override
        public void unload() {
            if (!_loaded) {
                return;
            }
            SwimApp app = _app;
            _app = null;
            _loaded = false;
            try {
                app.close();
            } finally {
                SwimNemoToolRegistry.unregisterPlugin(CORE_PLUGIN_ID);
            }
        }

        @Override
        public boolean isLoaded() {
            return _loaded;
        }

        @Override
        public SwimApp app() {
            return _app;
        }
    }

    private static final class ExtensionPluginBinding implements PluginBinding {
        private final String _id;
        private final int _loadOrder;
        private final boolean _loadOnStartup;
        private final ServiceLoader.Provider<SwimPlugin> _provider;
        private SwimPlugin _instance;
        private boolean _loaded;

        private ExtensionPluginBinding(
                String id,
                int loadOrder,
                boolean loadOnStartup,
                ServiceLoader.Provider<SwimPlugin> provider,
                SwimPlugin instance) {
            _id = id;
            _loadOrder = loadOrder;
            _loadOnStartup = loadOnStartup;
            _provider = provider;
            _instance = instance;
        }

        @Override
        public String id() {
            return _id;
        }

        @Override
        public int loadOrder() {
            return _loadOrder;
        }

        @Override
        public boolean loadOnStartup() {
            return _loadOnStartup;
        }

        @Override
        public void load(DefaultPluginContext context) {
            if (_loaded) {
                return;
            }
            SwimNemoToolRegistry.unregisterPlugin(_id);
            SwimPlugin plugin = _instance == null ? _provider.get() : _instance;
            try {
                plugin.load(context);
                _instance = plugin;
                _loaded = true;
            } catch (RuntimeException | Error e) {
                _instance = null;
                _loaded = false;
                SwimNemoToolRegistry.unregisterPlugin(_id);
                throw e;
            }
        }

        @Override
        public void unload() {
            if (!_loaded) {
                _instance = null;
                SwimNemoToolRegistry.unregisterPlugin(_id);
                return;
            }
            SwimPlugin plugin = _instance;
            _instance = null;
            _loaded = false;
            try {
                plugin.close();
            } finally {
                SwimNemoToolRegistry.unregisterPlugin(_id);
            }
        }

        @Override
        public boolean isLoaded() {
            return _loaded;
        }
    }
}
