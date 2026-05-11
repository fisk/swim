package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LanguagePluginRegistry {
    @FunctionalInterface
    public interface LanguageModeFactory {
        LanguageMode create(Path path);
    }

    public record Registration(String extension, String pluginId, LanguageModeFactory factory) {
    }

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    private LanguagePluginRegistry() {
    }

    public static void register(String extension, String pluginId, LanguageModeFactory factory) {
        if (extension == null || extension.isBlank() || factory == null) {
            throw new IllegalArgumentException("Language plugin registration requires an extension and factory");
        }
        String normalizedExtension = normalizeExtension(extension);
        REGISTRATIONS.put(normalizedExtension, new Registration(normalizedExtension, pluginId, factory));
    }

    public static void unregisterPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        REGISTRATIONS.entrySet().removeIf(entry -> pluginId.equals(entry.getValue().pluginId()));
    }

    public static Registration find(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return null;
        }
        return REGISTRATIONS.get(normalizeExtension(fileName.substring(index + 1)));
    }

    private static String normalizeExtension(String extension) {
        return extension.toLowerCase(java.util.Locale.ROOT);
    }
}
