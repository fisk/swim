package org.fisk.swim.terminal;

/** Complete SGR style for one terminal cell. */
public record AnsiStyle(AnsiColour foreground, AnsiColour background, boolean bold, boolean underline, boolean inverse) {
    public static final AnsiStyle DEFAULT = new AnsiStyle(AnsiColour.DEFAULT, AnsiColour.DEFAULT, false, false, false);

    public AnsiStyle {
        foreground = foreground == null ? AnsiColour.DEFAULT : foreground;
        background = background == null ? AnsiColour.DEFAULT : background;
    }
}
