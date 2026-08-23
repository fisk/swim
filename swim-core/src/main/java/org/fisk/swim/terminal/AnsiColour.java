package org.fisk.swim.terminal;

/** Terminal colour independent of any rendering library. */
public record AnsiColour(int red, int green, int blue, boolean defaultColour) {
    public static final AnsiColour DEFAULT = new AnsiColour(0, 0, 0, true);

    public AnsiColour {
        if (!defaultColour && (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255)) {
            throw new IllegalArgumentException("RGB components must be in [0,255]");
        }
    }

    public static AnsiColour rgb(int red, int green, int blue) {
        return new AnsiColour(red, green, blue, false);
    }

    /** Temporary migration bridge from the current renderer's colour type. */
    public static AnsiColour fromTextColor(org.fisk.swim.terminal.TextColor colour) {
        if (colour == null || colour == org.fisk.swim.terminal.TextColor.ANSI.DEFAULT) return DEFAULT;
        return rgb(colour.getRed(), colour.getGreen(), colour.getBlue());
    }
}
