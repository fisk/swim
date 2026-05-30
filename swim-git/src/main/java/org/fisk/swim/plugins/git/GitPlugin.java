package org.fisk.swim.plugins.git;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;

public final class GitPlugin implements SwimPlugin {
    private org.fisk.swim.api.SwimHost _host;

    @Override
    public String getId() {
        return GitPluginSupport.PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void load(SwimPluginContext context) {
        _host = context.getHost();
        _host.registerPanel(getId(), new GitSwimPanel(GitPluginSupport.install(context)));
    }

    @Override
    public void close() {
        if (_host != null) {
            _host.unregisterPanel(getId());
            _host = null;
        }
        GitPluginSupport.shutdown();
    }
}
