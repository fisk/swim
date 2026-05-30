package org.fisk.swim.plugins.git;

import java.util.Optional;

import org.fisk.swim.api.SwimPluginContext;

public final class GitPluginSupport {
    public static final String PLUGIN_ID = "swim-git";

    private static GitPluginSession _session;

    private GitPluginSupport() {
    }

    public static synchronized GitPluginSession install(SwimPluginContext context) {
        shutdown();
        _session = new GitPluginSession(context);
        return _session;
    }

    public static synchronized Optional<GitPluginSession> getSession() {
        return Optional.ofNullable(_session);
    }

    public static synchronized void shutdown() {
        if (_session != null) {
            _session.close();
            _session = null;
        }
    }
}
