package org.fisk.swim.api;

public interface SwimPluginPreloadContext {
    String getPluginId();

    default AutoCloseable registerHelpChapter(SwimHelpChapter chapter) {
        return SwimHelpRegistry.register(getPluginId(), chapter);
    }
}
