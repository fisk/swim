package org.fisk.swim.mail;

public final class MailPluginRegistry {
    private static volatile MailClient _client;

    private MailPluginRegistry() {
    }

    public static void register(MailClient client) {
        _client = client;
    }

    public static MailClient getClient() {
        return _client;
    }

    public static void clear() {
        _client = null;
    }
}
