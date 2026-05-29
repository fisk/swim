package org.fisk.swim;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.ui.Window;

public final class SwimRuntime {
    private static SwimHost _host;
    private static Supplier<Path> _currentPathSupplier = SwimRuntime::resolveCurrentPath;

    private SwimRuntime() {
    }

    public static void setHost(SwimHost host) {
        _host = host;
    }

    public static void clear() {
        _host = null;
        _currentPathSupplier = SwimRuntime::resolveCurrentPath;
    }

    public static void exit() {
        if (_host != null) {
            _host.requestExit();
        } else {
            System.exit(0);
        }
    }

    private static Path resolveCurrentPath() {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null || window.getBufferContext().getBuffer() == null) {
            return null;
        }
        return window.getBufferContext().getBuffer().getPath();
    }

    static void setCurrentPathSupplier(Supplier<Path> currentPathSupplier) {
        _currentPathSupplier = Objects.requireNonNull(currentPathSupplier);
    }

    static void resetCurrentPathSupplier() {
        _currentPathSupplier = SwimRuntime::resolveCurrentPath;
    }

    public static void reload() {
        if (_host != null) {
            _host.requestReload(_currentPathSupplier.get());
        }
    }

    public static void rebuildAndReload() {
        if (_host != null) {
            _host.requestRebuildAndReload(_currentPathSupplier.get());
        }
    }

    public static void loadPlugin(String pluginId) {
        loadPlugin(pluginId, _currentPathSupplier.get());
    }

    public static void loadPlugin(String pluginId, Path path) {
        if (_host != null) {
            _host.requestLoadPlugin(pluginId, path);
        }
    }

    public static SwimPanel getPanel(String pluginId) {
        if (_host == null) {
            return null;
        }
        return _host.getPanel(pluginId);
    }

    public static boolean isReloading() {
        return _host != null && _host.isReloading();
    }
}
