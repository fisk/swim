package org.fisk.swim.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SwimPluginPreloadRegistry {
    private static final Object LOCK = new Object();
    private static final Map<String, List<TrackedResource>> RESOURCES_BY_PLUGIN = new LinkedHashMap<>();

    private SwimPluginPreloadRegistry() {
    }

    public static AutoCloseable register(String pluginId, AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        String owner = normalizePluginId(pluginId);
        var tracked = new TrackedResource(owner, resource);
        synchronized (LOCK) {
            RESOURCES_BY_PLUGIN.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(tracked);
        }
        return tracked;
    }

    public static void unregisterPlugin(String pluginId) {
        String owner = normalizePluginId(pluginId);
        List<TrackedResource> resources;
        synchronized (LOCK) {
            resources = RESOURCES_BY_PLUGIN.remove(owner);
        }
        closeReverse(resources);
    }

    public static void clearForTests() {
        clear();
    }

    public static void clear() {
        List<TrackedResource> resources = new ArrayList<>();
        synchronized (LOCK) {
            for (var pluginResources : RESOURCES_BY_PLUGIN.values()) {
                resources.addAll(pluginResources);
            }
            RESOURCES_BY_PLUGIN.clear();
        }
        closeReverse(resources);
    }

    private static void unregister(TrackedResource tracked) {
        synchronized (LOCK) {
            List<TrackedResource> resources = RESOURCES_BY_PLUGIN.get(tracked.pluginId());
            if (resources == null) {
                return;
            }
            resources.remove(tracked);
            if (resources.isEmpty()) {
                RESOURCES_BY_PLUGIN.remove(tracked.pluginId());
            }
        }
    }

    private static void closeReverse(List<TrackedResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        RuntimeException failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception e) {
                RuntimeException runtime = e instanceof RuntimeException r ? r : new RuntimeException(e);
                if (failure == null) {
                    failure = runtime;
                } else {
                    failure.addSuppressed(runtime);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return "plugin";
        }
        return pluginId.strip();
    }

    private static final class TrackedResource implements AutoCloseable {
        private final String _pluginId;
        private final AutoCloseable _resource;
        private boolean _closed;

        private TrackedResource(String pluginId, AutoCloseable resource) {
            _pluginId = pluginId;
            _resource = resource;
        }

        private String pluginId() {
            return _pluginId;
        }

        @Override
        public void close() throws Exception {
            synchronized (this) {
                if (_closed) {
                    return;
                }
                _closed = true;
            }
            unregister(this);
            _resource.close();
        }
    }
}
