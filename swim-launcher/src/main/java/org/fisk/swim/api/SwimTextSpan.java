package org.fisk.swim.api;

/**
 * A styled text span returned by plugin panels.
 *
 * Foreground and background values may be literal Lanterna color strings such
 * as {@code #5ec4ff}, or editor theme role names such as
 * {@code text.primary}, {@code accent.green}, or
 * {@code diff.added.background}. Unknown values are ignored by the host and
 * the panel row fallback color is used instead.
 */
public record SwimTextSpan(String text, String foreground, String background) {
    public SwimTextSpan {
        text = text == null ? "" : text;
        foreground = normalize(foreground);
        background = normalize(background);
    }

    public SwimTextSpan(String text) {
        this(text, null, null);
    }

    public static SwimTextSpan plain(String text) {
        return new SwimTextSpan(text);
    }

    public static SwimTextSpan styled(String text, String foreground, String background) {
        return new SwimTextSpan(text, foreground, background);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
