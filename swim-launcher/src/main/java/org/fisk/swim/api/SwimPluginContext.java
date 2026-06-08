package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

public interface SwimPluginContext {
    SwimHost getHost();
    Path getInitialPath();

    default List<Path> getInitialPaths() {
        Path path = getInitialPath();
        return path == null ? List.of() : List.of(path);
    }

    Path getCurrentPath();

    default String getPluginId() {
        return getClass().getName();
    }

    default AutoCloseable registerNemoTool(SwimNemoTool tool) {
        return SwimNemoToolRegistry.register(getPluginId(), tool);
    }
}
