package org.fisk.swim.api;

public interface SwimPluginPreloadContext {
    String getPluginId();

    default AutoCloseable registerPreloadResource(AutoCloseable resource) {
        return SwimPluginPreloadRegistry.register(getPluginId(), resource);
    }

    default AutoCloseable registerHelpChapter(SwimHelpChapter chapter) {
        return registerPreloadResource(SwimHelpRegistry.register(getPluginId(), chapter));
    }

    default AutoCloseable registerKeyBinding(SwimPluginKeyBinding binding) {
        return registerPreloadResource(SwimPluginKeyBindingRegistry.register(getPluginId(), binding));
    }
}
