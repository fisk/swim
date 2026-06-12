package org.fisk.swim.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.fisk.swim.config.ThemeConfig;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

public final class UiTheme {
    public static TextColor ROOT_BACKGROUND;
    public static TextColor SURFACE_BACKGROUND;
    public static TextColor SURFACE_ELEVATED;
    public static TextColor SURFACE_ACCENT;
    public static TextColor SURFACE_MUTED;

    public static TextColor TEXT_PRIMARY;
    public static TextColor TEXT_MUTED;
    public static TextColor TEXT_SUBTLE;
    public static TextColor TEXT_ON_ACCENT;

    public static TextColor ACCENT_BLUE;
    public static TextColor ACCENT_GOLD;
    public static TextColor ACCENT_GREEN;
    public static TextColor ACCENT_RED;
    public static TextColor ACCENT_ORANGE;
    public static TextColor ACCENT_PURPLE;
    public static TextColor DIAGNOSTIC_ERROR_FOREGROUND;
    public static TextColor DIAGNOSTIC_ERROR_BACKGROUND;
    public static TextColor DIAGNOSTIC_WARNING_FOREGROUND;
    public static TextColor DIAGNOSTIC_WARNING_BACKGROUND;
    public static TextColor DIFF_ADDED_FOREGROUND;
    public static TextColor DIFF_ADDED_BACKGROUND;
    public static TextColor DIFF_REMOVED_FOREGROUND;
    public static TextColor DIFF_REMOVED_BACKGROUND;

    public static TextColor PANEL_SELECTION_BACKGROUND;
    public static TextColor PANEL_SELECTION_FOREGROUND;
    public static TextColor PANEL_SELECTION_ACCENT;

    public static TextColor MENU_BACKGROUND;
    public static TextColor MENU_SECONDARY_BACKGROUND;
    public static TextColor MENU_SEGMENT_BACKGROUND;
    public static TextColor MENU_CONTEXT_BACKGROUND;
    public static TextColor MENU_ACCENT;
    public static TextColor MENU_CHAIN;
    public static TextColor MENU_HINT;

    public static TextColor COMMAND_BACKGROUND;
    public static TextColor COMMAND_INACTIVE_BACKGROUND;
    public static TextColor COMMAND_FOREGROUND;
    public static TextColor COMMAND_PROMPT;
    public static TextColor COMMAND_SEARCH;
    public static TextColor COMMAND_ERROR;
    public static TextColor COMMAND_SUCCESS;

    public static TextColor COMPLETION_BACKGROUND;
    public static TextColor COMPLETION_FOREGROUND;
    public static TextColor COMPLETION_HEADER_BACKGROUND;
    public static TextColor COMPLETION_HEADER_FOREGROUND;
    public static TextColor COMPLETION_FOOTER_BACKGROUND;
    public static TextColor COMPLETION_FOOTER_FOREGROUND;
    public static TextColor COMPLETION_SELECTION_BACKGROUND;
    public static TextColor COMPLETION_SELECTION_FOREGROUND;
    public static TextColor COMPLETION_ANNOTATION;
    public static TextColor COMPLETION_SOURCE;
    public static TextColor COMPLETION_KIND_FUNCTION;
    public static TextColor COMPLETION_KIND_TYPE;
    public static TextColor COMPLETION_KIND_FIELD;
    public static TextColor COMPLETION_KIND_VARIABLE;
    public static TextColor COMPLETION_KIND_KEYWORD;
    public static TextColor COMPLETION_KIND_DEFAULT;

    public static TextColor CHAT_ME;
    public static TextColor CHAT_NEMO;

    public static TextColor MAIL_HEADER_BACKGROUND;
    public static TextColor MAIL_SECTION_BACKGROUND;
    public static TextColor MAIL_STATUS_BACKGROUND;
    public static TextColor MAIL_UNREAD_FOREGROUND;
    public static TextColor MAIL_TAG_FOREGROUND;
    public static TextColor MAIL_COMPOSE_FIELD_BACKGROUND;

    public static TextColor MODELINE_BACKGROUND;
    public static TextColor MODELINE_FOREGROUND;
    public static TextColor MODE_NORMAL;
    public static TextColor MODE_INPUT;
    public static TextColor MODE_VISUAL;

    public static TextColor VISUAL_SELECTION_FOREGROUND;
    public static TextColor VISUAL_SELECTION_BACKGROUND;
    public static TextColor FANCY_JUMP_FOREGROUND;

    public static TextColor DEBUGGER_HEADER_FOREGROUND;
    public static TextColor DEBUGGER_HEADER_BACKGROUND;
    public static TextColor DEBUGGER_FOREGROUND;
    public static TextColor DEBUGGER_SELECTION_FOREGROUND;
    public static TextColor DEBUGGER_SELECTION_BACKGROUND;

    public static TextColor SEMANTIC_NAMESPACE;
    public static TextColor SEMANTIC_TYPE;
    public static TextColor SEMANTIC_PARAMETER;
    public static TextColor SEMANTIC_MEMBER;
    public static TextColor SEMANTIC_FUNCTION;
    public static TextColor SEMANTIC_COMMENT;
    public static TextColor SEMANTIC_STRING;
    public static TextColor SEMANTIC_NUMBER;
    public static TextColor SEMANTIC_KEYWORD;
    public static TextColor SEMANTIC_READONLY;
    public static TextColor SEMANTIC_MACRO;

    private static final Map<String, String> DEFAULT_COLORS = defaultColors();
    private static final Map<String, Consumer<TextColor>> COLOR_SETTERS = colorSetters();
    private static volatile Map<String, String> _activeColors = DEFAULT_COLORS;

    static {
        reset();
    }

    private UiTheme() {
    }

    public static TextColor color(String hex) {
        return TextColor.Factory.fromString(hex);
    }

    public static synchronized void reset() {
        applyColors(Map.of());
    }

    static synchronized void apply(ThemeConfig theme) {
        applyColors(theme == null ? Map.of() : theme.colors());
    }

    public static synchronized void applyColors(Map<String, String> colors) {
        var rawOverrides = new LinkedHashMap<String, String>();
        if (colors != null) {
            for (var entry : colors.entrySet()) {
                String role = canonicalRole(entry.getKey());
                if (role == null) {
                    continue;
                }
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    rawOverrides.put(role, value.trim());
                }
            }
        }

        var rawSpecs = new LinkedHashMap<>(DEFAULT_COLORS);
        rawSpecs.putAll(rawOverrides);
        var resolved = new LinkedHashMap<String, String>();
        for (String role : rawSpecs.keySet()) {
            String colorSpec = resolveColorSpec(role, rawSpecs, new LinkedHashMap<>());
            if (colorSpec != null) {
                resolved.put(role, colorSpec);
            }
        }
        for (var entry : COLOR_SETTERS.entrySet()) {
            entry.getValue().accept(color(resolved.get(entry.getKey())));
        }
        _activeColors = Map.copyOf(resolved);
    }

    public static TextColor role(String role) {
        String colorSpec = colorSpec(role);
        return colorSpec == null ? null : color(colorSpec);
    }

    public static String colorSpec(String role) {
        String canonical = canonicalRole(role);
        return canonical == null ? null : _activeColors.get(canonical);
    }

    public static TextColor resolve(String value, TextColor fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        TextColor direct = tryColor(trimmed);
        if (direct != null) {
            return direct;
        }
        TextColor themed = role(trimmed);
        return themed == null ? fallback : themed;
    }

    public static Map<String, String> activeColorSpecs() {
        return _activeColors;
    }

    public static Map<String, String> defaultColorSpecs() {
        return DEFAULT_COLORS;
    }

    private static String resolveColorSpec(String role, Map<String, String> rawSpecs, Map<String, Boolean> visiting) {
        if (Boolean.TRUE.equals(visiting.get(role))) {
            return DEFAULT_COLORS.get(role);
        }
        String raw = rawSpecs.get(role);
        if (tryColor(raw) != null) {
            return normalizeColorSpec(raw);
        }
        String referencedRole = canonicalRole(raw);
        if (referencedRole == null || !rawSpecs.containsKey(referencedRole)) {
            return DEFAULT_COLORS.get(role);
        }
        visiting.put(role, true);
        String result = resolveColorSpec(referencedRole, rawSpecs, visiting);
        visiting.remove(role);
        return result == null ? DEFAULT_COLORS.get(role) : result;
    }

    private static TextColor tryColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TextColor.Factory.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeColorSpec(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("#") ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private static String canonicalRole(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String role = value.trim();
        while (role.startsWith("$")) {
            role = role.substring(1);
        }
        role = role.toLowerCase(Locale.ROOT)
                .replace('-', '.')
                .replace('_', '.');
        if (role.startsWith("theme.")) {
            role = role.substring("theme.".length());
        }
        if (role.startsWith("ui.")) {
            role = role.substring("ui.".length());
        }
        return role;
    }

    private static Map<String, String> defaultColors() {
        var colors = new LinkedHashMap<String, String>();
        colors.put("root.background", "#0b1117");
        colors.put("surface.background", "#111a23");
        colors.put("surface.elevated", "#15202b");
        colors.put("surface.accent", "#1b2a38");
        colors.put("surface.muted", "#101821");
        colors.put("text.primary", "#dce6ef");
        colors.put("text.muted", "#8ca1b3");
        colors.put("text.subtle", "#61788d");
        colors.put("text.on.accent", "#f7fbff");
        colors.put("accent.blue", "#5ec4ff");
        colors.put("accent.gold", "#ffb454");
        colors.put("accent.green", "#7ee787");
        colors.put("accent.red", "#ff7b72");
        colors.put("accent.orange", "#ff9e64");
        colors.put("accent.purple", "#d2a8ff");
        colors.put("diagnostic.error.foreground", "#ff6b6b");
        colors.put("diagnostic.error.background", "#4a2020");
        colors.put("diagnostic.warning.foreground", "#ffe66d");
        colors.put("diagnostic.warning.background", "#4a3f1a");
        colors.put("diff.added.foreground", "#d9ffe2");
        colors.put("diff.added.background", "#16351f");
        colors.put("diff.removed.foreground", "#ffd8d8");
        colors.put("diff.removed.background", "#3a1d22");
        colors.put("panel.selection.background", "#20405a");
        colors.put("panel.selection.foreground", "#f8fbff");
        colors.put("panel.selection.accent", "#ffb454");
        colors.put("menu.background", "#0d151d");
        colors.put("menu.secondary.background", "#101b25");
        colors.put("menu.segment.background", "#15222d");
        colors.put("menu.context.background", "#1d2d3a");
        colors.put("menu.accent", "#5ec4ff");
        colors.put("menu.chain", "#ffb454");
        colors.put("menu.hint", "#7ee787");
        colors.put("command.background", "#0f1822");
        colors.put("command.inactive.background", "#0c131b");
        colors.put("command.foreground", "#dce6ef");
        colors.put("command.prompt", "#5ec4ff");
        colors.put("command.search", "#ffb454");
        colors.put("command.error", "#ff7b72");
        colors.put("command.success", "#7ee787");
        colors.put("completion.background", "#0f1822");
        colors.put("completion.foreground", "#dce6ef");
        colors.put("completion.header.background", "#5ec4ff");
        colors.put("completion.header.foreground", "#0b1117");
        colors.put("completion.footer.background", "#7ee787");
        colors.put("completion.footer.foreground", "#0b1117");
        colors.put("completion.selection.background", "#f7fbff");
        colors.put("completion.selection.foreground", "#0b1117");
        colors.put("completion.annotation", "#5ec4ff");
        colors.put("completion.source", "#7ee787");
        colors.put("completion.kind.function", "#5ec4ff");
        colors.put("completion.kind.type", "#7ee787");
        colors.put("completion.kind.field", "#ffb454");
        colors.put("completion.kind.variable", "#d2a8ff");
        colors.put("completion.kind.keyword", "#ff7b72");
        colors.put("completion.kind.default", "#5ec4ff");
        colors.put("chat.me", "#ffb86b");
        colors.put("chat.nemo", "#7ee787");
        colors.put("mail.header.background", "#12384d");
        colors.put("mail.section.background", "#172635");
        colors.put("mail.status.background", "#263243");
        colors.put("mail.unread.foreground", "#ffcf66");
        colors.put("mail.tag.foreground", "#7ee787");
        colors.put("mail.compose.field.background", "#203349");
        colors.put("modeline.background", "#091017");
        colors.put("modeline.foreground", "#d2dce5");
        colors.put("mode.normal", "#1d4e89");
        colors.put("mode.input", "#8a4020");
        colors.put("mode.visual", "#1f6f50");
        colors.put("visual.selection.foreground", "#000000");
        colors.put("visual.selection.background", "#ffff00");
        colors.put("fancy.jump.foreground", "#ff6b6b");
        colors.put("debugger.header.foreground", "#f7fbff");
        colors.put("debugger.header.background", "#1d4e89");
        colors.put("debugger.foreground", "#dce6ef");
        colors.put("debugger.selection.foreground", "#000000");
        colors.put("debugger.selection.background", "#ffff00");
        colors.put("semantic.namespace", "#5ec4ff");
        colors.put("semantic.type", "#86d96a");
        colors.put("semantic.parameter", "#ffb86c");
        colors.put("semantic.member", "#ffd166");
        colors.put("semantic.function", "#7ab8ff");
        colors.put("semantic.comment", "#7ecb7e");
        colors.put("semantic.string", "#7fe3ff");
        colors.put("semantic.number", "#f5a3ff");
        colors.put("semantic.keyword", "#ff6b6b");
        colors.put("semantic.readonly", "#ffcf66");
        colors.put("semantic.macro", "#f7a94b");
        colors.put("git.commit.hash", "#f7a94b");
        colors.put("git.pull.request.number", "#3ddbd9");
        colors.put("git.label.foreground", "#a6e3a1");
        colors.put("git.label.background", "#1b4f72");
        colors.put("git.filter.field", "#ffb454");
        colors.put("git.filter.negated", "#ff6b6b");
        colors.put("git.review.separator", "#61788d");
        return Map.copyOf(colors);
    }

    private static Map<String, Consumer<TextColor>> colorSetters() {
        var setters = new LinkedHashMap<String, Consumer<TextColor>>();
        setters.put("root.background", value -> ROOT_BACKGROUND = value);
        setters.put("surface.background", value -> SURFACE_BACKGROUND = value);
        setters.put("surface.elevated", value -> SURFACE_ELEVATED = value);
        setters.put("surface.accent", value -> SURFACE_ACCENT = value);
        setters.put("surface.muted", value -> SURFACE_MUTED = value);
        setters.put("text.primary", value -> TEXT_PRIMARY = value);
        setters.put("text.muted", value -> TEXT_MUTED = value);
        setters.put("text.subtle", value -> TEXT_SUBTLE = value);
        setters.put("text.on.accent", value -> TEXT_ON_ACCENT = value);
        setters.put("accent.blue", value -> ACCENT_BLUE = value);
        setters.put("accent.gold", value -> ACCENT_GOLD = value);
        setters.put("accent.green", value -> ACCENT_GREEN = value);
        setters.put("accent.red", value -> ACCENT_RED = value);
        setters.put("accent.orange", value -> ACCENT_ORANGE = value);
        setters.put("accent.purple", value -> ACCENT_PURPLE = value);
        setters.put("diagnostic.error.foreground", value -> DIAGNOSTIC_ERROR_FOREGROUND = value);
        setters.put("diagnostic.error.background", value -> DIAGNOSTIC_ERROR_BACKGROUND = value);
        setters.put("diagnostic.warning.foreground", value -> DIAGNOSTIC_WARNING_FOREGROUND = value);
        setters.put("diagnostic.warning.background", value -> DIAGNOSTIC_WARNING_BACKGROUND = value);
        setters.put("diff.added.foreground", value -> DIFF_ADDED_FOREGROUND = value);
        setters.put("diff.added.background", value -> DIFF_ADDED_BACKGROUND = value);
        setters.put("diff.removed.foreground", value -> DIFF_REMOVED_FOREGROUND = value);
        setters.put("diff.removed.background", value -> DIFF_REMOVED_BACKGROUND = value);
        setters.put("panel.selection.background", value -> PANEL_SELECTION_BACKGROUND = value);
        setters.put("panel.selection.foreground", value -> PANEL_SELECTION_FOREGROUND = value);
        setters.put("panel.selection.accent", value -> PANEL_SELECTION_ACCENT = value);
        setters.put("menu.background", value -> MENU_BACKGROUND = value);
        setters.put("menu.secondary.background", value -> MENU_SECONDARY_BACKGROUND = value);
        setters.put("menu.segment.background", value -> MENU_SEGMENT_BACKGROUND = value);
        setters.put("menu.context.background", value -> MENU_CONTEXT_BACKGROUND = value);
        setters.put("menu.accent", value -> MENU_ACCENT = value);
        setters.put("menu.chain", value -> MENU_CHAIN = value);
        setters.put("menu.hint", value -> MENU_HINT = value);
        setters.put("command.background", value -> COMMAND_BACKGROUND = value);
        setters.put("command.inactive.background", value -> COMMAND_INACTIVE_BACKGROUND = value);
        setters.put("command.foreground", value -> COMMAND_FOREGROUND = value);
        setters.put("command.prompt", value -> COMMAND_PROMPT = value);
        setters.put("command.search", value -> COMMAND_SEARCH = value);
        setters.put("command.error", value -> COMMAND_ERROR = value);
        setters.put("command.success", value -> COMMAND_SUCCESS = value);
        setters.put("completion.background", value -> COMPLETION_BACKGROUND = value);
        setters.put("completion.foreground", value -> COMPLETION_FOREGROUND = value);
        setters.put("completion.header.background", value -> COMPLETION_HEADER_BACKGROUND = value);
        setters.put("completion.header.foreground", value -> COMPLETION_HEADER_FOREGROUND = value);
        setters.put("completion.footer.background", value -> COMPLETION_FOOTER_BACKGROUND = value);
        setters.put("completion.footer.foreground", value -> COMPLETION_FOOTER_FOREGROUND = value);
        setters.put("completion.selection.background", value -> COMPLETION_SELECTION_BACKGROUND = value);
        setters.put("completion.selection.foreground", value -> COMPLETION_SELECTION_FOREGROUND = value);
        setters.put("completion.annotation", value -> COMPLETION_ANNOTATION = value);
        setters.put("completion.source", value -> COMPLETION_SOURCE = value);
        setters.put("completion.kind.function", value -> COMPLETION_KIND_FUNCTION = value);
        setters.put("completion.kind.type", value -> COMPLETION_KIND_TYPE = value);
        setters.put("completion.kind.field", value -> COMPLETION_KIND_FIELD = value);
        setters.put("completion.kind.variable", value -> COMPLETION_KIND_VARIABLE = value);
        setters.put("completion.kind.keyword", value -> COMPLETION_KIND_KEYWORD = value);
        setters.put("completion.kind.default", value -> COMPLETION_KIND_DEFAULT = value);
        setters.put("chat.me", value -> CHAT_ME = value);
        setters.put("chat.nemo", value -> CHAT_NEMO = value);
        setters.put("mail.header.background", value -> MAIL_HEADER_BACKGROUND = value);
        setters.put("mail.section.background", value -> MAIL_SECTION_BACKGROUND = value);
        setters.put("mail.status.background", value -> MAIL_STATUS_BACKGROUND = value);
        setters.put("mail.unread.foreground", value -> MAIL_UNREAD_FOREGROUND = value);
        setters.put("mail.tag.foreground", value -> MAIL_TAG_FOREGROUND = value);
        setters.put("mail.compose.field.background", value -> MAIL_COMPOSE_FIELD_BACKGROUND = value);
        setters.put("modeline.background", value -> MODELINE_BACKGROUND = value);
        setters.put("modeline.foreground", value -> MODELINE_FOREGROUND = value);
        setters.put("mode.normal", value -> MODE_NORMAL = value);
        setters.put("mode.input", value -> MODE_INPUT = value);
        setters.put("mode.visual", value -> MODE_VISUAL = value);
        setters.put("visual.selection.foreground", value -> VISUAL_SELECTION_FOREGROUND = value);
        setters.put("visual.selection.background", value -> VISUAL_SELECTION_BACKGROUND = value);
        setters.put("fancy.jump.foreground", value -> FANCY_JUMP_FOREGROUND = value);
        setters.put("debugger.header.foreground", value -> DEBUGGER_HEADER_FOREGROUND = value);
        setters.put("debugger.header.background", value -> DEBUGGER_HEADER_BACKGROUND = value);
        setters.put("debugger.foreground", value -> DEBUGGER_FOREGROUND = value);
        setters.put("debugger.selection.foreground", value -> DEBUGGER_SELECTION_FOREGROUND = value);
        setters.put("debugger.selection.background", value -> DEBUGGER_SELECTION_BACKGROUND = value);
        setters.put("semantic.namespace", value -> SEMANTIC_NAMESPACE = value);
        setters.put("semantic.type", value -> SEMANTIC_TYPE = value);
        setters.put("semantic.parameter", value -> SEMANTIC_PARAMETER = value);
        setters.put("semantic.member", value -> SEMANTIC_MEMBER = value);
        setters.put("semantic.function", value -> SEMANTIC_FUNCTION = value);
        setters.put("semantic.comment", value -> SEMANTIC_COMMENT = value);
        setters.put("semantic.string", value -> SEMANTIC_STRING = value);
        setters.put("semantic.number", value -> SEMANTIC_NUMBER = value);
        setters.put("semantic.keyword", value -> SEMANTIC_KEYWORD = value);
        setters.put("semantic.readonly", value -> SEMANTIC_READONLY = value);
        setters.put("semantic.macro", value -> SEMANTIC_MACRO = value);
        return Map.copyOf(setters);
    }

    static String repeat(String value, int count) {
        return count <= 0 ? "" : value.repeat(count);
    }

    static String fit(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width == 1) {
            return "…";
        }
        return text.substring(0, width - 1) + "…";
    }

    static String padRight(String text, int width) {
        String fitted = fit(text, width);
        return fitted + repeat(" ", width - fitted.length());
    }

    static void appendSegment(AttributedString line, String text, TextColor foreground, TextColor background) {
        line.append(" " + text + " ", foreground, background);
    }

    static void appendRightSeparator(AttributedString line, TextColor fromBackground, TextColor toBackground) {
        line.append(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fromBackground, toBackground);
    }

    static void appendLeftSeparator(AttributedString line, TextColor fromBackground, TextColor toBackground) {
        line.append(Powerline.SYMBOL_FILLED_LEFT_ARROW, fromBackground, toBackground);
    }

    static void fillRow(TextGraphics graphics, Point point, int width, TextColor background) {
        if (width <= 0) {
            return;
        }
        graphics.setBackgroundColor(background);
        graphics.fillRectangle(new TerminalPosition(point.getX(), point.getY()), new TerminalSize(width, 1), ' ');
    }

    static void drawLine(TextGraphics graphics, Point point, int width, AttributedString line,
            TextColor paddingForeground, TextColor paddingBackground) {
        fillRow(graphics, point, width, paddingBackground);
        if (width <= 0) {
            return;
        }
        AttributedString output = line.length() > width ? line.slice(0, width) : AttributedString.create(line);
        int remaining = width - output.length();
        if (remaining > 0) {
            output.append(repeat(" ", remaining), paddingForeground, paddingBackground);
        }
        output.drawAt(point, graphics);
    }

    static TextColor modeColor(String modeName) {
        return switch (modeName) {
        case "INPUT" -> MODE_INPUT;
        case "VISUAL" -> MODE_VISUAL;
        default -> MODE_NORMAL;
        };
    }

    static String displayKey(String token) {
        if (token != null && token.startsWith("<CTRL>-")) {
            String rest = token.substring("<CTRL>-".length());
            if (rest.length() == 1) {
                return "C-" + rest;
            }
            return "C-" + displayKey(rest);
        }
        return switch (token) {
        case "<SPACE>" -> "SPC";
        case "<CHAR>" -> "char";
        case "<ESC>" -> "Esc";
        case "<ENTER>" -> "Enter";
        case "<SHIFT>-<ENTER>" -> "S-Enter";
        case "<BACKSPACE>" -> "Bksp";
        case "<REVERSE-TAB>" -> "S-Tab";
        case "<TAB>" -> "Tab";
        case "<PAGEUP>" -> "PgUp";
        case "<PAGEDOWN>" -> "PgDn";
        case "<UP>" -> "Up";
        case "<DOWN>" -> "Down";
        case "<LEFT>" -> "Left";
        case "<RIGHT>" -> "Right";
        default -> token;
        };
    }
}
