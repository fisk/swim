package org.fisk.swim.api;

public interface SwimPlugin extends AutoCloseable {
    default String getId() {
        return getClass().getName();
    }

    default int getLoadOrder() {
        return 100;
    }

    default boolean loadOnStartup() {
        return true;
    }

    void load(SwimPluginContext context);

    @Override
    default void close() {
    }
}
