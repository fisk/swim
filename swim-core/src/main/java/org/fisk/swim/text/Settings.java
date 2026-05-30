package org.fisk.swim.text;

public class Settings {
    private static final String DEFAULT_LANGUAGE = "default";

    public static String getIndentationString() {
        return getIndentationString(DEFAULT_LANGUAGE);
    }

    public static String getIndentationString(String languageId) {
        String normalized = normalizeLanguageId(languageId);
        String explicit = System.getProperty("swim.indent." + normalized + ".string", "");
        if (!explicit.isBlank()) {
            return decodeEscapes(explicit);
        }
        int width = Integer.getInteger("swim.indent." + normalized + ".size", defaultIndentWidth(normalized));
        if (width <= 0) {
            width = defaultIndentWidth(normalized);
        }
        return " ".repeat(width);
    }

    private static int defaultIndentWidth(String languageId) {
        return switch (languageId) {
        case "c", "cpp" -> 2;
        case "java" -> 4;
        default -> 4;
        };
    }

    private static String normalizeLanguageId(String languageId) {
        if (languageId == null || languageId.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return languageId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String decodeEscapes(String value) {
        return value
                .replace("\\t", "\t")
                .replace("\\n", "\n");
    }
}
