package org.fisk.swim.api;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class SwimCommandRegistry {
    public record Registration(String pluginId, SwimCommand command) { }
    private static final ConcurrentHashMap<String, Registration> COMMANDS = new ConcurrentHashMap<>();
    private SwimCommandRegistry() { }
    public static AutoCloseable register(String pluginId, SwimCommand command) {
        if (pluginId == null || pluginId.isBlank() || command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("Plugin command requires a plugin id and name");
        }
        String key = command.name().trim().toLowerCase();
        Registration registration = new Registration(pluginId, command);
        if (COMMANDS.putIfAbsent(key, registration) != null) throw new IllegalArgumentException("Duplicate plugin command: " + key);
        return () -> COMMANDS.remove(key, registration);
    }
    public static Registration find(String name) { return name == null ? null : COMMANDS.get(name.toLowerCase()); }
    public static List<Registration> list() { return COMMANDS.values().stream().sorted(Comparator.comparing(r -> r.command().name())).toList(); }
    public static void unregisterPlugin(String pluginId) { COMMANDS.entrySet().removeIf(e -> pluginId.equals(e.getValue().pluginId())); }
}
