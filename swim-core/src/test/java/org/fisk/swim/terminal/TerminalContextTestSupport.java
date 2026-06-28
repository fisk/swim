package org.fisk.swim.terminal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import com.googlecode.lanterna.terminal.Terminal;

public final class TerminalContextTestSupport {
    private TerminalContextTestSupport() {
    }

    public static InstalledTerminalContext install(int columns, int rows) {
        return install(columns, rows, null);
    }

    public static InstalledTerminalContext install(int columns, int rows, Throwable stopFailure) {
        return install(columns, rows, stopFailure, () -> new TerminalSize(columns, rows));
    }

    public static InstalledTerminalContext install(int columns, int rows, Throwable stopFailure, Supplier<TerminalSize> terminalSizeSupplier) {
        var stopCalls = new AtomicInteger();
        var closeCalls = new AtomicInteger();
        var clearCalls = new AtomicInteger();
        var resizeCalls = new AtomicInteger();
        var drawCalls = new CopyOnWriteArrayList<DrawCall>();
        var refreshCalls = new CopyOnWriteArrayList<RefreshType>();
        var terminalWrites = new CopyOnWriteArrayList<String>();
        var foreground = new AtomicReference<TextColor>(TextColor.ANSI.DEFAULT);
        var background = new AtomicReference<TextColor>(TextColor.ANSI.DEFAULT);
        var cursorPosition = new AtomicReference<TerminalPosition>(new TerminalPosition(0, 0));
        var screenSize = new AtomicReference<TerminalSize>(new TerminalSize(columns, rows));
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
                    case "drawRectangle":
                        if (args != null && args.length >= 3
                                && args[0] instanceof TerminalPosition position
                                && args[1] instanceof TerminalSize size
                                && args[2] instanceof Character character) {
                            String text = Character.toString(character).repeat(Math.max(0, size.getColumns()));
                            for (int row = 0; row < size.getRows(); row++) {
                                drawCalls.add(new DrawCall(position.getColumn(), position.getRow() + row, text,
                                        foreground.get(), background.get()));
                            }
                        }
                        return proxy;
                    default:
                        return defaultValue(proxy, method.getReturnType(), columns, rows);
                    }
                });
        var terminal = (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] { Terminal.class, TerminalControlWriter.class },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closeCalls.incrementAndGet();
                        return null;
                    }
                    if ("putString".equals(method.getName()) && args != null && args.length == 1) {
                        terminalWrites.add((String) args[0]);
                        return null;
                    }
                    if ("writeControlSequence".equals(method.getName()) && args != null && args.length == 1) {
                        terminalWrites.add((String) args[0]);
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
                        return screenSize.get();
                    case "newTextGraphics":
                        return graphics;
                    case "doResizeIfNecessary":
                        TerminalSize supplied = terminalSizeSupplier == null ? null : terminalSizeSupplier.get();
                        if (supplied != null && !supplied.equals(screenSize.get())) {
                            screenSize.set(supplied);
                            resizeCalls.incrementAndGet();
                            return supplied;
                        }
                        return null;
                    case "clear":
                        clearCalls.incrementAndGet();
                        return null;
                    case "getCursorPosition":
                        return cursorPosition.get();
                    case "setCursorPosition":
                        if (args != null && args.length == 1 && args[0] instanceof TerminalPosition position) {
                            cursorPosition.set(position);
                        }
                        return null;
                    case "refresh":
                        if (args != null && args.length == 1 && args[0] instanceof RefreshType refreshType) {
                            refreshCalls.add(refreshType);
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
        var context = new TerminalContext(screen, terminal, graphics, terminalSizeSupplier);
        setInstance(context);
        return new InstalledTerminalContext(context, stopCalls, closeCalls, clearCalls, resizeCalls, screenSize,
                drawCalls, refreshCalls, cursorPosition, terminalWrites);
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
            AtomicInteger clearCalls,
            AtomicInteger resizeCalls,
            AtomicReference<TerminalSize> screenSize,
            List<DrawCall> drawCalls,
            List<RefreshType> refreshCalls,
            AtomicReference<TerminalPosition> cursorPosition,
            List<String> terminalWrites) {
    }

    public record DrawCall(int x, int y, String text, TextColor foreground, TextColor background) {
    }
}
