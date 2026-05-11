package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import org.fisk.swim.api.SwimPluginContext;

public final class TreeViewPluginSupport {
    public static final String PLUGIN_ID = "swim-tree-view";

    private static TreeViewPluginSession _session;

    private TreeViewPluginSupport() {
    }

    public static synchronized TreeViewPluginSession install(SwimPluginContext context) {
        shutdown();
        try {
            _session = new TreeViewPluginSession(context);
            return _session;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize tree view plugin", e);
        }
    }

    public static synchronized Optional<TreeViewPluginSession> getSession() {
        return Optional.ofNullable(_session);
    }

    public static synchronized void shutdown() {
        if (_session != null) {
            _session.close();
            _session = null;
        }
    }
}
