package org.fisk.swim.config;

import java.util.Map;

public record ThemeConfig(
        String name,
        Map<String, String> colors) {
    public ThemeConfig {
        name = name == null || name.isBlank() ? "custom" : name.trim();
        colors = colors == null ? Map.of() : Map.copyOf(colors);
    }

    public static ThemeConfig empty() {
        return new ThemeConfig("default", Map.of());
    }
}
