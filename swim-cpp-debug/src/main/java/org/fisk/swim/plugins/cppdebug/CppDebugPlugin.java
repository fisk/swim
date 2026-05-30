package org.fisk.swim.plugins.cppdebug;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;

public final class CppDebugPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return CppDebugPluginSupport.PLUGIN_ID;
    }

    @Override
    public void load(SwimPluginContext context) {
        CppDebugPluginSupport.install();
    }

    @Override
    public void close() {
        CppDebugPluginSupport.shutdown();
    }
}
