package org.fisk.swim.event;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.View;
import org.fisk.swim.ui.Window;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.input.KeyStroke;

import sun.misc.Unsafe;

final class EventTestSupport {
    private static final Unsafe UNSAFE = lookupUnsafe();

    private EventTestSupport() {
    }

    static TestContext createContext(Path tempDir, String text) throws IOException {
        Path path = tempDir.resolve("event-" + text.hashCode() + ".txt");
        Files.writeString(path, text);

        var bufferContext = new BufferContext(Rect.create(0, 0, 80, 20), path);
        var rootView = new View(Rect.create(0, 0, 80, 20));
        rootView.addSubview(bufferContext.getBufferView());
        rootView.setFirstResponder(bufferContext.getBufferView());

        var window = allocate(Window.class);
        setField(window, "_rootView", rootView);
        setField(window, "_bufferContext", bufferContext);
        setStaticField(Window.class, "_instance", window);

        return new TestContext(window, bufferContext);
    }

    static KeyStrokes keys(KeyStroke... keys) {
        return new KeyStrokes(List.of(keys));
    }

    static void clearWindow() {
        setStaticField(Window.class, "_instance", null);
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
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setStaticField(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    record TestContext(Window window, BufferContext bufferContext) {
    }
}
