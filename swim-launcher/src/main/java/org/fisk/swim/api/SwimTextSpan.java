package org.fisk.swim.api;

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
