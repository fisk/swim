package org.fisk.swim.config;

import java.util.List;
import java.util.Map;

public record EditorConfig(
        List<NormalModeRemap> normalModeRemaps,
        List<String> startupCommands,
        Map<String, String> options,
        ThemeConfig theme,
        boolean restoreLastSession) {
    public EditorConfig {
        normalModeRemaps = normalModeRemaps == null ? List.of() : List.copyOf(normalModeRemaps);
        startupCommands = startupCommands == null ? List.of() : List.copyOf(startupCommands);
        options = options == null ? Map.of() : Map.copyOf(options);
        theme = theme == null ? ThemeConfig.empty() : theme;
    }

    public static EditorConfig empty() {
        return new EditorConfig(List.of(), List.of(), Map.of(), ThemeConfig.empty(), false);
    }
}
