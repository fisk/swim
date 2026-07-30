package org.fisk.swim.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry for debugger commands supplied by independently loaded plugins. */
public final class DebuggerCommandExtensionRegistry {
    public record Registration(String pluginId, DebuggerCommandExtension extension) {
    }

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    private DebuggerCommandExtensionRegistry() {
    }

    public static void register(String pluginId, DebuggerCommandExtension extension) {
        if (pluginId == null || pluginId.isBlank() || extension == null || extension.id().isBlank()) {
            throw new IllegalArgumentException("Debugger command registration requires a plugin id and extension id");
        }
        REGISTRATIONS.put(pluginId + ":" + extension.id(), new Registration(pluginId, extension));
    }

    public static DebuggerCommandExtension find(String command) {
        return list().stream().map(Registration::extension).filter(extension -> extension.handles(command)).findFirst().orElse(null);
    }

    public static List<Registration> list() {
        var result = new ArrayList<>(REGISTRATIONS.values());
        result.sort(Comparator.comparing(registration -> registration.extension().id()));
        return result;
    }

    public static void unregisterPlugin(String pluginId) {
        REGISTRATIONS.entrySet().removeIf(entry -> pluginId != null && pluginId.equals(entry.getValue().pluginId()));
    }
}
