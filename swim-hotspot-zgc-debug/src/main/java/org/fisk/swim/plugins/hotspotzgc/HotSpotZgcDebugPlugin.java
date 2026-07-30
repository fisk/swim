package org.fisk.swim.plugins.hotspotzgc;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.debug.DebuggerCommandExtensionRegistry;

/** Debugger support for the current mainline HotSpot generational ZGC layout. */
public final class HotSpotZgcDebugPlugin implements SwimPlugin {
    public static final String PLUGIN_ID = "swim-hotspot-zgc-debug";

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public void load(SwimPluginContext context) {
        DebuggerCommandExtensionRegistry.register(PLUGIN_ID, new ZgcDebuggerCommand());
    }

    @Override
    public void close() {
        DebuggerCommandExtensionRegistry.unregisterPlugin(PLUGIN_ID);
    }
}
