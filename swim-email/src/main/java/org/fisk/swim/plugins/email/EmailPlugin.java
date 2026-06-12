package org.fisk.swim.plugins.email;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginPreloadContext;

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
    public void preload(SwimPluginPreloadContext context) {
        context.registerHelpChapter(EmailPluginHelp.chapter());
        context.registerKeyBinding(new SwimPluginKeyBinding("<SPACE> m", "Workspace", "mail", "mail", "mail"));
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
