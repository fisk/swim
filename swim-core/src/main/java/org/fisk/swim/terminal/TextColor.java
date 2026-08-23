package org.fisk.swim.terminal;

import java.util.Locale;
import java.util.Objects;

/** SWIM's terminal colour model, independent of the terminal renderer. */
public sealed interface TextColor permits TextColor.RGB, TextColor.Indexed, TextColor.ANSI {
    int getRed();

    int getGreen();

    int getBlue();

    record RGB(int red, int green, int blue) implements TextColor {
        public RGB {
            validate(red);
            validate(green);
            validate(blue);
        }

        @Override public int getRed() { return red; }
        @Override public int getGreen() { return green; }
        @Override public int getBlue() { return blue; }
    }

    record Indexed(int index) implements TextColor {
        public Indexed {
            if (index < 0 || index > 255) throw new IllegalArgumentException("ANSI colour index must be in [0,255]");
        }

        @Override public int getRed() { return rgb(index)[0]; }
        @Override public int getGreen() { return rgb(index)[1]; }
        @Override public int getBlue() { return rgb(index)[2]; }

        public static Indexed fromRGB(int red, int green, int blue) {
            return new Indexed(16 + 36 * Math.round(red / 51f) + 6 * Math.round(green / 51f) + Math.round(blue / 51f));
        }
    }

    enum ANSI implements TextColor {
        BLACK(0, 0, 0), RED(205, 49, 49), GREEN(13, 188, 121), YELLOW(229, 229, 16),
        BLUE(36, 114, 200), MAGENTA(188, 63, 188), CYAN(17, 168, 205), WHITE(229, 229, 229),
        DEFAULT(0, 0, 0), BLACK_BRIGHT(102, 102, 102), RED_BRIGHT(241, 76, 76),
        GREEN_BRIGHT(35, 209, 139), YELLOW_BRIGHT(245, 245, 67), BLUE_BRIGHT(59, 142, 234),
        MAGENTA_BRIGHT(214, 112, 214), CYAN_BRIGHT(41, 184, 219), WHITE_BRIGHT(255, 255, 255);

        private final int red;
        private final int green;
        private final int blue;
        ANSI(int red, int green, int blue) { this.red = red; this.green = green; this.blue = blue; }
        @Override public int getRed() { return red; }
        @Override public int getGreen() { return green; }
        @Override public int getBlue() { return blue; }
        public boolean isBright() { return ordinal() >= BLACK_BRIGHT.ordinal(); }
    }

    final class Factory {
        private Factory() { }

        public static TextColor fromString(String value) {
            String colour = Objects.requireNonNull(value, "value").trim();
            if (colour.startsWith("#") && colour.length() == 7) {
                return new RGB(Integer.parseInt(colour.substring(1, 3), 16), Integer.parseInt(colour.substring(3, 5), 16),
                        Integer.parseInt(colour.substring(5, 7), 16));
            }
            try { return ANSI.valueOf(colour.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown terminal colour: " + value, e); }
        }
    }

    private static void validate(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("RGB component must be in [0,255]");
    }

    private static int[] rgb(int index) {
        if (index < 16) {
            ANSI[] ansi = ANSI.values();
            TextColor colour = ansi[index < 8 ? index : index + 1];
            return new int[] { colour.getRed(), colour.getGreen(), colour.getBlue() };
        }
        if (index >= 232) { int value = 8 + (index - 232) * 10; return new int[] { value, value, value }; }
        int value = index - 16;
        int[] cube = { 0, 95, 135, 175, 215, 255 };
        return new int[] { cube[value / 36], cube[value / 6 % 6], cube[value % 6] };
    }
}
