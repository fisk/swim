package org.fisk.swim.terminal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;

public final class TerminalContextTestSupport {
    private TerminalContextTestSupport() {
    }

    public static InstalledTerminalContext install(int columns, int rows) {
        return install(columns, rows, null);
    }

    public static InstalledTerminalContext install(int columns, int rows, Throwable stopFailure) {
        var stopCalls = new AtomicInteger();
        var closeCalls = new AtomicInteger();
        var drawCalls = new CopyOnWriteArrayList<DrawCall>();
        var foreground = new AtomicReference<TextColor>(TextColor.ANSI.DEFAULT);
        var background = new AtomicReference<TextColor>(TextColor.ANSI.DEFAULT);
        var cursorPosition = new AtomicReference<TerminalPosition>(new TerminalPosition(0, 0));
        var graphics = (TextGraphics) Proxy.newProxyInstance(
                TextGraphics.class.getClassLoader(),
                new Class<?>[] { TextGraphics.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "setForegroundColor":
                        foreground.set((TextColor) args[0]);
                        return null;
                    case "setBackgroundColor":
                        background.set((TextColor) args[0]);
                        return null;
                    case "putString":
                        if (args != null && args.length >= 3 && args[0] instanceof Integer x && args[1] instanceof Integer y
                                && args[2] instanceof String text) {
                            drawCalls.add(new DrawCall(x, y, text, foreground.get(), background.get()));
                        }
                        return proxy;
                    default:
                        return defaultValue(proxy, method.getReturnType(), columns, rows);
                    }
                });
        var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] { Terminal.class },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closeCalls.incrementAndGet();
                        return null;
                    }
                    return defaultValue(proxy, method.getReturnType(), columns, rows);
                });
        var screen = (Screen) Proxy.newProxyInstance(
                Screen.class.getClassLoader(),
                new Class<?>[] { Screen.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getTerminalSize":
                        return new TerminalSize(columns, rows);
                    case "newTextGraphics":
                        return graphics;
                    case "doResizeIfNecessary":
                        return null;
                    case "getCursorPosition":
                        return cursorPosition.get();
                    case "setCursorPosition":
                        if (args != null && args.length == 1 && args[0] instanceof TerminalPosition position) {
                            cursorPosition.set(position);
                        }
                        return null;
                    case "getFrontCharacter":
                    case "getBackCharacter":
                    case "getCharacter":
                        return TextCharacter.DEFAULT_CHARACTER;
                    case "stopScreen":
                        stopCalls.incrementAndGet();
                        if (stopFailure instanceof IOException io) {
                            throw io;
                        }
                        if (stopFailure instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        return null;
                    default:
                        return defaultValue(proxy, method.getReturnType(), columns, rows);
                    }
                });
        var context = new TerminalContext(screen, terminal, graphics);
        setInstance(context);
        return new InstalledTerminalContext(context, stopCalls, closeCalls, drawCalls, cursorPosition);
    }

    private static void setInstance(TerminalContext context) {
        try {
            Field field = TerminalContext.class.getDeclaredField("_instance");
            field.setAccessible(true);
            field.set(null, context);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object defaultValue(Object proxy, Class<?> type, int columns, int rows) {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Byte.TYPE) {
            return new byte[0];
        }
        if (type == TerminalSize.class) {
            return new TerminalSize(columns, rows);
        }
        if (type == TerminalPosition.class) {
            return new TerminalPosition(0, 0);
        }
        if (type.isInstance(proxy)) {
            return proxy;
        }
        return null;
    }

    public record InstalledTerminalContext(
            TerminalContext context,
            AtomicInteger stopCalls,
            AtomicInteger closeCalls,
            List<DrawCall> drawCalls,
            AtomicReference<TerminalPosition> cursorPosition) {
    }

    public record DrawCall(int x, int y, String text, TextColor foreground, TextColor background) {
    }
}
