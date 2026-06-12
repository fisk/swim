package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class UiThemeTest {
    @AfterEach
    void tearDown() {
        UiTheme.reset();
    }

    @Test
    void appliesConfiguredThemeColorsByRole() {
        UiTheme.applyColors(Map.of(
                "mode.normal", "#ff00ff",
                "TEXT_PRIMARY", "accent.gold",
                "unknown.role", "#ffffff",
                "accent.blue", "not-a-color"));

        assertEquals(TextColor.Factory.fromString("#ff00ff"), UiTheme.MODE_NORMAL);
        assertEquals(UiTheme.ACCENT_GOLD, UiTheme.TEXT_PRIMARY);
        assertEquals(TextColor.Factory.fromString("#5ec4ff"), UiTheme.ACCENT_BLUE);
    }

    @Test
    void resolvesLiteralColorsAndThemeRoleStrings() {
        UiTheme.applyColors(Map.of(
                "diff.added.background", "#112233",
                "git.commit.hash", "diff.added.background"));

        assertEquals(TextColor.Factory.fromString("#abcdef"),
                UiTheme.resolve("#abcdef", UiTheme.TEXT_PRIMARY));
        assertEquals(TextColor.Factory.fromString("#112233"),
                UiTheme.resolve("$git.commit.hash", UiTheme.TEXT_PRIMARY));
        assertEquals(UiTheme.TEXT_PRIMARY, UiTheme.resolve("missing.role", UiTheme.TEXT_PRIMARY));
    }

    @Test
    void supportsCustomPluginRolesFromUserTheme() {
        UiTheme.applyColors(Map.of(
                "plugin.example.accent", "#123456",
                "plugin.example.warning", "diagnostic.warning.foreground",
                "plugin.example.invalid", "not-a-color"));

        assertEquals(TextColor.Factory.fromString("#123456"),
                UiTheme.resolve("plugin.example.accent", UiTheme.TEXT_PRIMARY));
        assertEquals(UiTheme.DIAGNOSTIC_WARNING_FOREGROUND,
                UiTheme.resolve("plugin.example.warning", UiTheme.TEXT_PRIMARY));
        assertEquals(UiTheme.TEXT_PRIMARY,
                UiTheme.resolve("plugin.example.invalid", UiTheme.TEXT_PRIMARY));
    }
}
