package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.fisk.swim.text.AttributedString;

public final class LanguagePluginRegistry {
    @FunctionalInterface
    public interface LanguageModeFactory {
        LanguageMode create(Path path);
    }

    public record Registration(String extension, String pluginId, LanguageModeFactory factory) {
    }

    private record PathRegistration(String pluginId, Predicate<Path> matcher, LanguageModeFactory factory) {
    }

    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<PathRegistration> PATH_REGISTRATIONS = new CopyOnWriteArrayList<>();

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

    /** Registers a language mode for paths identified by project-local context rather than extension alone. */
    public static AutoCloseable registerPathMatcher(String pluginId, Predicate<Path> matcher, LanguageModeFactory factory) {
        if (matcher == null || factory == null) {
            throw new IllegalArgumentException("Language plugin path registration requires a matcher and factory");
        }
        var registration = new PathRegistration(pluginId, matcher, factory);
        PATH_REGISTRATIONS.add(registration);
        return () -> PATH_REGISTRATIONS.remove(registration);
    }

    public static void unregisterPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        REGISTRATIONS.entrySet().removeIf(entry -> pluginId.equals(entry.getValue().pluginId()));
        PATH_REGISTRATIONS.removeIf(entry -> pluginId.equals(entry.pluginId()));
    }

    public static void clearForTests() {
        REGISTRATIONS.entrySet().removeIf(entry -> entry.getValue().pluginId() != null);
        PATH_REGISTRATIONS.removeIf(entry -> entry.pluginId() != null);
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
        Registration extensionRegistration = REGISTRATIONS.get(normalizeExtension(fileName.substring(index + 1)));
        if (extensionRegistration != null) return extensionRegistration;
        for (PathRegistration registration : PATH_REGISTRATIONS) {
            if (registration.matcher().test(path)) {
                return new Registration("", registration.pluginId(), registration.factory());
            }
        }
        return null;
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
