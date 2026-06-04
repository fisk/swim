package org.fisk.swim.slack;

public final class SlackPluginRegistry {
    private static volatile SlackClient _client;

    private SlackPluginRegistry() {
    }

    public static void register(SlackClient client) {
        _client = client;
    }

    public static SlackClient getClient() {
        return _client;
    }

    public static void clear() {
        _client = null;
    }
}
