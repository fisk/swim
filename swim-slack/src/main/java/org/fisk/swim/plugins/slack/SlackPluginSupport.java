package org.fisk.swim.plugins.slack;

import java.io.IOException;

import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.slack.SlackPluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SlackPluginSupport {
    private static final Logger LOG = LoggerFactory.getLogger(SlackPluginSupport.class);
    private static SlackHttpClient _client;

    private SlackPluginSupport() {
    }

    static synchronized void install(SwimPluginContext context) {
        shutdown();
        try {
            _client = new SlackHttpClient(SlackPaths.fromUserHome());
            SlackPluginRegistry.register(_client);
        } catch (IOException e) {
            LOG.error("Failed to initialize Slack plugin", e);
            throw new RuntimeException("Failed to initialize Slack plugin: " + e.getMessage(), e);
        }
    }

    static synchronized void shutdown() {
        SlackPluginRegistry.clear();
        if (_client != null) {
            _client.close();
            _client = null;
        }
    }
}
