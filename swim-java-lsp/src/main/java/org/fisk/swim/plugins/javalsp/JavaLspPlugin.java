package org.fisk.swim.plugins.javalsp;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.lsp.java.JavaLspPluginSupport;

public final class JavaLspPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return JavaLspPluginSupport.PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void load(SwimPluginContext context) {
        JavaLspPluginSupport.install();
    }

    @Override
    public void close() {
        JavaLspPluginSupport.shutdown();
    }
}
