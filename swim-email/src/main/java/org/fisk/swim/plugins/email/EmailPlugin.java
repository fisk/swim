package org.fisk.swim.plugins.email;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;

public final class EmailPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return "swim-email";
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void load(SwimPluginContext context) {
        EmailPluginSupport.install(context);
    }

    @Override
    public void close() {
        EmailPluginSupport.shutdown();
    }
}
