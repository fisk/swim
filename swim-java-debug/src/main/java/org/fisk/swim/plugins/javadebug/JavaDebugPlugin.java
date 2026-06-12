package org.fisk.swim.plugins.javadebug;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginPreloadContext;

public final class JavaDebugPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return JavaDebugPluginSupport.PLUGIN_ID;
    }

    @Override
    public void preload(SwimPluginPreloadContext context) {
        context.registerHelpChapter(JavaDebugPluginHelp.chapter());
    }

    @Override
    public void load(SwimPluginContext context) {
        JavaDebugPluginSupport.install();
    }

    @Override
    public void close() {
        JavaDebugPluginSupport.shutdown();
    }
}
