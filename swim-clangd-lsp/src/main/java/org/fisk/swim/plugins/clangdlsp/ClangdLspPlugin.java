package org.fisk.swim.plugins.clangdlsp;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.lsp.cpp.ClangdLspPluginSupport;

public final class ClangdLspPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return ClangdLspPluginSupport.PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void load(SwimPluginContext context) {
        ClangdLspPluginSupport.install();
    }

    @Override
    public void close() {
        ClangdLspPluginSupport.shutdown();
    }
}
