package org.fisk.swim.plugins.jfrmetrics;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginPreloadContext;

public final class JfrPlugin implements SwimPlugin {
    static final String PLUGIN_ID = "swim-jfr";
    private SwimHost host;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void preload(SwimPluginPreloadContext context) {
        context.registerKeyBinding(new SwimPluginKeyBinding("<SPACE> j", "Workspace",
                "JFR recording metrics", "jfr", "jfr"));
    }

    @Override
    public void load(SwimPluginContext context) {
        host = context.getHost();
        host.registerPanel(getId(), new JfrPanel(context.getCurrentPath()));
    }

    @Override
    public void close() {
        if (host != null) {
            host.unregisterPanel(getId());
        }
        host = null;
    }
}
