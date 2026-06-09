package org.fisk.swim.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SwimPluginKeyBindingRegistry {
    private static final Object LOCK = new Object();
    private static final Map<String, List<RegisteredKeyBinding>> BINDINGS_BY_KEY = new LinkedHashMap<>();
    private static final Map<String, List<RegisteredKeyBinding>> BINDINGS_BY_PLUGIN = new LinkedHashMap<>();

    private SwimPluginKeyBindingRegistry() {
    }

    public static AutoCloseable register(String pluginId, SwimPluginKeyBinding binding) {
        Objects.requireNonNull(binding, "binding");
        String owner = normalizePluginId(pluginId);
        RegisteredKeyBinding registered = new RegisteredKeyBinding(owner, binding);
        synchronized (LOCK) {
            unregister(owner, binding.key());
            BINDINGS_BY_KEY.computeIfAbsent(binding.key(), ignored -> new ArrayList<>()).add(registered);
            BINDINGS_BY_PLUGIN.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(registered);
        }
        return () -> unregister(registered);
    }

    public static List<SwimPluginKeyBindingDescriptor> listBindings() {
        synchronized (LOCK) {
            return BINDINGS_BY_KEY.values().stream()
                    .flatMap(List::stream)
                    .map(RegisteredKeyBinding::descriptor)
                    .toList();
        }
    }

    public static void unregisterPlugin(String pluginId) {
        String owner = normalizePluginId(pluginId);
        synchronized (LOCK) {
            List<RegisteredKeyBinding> bindings = BINDINGS_BY_PLUGIN.remove(owner);
            if (bindings == null) {
                return;
            }
            for (RegisteredKeyBinding binding : bindings) {
                removeFromKeyIndex(binding);
            }
        }
    }

    public static void clearForTests() {
        clear();
    }

    public static void clear() {
        synchronized (LOCK) {
            BINDINGS_BY_KEY.clear();
            BINDINGS_BY_PLUGIN.clear();
        }
    }

    private static void unregister(RegisteredKeyBinding registered) {
        synchronized (LOCK) {
            removeFromKeyIndex(registered);
            List<RegisteredKeyBinding> bindings = BINDINGS_BY_PLUGIN.get(registered.pluginId());
            if (bindings != null) {
                bindings.remove(registered);
                if (bindings.isEmpty()) {
                    BINDINGS_BY_PLUGIN.remove(registered.pluginId());
                }
            }
        }
    }

    private static void unregister(String pluginId, String key) {
        List<RegisteredKeyBinding> bindings = BINDINGS_BY_PLUGIN.get(pluginId);
        if (bindings == null) {
            return;
        }
        List<RegisteredKeyBinding> matches = bindings.stream()
                .filter(binding -> key.equals(binding.binding().key()))
                .toList();
        for (RegisteredKeyBinding binding : matches) {
            removeFromKeyIndex(binding);
            bindings.remove(binding);
        }
        if (bindings.isEmpty()) {
            BINDINGS_BY_PLUGIN.remove(pluginId);
        }
    }

    private static void removeFromKeyIndex(RegisteredKeyBinding registered) {
        List<RegisteredKeyBinding> bindings = BINDINGS_BY_KEY.get(registered.binding().key());
        if (bindings == null) {
            return;
        }
        bindings.remove(registered);
        if (bindings.isEmpty()) {
            BINDINGS_BY_KEY.remove(registered.binding().key());
        }
    }

    private static String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return "plugin";
        }
        return pluginId.strip();
    }

    private record RegisteredKeyBinding(String pluginId, SwimPluginKeyBinding binding) {
        private SwimPluginKeyBindingDescriptor descriptor() {
            return new SwimPluginKeyBindingDescriptor(
                    pluginId,
                    binding.key(),
                    binding.group(),
                    binding.summary(),
                    binding.commandName(),
                    binding.command(),
                    binding.available(),
                    binding.action());
        }
    }
}
