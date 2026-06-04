package org.fisk.swim.plugins.slack;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;

public final class SlackPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return "swim-slack";
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void load(SwimPluginContext context) {
        SlackPluginSupport.install(context);
    }

    @Override
    public void close() {
        SlackPluginSupport.shutdown();
    }
}
