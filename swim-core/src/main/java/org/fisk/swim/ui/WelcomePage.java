package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.TextColor;

final class WelcomePage {
    static final String HELP_TEXT = "Type :help and press Enter to open the help pages.";
    static final String DISPLAY_NAME = "*welcome*";

    private static final int DEFAULT_FRAME_MILLIS = 120;
    private static final int JOIN_MILLIS = 500;
    private static final String[] LOGO = {
            " ____ __        _____ __  __ ",
            "/ ___|\\ \\      / /_ _|  \\/  |",
            "\\___ \\\\ \\ /\\ / / | || |\\/| |",
            " ___) |\\ V  V /  | || |  | |",
            "|____/  \\_/\\_/  |___|_|  |_|"
    };
    private static final String[] SUBMARINE = {
            "                  _|_                 ",
            "              ___/___\\___             ",
            "       ______/           \\______       ",
            "  <=< /   o     o     o        \\       ",
            "    <|     ___       ___        |>     ",
            "      \\___/___\\_____/___\\_____/       ",
            "           /_/             \\_\\         "
    };
    private static final String[] SMALL_SUBMARINE = {
            " _|_ ",
            "<_o_>"
    };
    private static final String[] SHELL = {
            "  _  ",
            " /|\\ ",
            "/_|_\\"
    };
    private static final String[] FISH = {
            "><>",
            "<><",
            ">))>",
            "<((<"
    };
    private static final char[] BUBBLES = { 'o', 'O', 'o', 'O' };
    private static final char[] FLOOR = { '.', '_', '.', '.', ',', '.', '_' };

    private WelcomePage() {
    }

    static BufferContext createBufferContext(Rect rect, String initialText) {
        return new BufferContext(rect, initialText, true, WelcomeBufferView::new);
    }

    static void stopIfWelcome(BufferView bufferView) {
        if (bufferView instanceof WelcomeBufferView welcomeView) {
            welcomeView.close();
        }
    }

    static boolean isWelcome(BufferView bufferView) {
        return bufferView instanceof WelcomeBufferView;
    }

    static boolean isWelcome(BufferContext context) {
        return context != null && isWelcome(context.getBufferView());
    }

    static void stopAll(Collection<BufferView> bufferViews) {
        if (bufferViews == null) {
            return;
        }
        for (BufferView bufferView : bufferViews) {
            stopIfWelcome(bufferView);
        }
    }

    static List<String> renderFrame(int width, int height, int frame) {
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        char[][] canvas = new char[height][width];
        for (char[] row : canvas) {
            Arrays.fill(row, ' ');
        }

        drawWaves(canvas, frame);
        drawOceanTexture(canvas, frame);
        drawBubbles(canvas, frame);
        drawLogo(canvas);

        int floorY = Math.max(0, height - 2);
        drawSeaFloor(canvas, floorY);
        drawShell(canvas, floorY);

        drawFish(canvas, floorY, frame);
        drawSubmarine(canvas, floorY, frame);
        drawHelpText(canvas, floorY);

        var lines = new ArrayList<String>(height);
        for (char[] row : canvas) {
            lines.add(new String(row));
        }
        return lines;
    }

    private static void drawLogo(char[][] canvas) {
        int width = canvas[0].length;
        int y = logoTop(canvas);
        if (width < maxWidth(LOGO) || canvas.length < 10) {
            drawCentered(canvas, y, "SWIM");
            return;
        }
        drawText(canvas, Math.max(0, (width - maxWidth(LOGO)) / 2), y, LOGO, false);
    }

    private static void drawWaves(char[][] canvas, int frame) {
        int width = canvas[0].length;
        String wave = frame % 2 == 0 ? "~    ~   " : "  ~    ~ ";
        for (int x = 0; x < width; x++) {
            char c = wave.charAt(positiveMod(x + frame, wave.length()));
            if (c != ' ') {
                canvas[0][x] = c;
            }
        }
    }

    private static void drawOceanTexture(char[][] canvas, int frame) {
        int height = canvas.length;
        int width = canvas[0].length;
        int waterRows = Math.max(0, height - 4);
        if (waterRows <= 0) {
            return;
        }
        int specks = Math.max(2, width * waterRows / 35);
        for (int i = 0; i < specks; i++) {
            int x = positiveMod(i * 17 + frame * 2, width);
            int y = 1 + positiveMod(i * 7 + frame, waterRows);
            if (canvas[y][x] == ' ') {
                canvas[y][x] = '.';
            }
        }
    }

    private static void drawSeaFloor(char[][] canvas, int floorY) {
        int height = canvas.length;
        int width = canvas[0].length;
        for (int x = 0; x < width; x++) {
            canvas[floorY][x] = FLOOR[x % FLOOR.length];
            if (floorY + 1 < height) {
                canvas[floorY + 1][x] = x % 5 == 0 ? '_' : '.';
            }
        }
    }

    private static void drawShell(char[][] canvas, int floorY) {
        if (canvas.length < 6 || canvas[0].length < 8) {
            return;
        }
        int x = Math.max(1, canvas[0].length * 3 / 4 - SHELL[0].length() / 2);
        int y = Math.max(1, floorY - SHELL.length + 1);
        drawText(canvas, x, y, SHELL, true);
    }

    private static void drawFish(char[][] canvas, int floorY, int frame) {
        int width = canvas[0].length;
        int waterHeight = Math.max(1, floorY - 2);
        for (int i = 0; i < FISH.length; i++) {
            String fish = FISH[(i + frame / 3) % FISH.length];
            int span = width + fish.length();
            int x = i % 2 == 0
                    ? width - positiveMod(frame + i * 19, span)
                    : positiveMod(frame + i * 23, span) - fish.length();
            int y = 2 + positiveMod(i * 4 + frame / 2, waterHeight);
            drawText(canvas, x, y, fish, true);
        }
    }

    private static void drawSubmarine(char[][] canvas, int floorY, int frame) {
        String[] submarine = useSmallSubmarine(canvas) ? SMALL_SUBMARINE : SUBMARINE;
        int width = canvas[0].length;
        int submarineWidth = maxWidth(submarine);
        int span = width + submarineWidth;
        int x = positiveMod(frame * 2 + width / 4, span) - submarineWidth / 2;
        int logoBottom = logoTop(canvas) + logoHeight(canvas) - 1;
        int waterTop = Math.max(1, logoBottom + 1);
        int waterBottom = Math.max(waterTop, floorY - 2);
        int midpoint = (waterTop + waterBottom) / 2;
        int maxY = Math.max(1, floorY - submarine.length - 2);
        int y = clamp(midpoint - submarine.length / 2 + verticalBob(frame), waterTop, maxY);
        drawSubmarineText(canvas, x, y, submarine);
    }

    private static boolean useSmallSubmarine(char[][] canvas) {
        return canvas.length < 14 || canvas[0].length < 48;
    }

    private static int logoTop(char[][] canvas) {
        return canvas.length >= 18 ? 2 : 1;
    }

    private static int logoHeight(char[][] canvas) {
        return canvas[0].length < maxWidth(LOGO) || canvas.length < 10 ? 1 : LOGO.length;
    }

    private static int verticalBob(int frame) {
        return switch (positiveMod(frame / 4, 8)) {
        case 0, 1 -> -1;
        case 2, 3 -> 0;
        case 4, 5 -> 1;
        default -> 0;
        };
    }

    private static void drawBubbles(char[][] canvas, int frame) {
        int width = canvas[0].length;
        int height = canvas.length;
        int floorY = Math.max(0, height - 2);
        int waterHeight = Math.max(1, floorY - 1);
        int bubbleCount = Math.max(3, width * Math.max(1, floorY) / 180);
        for (int i = 0; i < bubbleCount; i++) {
            int baseX = positiveMod(hash(i * 97 + 11), width);
            int baseY = 1 + positiveMod(hash(i * 131 + 17), waterHeight);
            int driftX = positiveMod(hash(i * 47 + frame * 13), 9) - 4;
            int driftY = positiveMod(hash(i * 59 + frame * 17), 7) - 3;
            int x = positiveMod(baseX + driftX + frame / 3, width);
            int y = 1 + positiveMod(baseY + driftY + frame / 5, waterHeight);
            char bubble = BUBBLES[positiveMod(hash(i * 43 + frame), BUBBLES.length)];
            if (canvas[y][x] == ' ' || canvas[y][x] == '.') {
                canvas[y][x] = bubble;
            }
        }
    }

    private static void drawHelpText(char[][] canvas, int floorY) {
        if (canvas.length < 5 || canvas[0].length < HELP_TEXT.length()) {
            return;
        }
        drawCentered(canvas, Math.max(1, floorY - 3), HELP_TEXT);
    }

    private static void drawCentered(char[][] canvas, int y, String text) {
        int x = Math.max(0, (canvas[0].length - text.length()) / 2);
        drawText(canvas, x, y, text, false);
    }

    private static void drawText(char[][] canvas, int x, int y, String[] lines, boolean transparentSpaces) {
        for (int row = 0; row < lines.length; row++) {
            drawText(canvas, x, y + row, lines[row], transparentSpaces);
        }
    }

    private static void drawSubmarineText(char[][] canvas, int x, int y, String[] lines) {
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            int first = firstNonSpace(line);
            int last = lastNonSpace(line);
            if (first < 0 || last < first) {
                continue;
            }
            drawText(canvas, x + first, y + row, line.substring(first, last + 1), false);
        }
    }

    private static void drawText(char[][] canvas, int x, int y, String text, boolean transparentSpaces) {
        if (y < 0 || y >= canvas.length) {
            return;
        }
        int width = canvas[0].length;
        for (int i = 0; i < text.length(); i++) {
            int targetX = x + i;
            if (targetX < 0 || targetX >= width) {
                continue;
            }
            char c = text.charAt(i);
            if (transparentSpaces && c == ' ') {
                continue;
            }
            canvas[y][targetX] = c;
        }
    }

    private static int maxWidth(String[] lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        return width;
    }

    private static int firstNonSpace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != ' ') {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonSpace(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (line.charAt(i) != ' ') {
                return i;
            }
        }
        return -1;
    }

    private static int positiveMod(int value, int modulo) {
        if (modulo <= 0) {
            return 0;
        }
        int result = value % modulo;
        return result < 0 ? result + modulo : result;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static final class WelcomeBufferView extends BufferView implements AutoCloseable {
        private volatile boolean _closed;
        private int _frame;
        private Thread _animationThread;

        private WelcomeBufferView(Rect rect, BufferContext bufferContext) {
            super(rect, bufferContext);
            setBackgroundColour(UiTheme.ROOT_BACKGROUND);
            startAnimation();
        }

        @Override
        public void draw(Rect rect) {
            var graphics = TerminalContext.getInstance().getGraphics();
            for (int row = 0; row < rect.getSize().getHeight(); row++) {
                UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row),
                        rect.getSize().getWidth(), UiTheme.ROOT_BACKGROUND);
            }
            var lines = renderFrame(rect.getSize().getWidth(), rect.getSize().getHeight(), _frame);
            int x = rect.getPoint().getX();
            int y = rect.getPoint().getY();
            for (int row = 0; row < lines.size(); row++) {
                String line = lines.get(row);
                graphics.setBackgroundColor(UiTheme.ROOT_BACKGROUND);
                graphics.setForegroundColor(foreground(line, row, lines.size()));
                graphics.putString(x, y + row, line);
            }
        }

        @Override
        public void close() {
            _closed = true;
            Thread thread = _animationThread;
            if (thread == null) {
                return;
            }
            thread.interrupt();
            if (Thread.currentThread() == thread) {
                return;
            }
            try {
                thread.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void startAnimation() {
            if (!animationEnabled()) {
                return;
            }
            _animationThread = new Thread(this::runAnimation, "swim-welcome-page");
            _animationThread.setDaemon(true);
            _animationThread.start();
        }

        private static boolean animationEnabled() {
            return !Boolean.getBoolean("swim.welcome.disable_animation")
                    && System.getProperty("surefire.test.class.path") == null;
        }

        private static int frameMillis() {
            return Math.max(33, Integer.getInteger("swim.welcome.frame_millis", DEFAULT_FRAME_MILLIS));
        }

        private void runAnimation() {
            while (!_closed) {
                try {
                    Thread.sleep(frameMillis());
                } catch (InterruptedException e) {
                    return;
                }
                if (!_closed) {
                    EventThread.getInstance().enqueue(new RunnableEvent(this::advanceFrame));
                }
            }
        }

        private void advanceFrame() {
            if (_closed || getParent() == null) {
                return;
            }
            _frame++;
            setNeedsRedraw();
        }
    }

    private static TextColor foreground(String line, int row, int height) {
        if (line.contains(HELP_TEXT) || line.contains("\\_/\\_/") || line.contains("|____/")) {
            return UiTheme.TEXT_ON_ACCENT;
        }
        if (row >= Math.max(0, height - 2) || line.contains("/_|_\\")) {
            return UiTheme.ACCENT_GOLD;
        }
        if (line.contains("___") || line.contains("(_)") || line.contains("<_")) {
            return UiTheme.ACCENT_BLUE;
        }
        if (line.contains("><") || line.contains("<>") || line.contains("))") || line.contains("((")) {
            return UiTheme.ACCENT_GREEN;
        }
        return UiTheme.TEXT_MUTED;
    }

    private static int hash(int value) {
        int hash = value;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return hash;
    }
}
