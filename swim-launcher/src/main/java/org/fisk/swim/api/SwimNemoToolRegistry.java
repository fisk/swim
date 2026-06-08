package org.fisk.swim.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SwimNemoToolRegistry {
    private static final Object LOCK = new Object();
    private static final Map<String, RegisteredTool> TOOLS_BY_EXPOSED_NAME = new LinkedHashMap<>();
    private static final Map<String, List<String>> EXPOSED_NAMES_BY_PLUGIN = new LinkedHashMap<>();

    private SwimNemoToolRegistry() {
    }

    public static AutoCloseable register(String pluginId, SwimNemoTool tool) {
        Objects.requireNonNull(tool, "tool");
        String owner = normalizePluginId(pluginId);
        String toolName = requireToolName(tool.getName());
        String exposedName = exposedName(owner, toolName);
        synchronized (LOCK) {
            if (TOOLS_BY_EXPOSED_NAME.containsKey(exposedName)) {
                throw new IllegalArgumentException("Duplicate Nemo plugin tool: " + exposedName);
            }
            RegisteredTool registered = new RegisteredTool(owner, toolName, exposedName, tool);
            TOOLS_BY_EXPOSED_NAME.put(exposedName, registered);
            EXPOSED_NAMES_BY_PLUGIN.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(exposedName);
        }
        return () -> unregister(exposedName);
    }

    public static void unregister(String exposedName) {
        if (exposedName == null || exposedName.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            RegisteredTool removed = TOOLS_BY_EXPOSED_NAME.remove(exposedName);
            if (removed == null) {
                return;
            }
            List<String> names = EXPOSED_NAMES_BY_PLUGIN.get(removed.pluginId());
            if (names != null) {
                names.remove(exposedName);
                if (names.isEmpty()) {
                    EXPOSED_NAMES_BY_PLUGIN.remove(removed.pluginId());
                }
            }
        }
    }

    public static void unregisterPlugin(String pluginId) {
        String owner = normalizePluginId(pluginId);
        synchronized (LOCK) {
            List<String> names = EXPOSED_NAMES_BY_PLUGIN.remove(owner);
            if (names == null) {
                return;
            }
            for (String name : names) {
                TOOLS_BY_EXPOSED_NAME.remove(name);
            }
        }
    }

    public static List<SwimNemoToolDescriptor> listTools() {
        synchronized (LOCK) {
            return TOOLS_BY_EXPOSED_NAME.values().stream()
                    .map(RegisteredTool::descriptor)
                    .sorted(Comparator.comparing(SwimNemoToolDescriptor::exposedName))
                    .toList();
        }
    }

    public static SwimNemoToolDescriptor findTool(String exposedName) {
        synchronized (LOCK) {
            RegisteredTool tool = TOOLS_BY_EXPOSED_NAME.get(exposedName);
            return tool == null ? null : tool.descriptor();
        }
    }

    public static String execute(String exposedName, SwimNemoToolInvocation invocation) throws Exception {
        RegisteredTool tool;
        synchronized (LOCK) {
            tool = TOOLS_BY_EXPOSED_NAME.get(exposedName);
        }
        if (tool == null) {
            throw new IllegalArgumentException("Unknown Nemo plugin tool: " + exposedName);
        }
        return tool.tool().execute(invocation);
    }

    public static void clearForTests() {
        clear();
    }

    public static void clear() {
        synchronized (LOCK) {
            TOOLS_BY_EXPOSED_NAME.clear();
            EXPOSED_NAMES_BY_PLUGIN.clear();
        }
    }

    private static String exposedName(String pluginId, String toolName) {
        return "plugin__" + sanitize(pluginId) + "__" + sanitize(toolName);
    }

    private static String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return "plugin";
        }
        return pluginId.trim();
    }

    private static String requireToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Nemo plugin tool name is blank");
        }
        return toolName.trim();
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                result.append(Character.toLowerCase(c));
            } else {
                result.append('_');
            }
        }
        while (result.length() > 0 && result.charAt(0) == '_') {
            result.deleteCharAt(0);
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '_') {
            result.deleteCharAt(result.length() - 1);
        }
        return result.isEmpty() ? "tool" : result.toString();
    }

    private record RegisteredTool(String pluginId, String toolName, String exposedName, SwimNemoTool tool) {
        private SwimNemoToolDescriptor descriptor() {
            return new SwimNemoToolDescriptor(
                    exposedName,
                    pluginId,
                    toolName,
                    safeDescription(),
                    safeInputSchemaJson(),
                    safeAvailableInReadOnly(),
                    safeRequiresApproval());
        }

        private String safeDescription() {
            try {
                return safe(tool.getDescription());
            } catch (RuntimeException e) {
                return "";
            }
        }

        private String safeInputSchemaJson() {
            try {
                return safe(tool.getInputSchemaJson());
            } catch (RuntimeException e) {
                return "";
            }
        }

        private boolean safeAvailableInReadOnly() {
            try {
                return tool.availableInReadOnly();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean safeRequiresApproval() {
            try {
                return tool.requiresApproval();
            } catch (RuntimeException e) {
                return true;
            }
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
