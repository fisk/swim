package org.fisk.swim.ui;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.mode.InputMode;
import org.fisk.swim.mode.NormalMode;
import org.fisk.swim.mode.VisualBlockMode;
import org.fisk.swim.mode.VisualLineMode;
import org.fisk.swim.mode.VisualMode;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import sun.misc.Unsafe;

public final class HeadlessWindowHarness implements AutoCloseable {
    private static final Unsafe UNSAFE = lookupUnsafe();

    private final Window _window;

    private HeadlessWindowHarness(Window window) {
        _window = window;
    }

    public static HeadlessWindowHarness installForBufferContext(BufferContext bufferContext) {
        var bounds = bufferContext.getBufferView().getBounds();
        var width = bounds.getSize().getWidth();
        var height = bounds.getSize().getHeight();

        var window = allocate(Window.class);
        setStaticField(Window.class, "_instance", window);

        var rootView = new View(Rect.create(0, 0, width, height));
        rootView.setBackgroundColour(UiTheme.ROOT_BACKGROUND);
        var keyMenuView = new KeyMenuView(Rect.create(0, 0, width, 2));
        var commandMenuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        rootView.addSubview(keyMenuView);
        rootView.addSubview(bufferContext.getBufferView());
        rootView.addSubview(commandMenuView);
        rootView.setFirstResponder(bufferContext.getBufferView());

        setField(window, "_rootView", rootView);
        setField(window, "_keyMenuView", keyMenuView);
        setField(window, "_commandMenuView", commandMenuView);
        setField(window, "_workspaceView", bufferContext.getBufferView());
        setField(window, "_activeView", bufferContext.getBufferView());
        setField(window, "_activeBufferView", bufferContext.getBufferView());
        setField(window, "_bufferContext", bufferContext);
        var bufferContextsByView = new IdentityHashMap<BufferView, org.fisk.swim.text.BufferContext>();
        bufferContextsByView.put(bufferContext.getBufferView(), bufferContext);
        setField(window, "_bufferContextsByView", bufferContextsByView);
        var bufferViewCounts = new IdentityHashMap<org.fisk.swim.text.BufferContext, Integer>();
        bufferViewCounts.put(bufferContext, 1);
        setField(window, "_bufferViewCounts", bufferViewCounts);
        window.refreshChromeState();

        return new HeadlessWindowHarness(window);
    }

    public static HeadlessWindowHarness create(Path path, int width, int height) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        if (!Files.exists(path)) {
            Files.writeString(path, "");
        }

        var window = allocate(Window.class);
        setStaticField(Window.class, "_instance", window);

        var rootView = new View(Rect.create(0, 0, width, height));
        rootView.setBackgroundColour(UiTheme.ROOT_BACKGROUND);
        var bufferContext = new BufferContext(Rect.create(0, 2, width, height - 4), path);
        var keyMenuView = new KeyMenuView(Rect.create(0, 0, width, 2));
        keyMenuView.setResizeMask(View.RESIZE_MASK_TOP | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        var modeLineView = new ModeLineView(Rect.create(0, height - 2, width, 1));
        modeLineView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        var commandView = new CommandView(Rect.create(0, height - 1, width, 1));
        commandView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        var commandMenuView = new CommandMenuView(Rect.create(0, 0, 0, 0));

        rootView.addSubview(keyMenuView);
        rootView.addSubview(modeLineView);
        rootView.addSubview(commandView);
        rootView.addSubview(bufferContext.getBufferView());
        rootView.addSubview(commandMenuView);
        rootView.setFirstResponder(bufferContext.getBufferView());

        setField(window, "_rootView", rootView);
        setField(window, "_keyMenuView", keyMenuView);
        setField(window, "_workspaceView", bufferContext.getBufferView());
        setField(window, "_activeView", bufferContext.getBufferView());
        setField(window, "_activeBufferView", bufferContext.getBufferView());
        setField(window, "_modeLineView", modeLineView);
        setField(window, "_commandView", commandView);
        setField(window, "_commandMenuView", commandMenuView);
        setField(window, "_size", Size.create(width, height));
        setField(window, "_bufferContext", bufferContext);
        var bufferContextsByView = new IdentityHashMap<BufferView, org.fisk.swim.text.BufferContext>();
        bufferContextsByView.put(bufferContext.getBufferView(), bufferContext);
        setField(window, "_bufferContextsByView", bufferContextsByView);
        var bufferViewCounts = new IdentityHashMap<org.fisk.swim.text.BufferContext, Integer>();
        bufferViewCounts.put(bufferContext, 1);
        setField(window, "_bufferViewCounts", bufferViewCounts);

        var normalMode = new NormalMode(window);
        var inputMode = new InputMode(window);
        var visualMode = new VisualMode(window);
        var visualLineMode = new VisualLineMode(window);
        var visualBlockMode = new VisualBlockMode(window);

        setField(window, "_normalMode", normalMode);
        setField(window, "_inputMode", inputMode);
        setField(window, "_visualMode", visualMode);
        setField(window, "_visualLineMode", visualLineMode);
        setField(window, "_visualBlockMode", visualBlockMode);
        setField(window, "_currentMode", normalMode);

        bufferContext.getBufferView().setFirstResponder(normalMode);
        window.refreshChromeState();

        return new HeadlessWindowHarness(window);
    }

    public Window getWindow() {
        return _window;
    }

    public static Response dispatch(EventResponder responder, KeyStroke... keys) {
        var response = responder.processEvent(new KeyStrokes(List.of(keys)));
        if (response == Response.YES) {
            responder.respond();
        }
        return response;
    }

    public static KeyStroke key(char character) {
        return new KeyStroke(character, false, false);
    }

    public static KeyStroke ctrl(char character) {
        return new KeyStroke(character, true, false);
    }

    public static KeyStroke escape() {
        return new KeyStroke(KeyType.Escape);
    }

    public static KeyStroke enter() {
        return new KeyStroke(KeyType.Enter);
    }

    public static KeyStroke tab() {
        return new KeyStroke(KeyType.Tab);
    }

    public static KeyStroke backspace() {
        return new KeyStroke(KeyType.Backspace);
    }

    public static KeyStroke up() {
        return new KeyStroke(KeyType.ArrowUp);
    }

    public static KeyStroke down() {
        return new KeyStroke(KeyType.ArrowDown);
    }

    public static KeyStroke pageUp() {
        return new KeyStroke(KeyType.PageUp);
    }

    public static KeyStroke pageDown() {
        return new KeyStroke(KeyType.PageDown);
    }

    public static KeyStroke reverseTab() {
        return new KeyStroke(KeyType.ReverseTab);
    }

    public static KeyStroke left() {
        return new KeyStroke(KeyType.ArrowLeft);
    }

    public static KeyStroke right() {
        return new KeyStroke(KeyType.ArrowRight);
    }

    public static <T> T getField(Object target, String name, Class<T> type) {
        try {
            Field field = getDeclaredField(target.getClass(), name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getField(Object target, String name) {
        return getField(target, name, Object.class);
    }

    @Override
    public void close() {
        if (_window != null) {
            _window.dispose();
        }
    }

    private static Unsafe lookupUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            return type.cast(UNSAFE.allocateInstance(type));
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = getDeclaredField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setStaticField(Class<?> type, String name, Object value) {
        try {
            Field field = getDeclaredField(type, name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field getDeclaredField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
