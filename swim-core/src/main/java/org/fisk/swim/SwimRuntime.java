package org.fisk.swim;

import java.nio.file.Path;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.ui.Window;

public final class SwimRuntime {
    private static SwimHost _host;

    private SwimRuntime() {
    }

    public static void setHost(SwimHost host) {
        _host = host;
    }

    public static void clear() {
        _host = null;
    }

    public static void exit() {
        if (_host != null) {
            _host.requestExit();
        } else {
            System.exit(0);
        }
    }

    private static Path currentPath() {
        return Window.getInstance().getBufferContext().getBuffer().getPath();
    }

    public static void reload() {
        if (_host != null) {
            _host.requestReload(currentPath());
        }
    }

    public static void rebuildAndReload() {
        if (_host != null) {
            _host.requestRebuildAndReload(currentPath());
        }
    }
}
