package org.fisk.swim.api;

import java.nio.file.Path;

public interface SwimPluginContext {
    SwimHost getHost();
    Path getInitialPath();
    Path getCurrentPath();

    default String getPluginId() {
        return getClass().getName();
    }

    default AutoCloseable registerNemoTool(SwimNemoTool tool) {
        return SwimNemoToolRegistry.register(getPluginId(), tool);
    }
}
