package org.fisk.swim.ui;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

public final class UiTheme {
    public static final TextColor ROOT_BACKGROUND = color("#0b1117");
    public static final TextColor SURFACE_BACKGROUND = color("#111a23");
    public static final TextColor SURFACE_ELEVATED = color("#15202b");
    public static final TextColor SURFACE_ACCENT = color("#1b2a38");
    public static final TextColor SURFACE_MUTED = color("#101821");

    public static final TextColor TEXT_PRIMARY = color("#dce6ef");
    public static final TextColor TEXT_MUTED = color("#8ca1b3");
    public static final TextColor TEXT_SUBTLE = color("#61788d");
    public static final TextColor TEXT_ON_ACCENT = color("#f7fbff");

    public static final TextColor ACCENT_BLUE = color("#5ec4ff");
    public static final TextColor ACCENT_GOLD = color("#ffb454");
    public static final TextColor ACCENT_GREEN = color("#7ee787");
    public static final TextColor ACCENT_RED = color("#ff7b72");
    public static final TextColor ACCENT_ORANGE = color("#ff9e64");
    public static final TextColor DIAGNOSTIC_ERROR_BACKGROUND = color("#4a2020");
    public static final TextColor DIAGNOSTIC_WARNING_FOREGROUND = color("#ffe66d");
    public static final TextColor DIAGNOSTIC_WARNING_BACKGROUND = color("#4a3f1a");

    public static final TextColor PANEL_SELECTION_BACKGROUND = color("#20405a");
    public static final TextColor PANEL_SELECTION_FOREGROUND = color("#f8fbff");
    public static final TextColor PANEL_SELECTION_ACCENT = color("#ffb454");

    public static final TextColor MENU_BACKGROUND = color("#0d151d");
    public static final TextColor MENU_SECONDARY_BACKGROUND = color("#101b25");
    public static final TextColor MENU_SEGMENT_BACKGROUND = color("#15222d");
    public static final TextColor MENU_CONTEXT_BACKGROUND = color("#1d2d3a");
    public static final TextColor MENU_ACCENT = color("#5ec4ff");
    public static final TextColor MENU_CHAIN = color("#ffb454");
    public static final TextColor MENU_HINT = color("#7ee787");

    public static final TextColor COMMAND_BACKGROUND = color("#0f1822");
    public static final TextColor COMMAND_INACTIVE_BACKGROUND = color("#0c131b");
    public static final TextColor COMMAND_PROMPT = color("#5ec4ff");
    public static final TextColor COMMAND_SEARCH = color("#ffb454");
    public static final TextColor COMMAND_ERROR = color("#ff7b72");
    public static final TextColor COMMAND_SUCCESS = color("#7ee787");

    public static final TextColor CHAT_ME = color("#ffb86b");
    public static final TextColor CHAT_NEMO = color("#7ee787");

    public static final TextColor MODELINE_BACKGROUND = color("#091017");
    public static final TextColor MODELINE_FOREGROUND = color("#d2dce5");
    public static final TextColor MODE_NORMAL = color("#1d4e89");
    public static final TextColor MODE_INPUT = color("#8a4020");
    public static final TextColor MODE_VISUAL = color("#1f6f50");

    private UiTheme() {
    }

    static TextColor color(String hex) {
        return TextColor.Factory.fromString(hex);
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
        return switch (token) {
        case "<SPACE>" -> "SPC";
        case "<CTRL>-g" -> "C-g";
        case "<CTRL>-v" -> "C-v";
        case "<CTRL>-r" -> "C-r";
        case "<CTRL>-t" -> "C-t";
        case "<CTRL>-y" -> "C-y";
        case "<CTRL>-e" -> "C-e";
        case "<CHAR>" -> "char";
        case "<ESC>" -> "Esc";
        case "<ENTER>" -> "Enter";
        case "<BACKSPACE>" -> "Bksp";
        case "<UP>" -> "Up";
        case "<DOWN>" -> "Down";
        case "<LEFT>" -> "Left";
        case "<RIGHT>" -> "Right";
        default -> token;
        };
    }
}
