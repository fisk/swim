package org.fisk.swim.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DebuggerProviderRegistry {
    public record Registration(String providerId, String pluginId, DebuggerProvider provider) {
    }

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    private DebuggerProviderRegistry() {
    }

    public static void register(String providerId, String pluginId, DebuggerProvider provider) {
        if (providerId == null || providerId.isBlank() || provider == null) {
            throw new IllegalArgumentException("Debugger registration requires a provider id and provider");
        }
        REGISTRATIONS.put(providerId, new Registration(providerId, pluginId, provider));
    }

    public static Registration find(String providerId) {
        return providerId == null ? null : REGISTRATIONS.get(providerId);
    }

    public static java.util.List<Registration> list() {
        var result = new ArrayList<>(REGISTRATIONS.values());
        result.sort(Comparator.comparing(Registration::providerId));
        return result;
    }

    public static void unregisterPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        REGISTRATIONS.entrySet().removeIf(entry -> pluginId.equals(entry.getValue().pluginId()));
    }
}
