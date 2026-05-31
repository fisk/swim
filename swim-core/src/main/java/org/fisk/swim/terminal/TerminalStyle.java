package org.fisk.swim.terminal;

import com.googlecode.lanterna.TextColor;

public record TerminalStyle(TextColor foreground, TextColor background, boolean bold, boolean inverse) {
    public static final TerminalStyle DEFAULT = new TerminalStyle(TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT, false,
            false);

    public TextColor resolvedForeground() {
        return inverse ? background : foreground;
    }

    public TextColor resolvedBackground() {
        return inverse ? foreground : background;
    }
}
