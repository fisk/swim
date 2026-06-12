package org.fisk.swim.plugins.treeview;

import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginPreloadContext;

public final class TreeViewPlugin implements SwimPlugin {
    private org.fisk.swim.api.SwimHost _host;

    @Override
    public String getId() {
        return TreeViewPluginSupport.PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return false;
    }

    @Override
    public void preload(SwimPluginPreloadContext context) {
        context.registerHelpChapter(TreeViewPluginHelp.chapter());
        context.registerKeyBinding(new SwimPluginKeyBinding("<SPACE> T", "Workspace", "tree", "tree", "tree"));
    }

    @Override
    public void load(SwimPluginContext context) {
        _host = context.getHost();
        var session = TreeViewPluginSupport.install(context);
        _host.registerPanel(getId(), new TreeViewSwimPanel(session));
    }

    @Override
    public void close() {
        if (_host != null) {
            _host.unregisterPanel(getId());
            _host = null;
        }
        TreeViewPluginSupport.shutdown();
    }
}
