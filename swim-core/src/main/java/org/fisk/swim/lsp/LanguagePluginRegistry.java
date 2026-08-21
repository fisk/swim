package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.fisk.swim.text.AttributedString;

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

    public static AutoCloseable register(String extension, String pluginId, LanguageModeFactory factory) {
        if (extension == null || extension.isBlank() || factory == null) {
            throw new IllegalArgumentException("Language plugin registration requires an extension and factory");
        }
        String normalizedExtension = normalizeExtension(extension);
        var registration = new Registration(normalizedExtension, pluginId, factory);
        REGISTRATIONS.put(normalizedExtension, registration);
        return () -> REGISTRATIONS.remove(normalizedExtension, registration);
    }

    public static void unregisterPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        REGISTRATIONS.entrySet().removeIf(entry -> pluginId.equals(entry.getValue().pluginId()));
    }

    public static void clearForTests() {
        REGISTRATIONS.entrySet().removeIf(entry -> entry.getValue().pluginId() != null);
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

    /** Applies lexical plugin colouring to standalone text, without a buffer or LSP document. */
    public static boolean applySnippetColouring(String language, AttributedString text) {
        if (language == null || language.isBlank() || text == null) return false;
        String extension = normalizeSnippetLanguage(language);
        Registration registration = REGISTRATIONS.get(extension);
        if (registration == null) return false;
        LanguageMode mode = registration.factory().create(Path.of("snippet." + registration.extension()));
        if (mode == null) return false;
        mode.applyColouring(null, text);
        return true;
    }

    private static String normalizeExtension(String extension) {
        return Objects.requireNonNull(extension, "extension").toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeSnippetLanguage(String language) {
        return switch (normalizeExtension(language)) {
        case "c++", "cxx" -> "cpp";
        case "c#" -> "cs";
        default -> normalizeExtension(language);
        };
    }
}
