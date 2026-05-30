package org.fisk.swim.plugins.javadebug;

import org.fisk.swim.debug.DebuggerProviderRegistry;

public final class JavaDebugPluginSupport {
    public static final String PLUGIN_ID = "swim-java-debug";
    private static final JavaDebuggerProvider PROVIDER = new JavaDebuggerProvider();

    private JavaDebugPluginSupport() {
    }

    public static void install() {
        DebuggerProviderRegistry.register(PROVIDER.id(), PLUGIN_ID, PROVIDER);
    }

    public static void shutdown() {
        DebuggerProviderRegistry.unregisterPlugin(PLUGIN_ID);
    }
}
