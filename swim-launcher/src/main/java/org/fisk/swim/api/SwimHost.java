package org.fisk.swim.api;

import java.nio.file.Path;

public interface SwimHost {
    void requestReload(Path path);
    void requestRebuildAndReload(Path path);
    void requestLoadPlugin(String pluginId, Path path);
    default void registerPanel(String pluginId, SwimPanel panel) {
    }
    default void unregisterPanel(String pluginId) {
    }
    default SwimPanel getPanel(String pluginId) {
        return null;
    }
    default boolean isReloading() {
        return false;
    }
    void requestExit();
    Path getBuildRoot();
}
