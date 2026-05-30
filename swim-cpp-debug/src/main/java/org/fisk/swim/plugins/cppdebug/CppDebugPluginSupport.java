package org.fisk.swim.plugins.cppdebug;

import org.fisk.swim.debug.DebuggerProviderRegistry;

public final class CppDebugPluginSupport {
    public static final String PLUGIN_ID = "swim-cpp-debug";
    private static final CppDebuggerProvider PROVIDER = new CppDebuggerProvider();

    private CppDebugPluginSupport() {
    }

    public static void install() {
        DebuggerProviderRegistry.register(PROVIDER.id(), PLUGIN_ID, PROVIDER);
    }

    public static void shutdown() {
        DebuggerProviderRegistry.unregisterPlugin(PLUGIN_ID);
    }
}
