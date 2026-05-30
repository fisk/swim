package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.fisk.swim.event.IOThread;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwimTest {
    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (org.fisk.swim.ui.Window.getInstance() != null) {
            org.fisk.swim.ui.Window.getInstance().dispose();
        }
        TerminalContext.shutdownInstance();
        EventThread.shutdownInstance();
    }

    @Test
    void runStartsWindowAndThreadsAndRefreshesOnEvents() throws Exception {
        FakeBindings bindings = new FakeBindings();
        Path path = Files.createFile(tempDir.resolve("existing.txt"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Swim.run(new String[] { path.toString() }, bindings, new PrintStream(output, true));

        assertEquals(path, bindings.createdWindowPath);
        assertEquals(List.of(true), bindings.window.updateCalls);
        assertTrue(bindings.eventThread.started);
        assertTrue(bindings.ioThread.started);
        assertEquals("", output.toString());

        bindings.eventThread.fireOnEvent();

        assertEquals(List.of(true, false), bindings.window.updateCalls);
    }

    @Test
    void checkArgumentsCreatesMissingFile() {
        Path path = tempDir.resolve("created.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Path result = Swim.checkArguments(new String[] { path.toString() }, new PrintStream(output, true));

        assertEquals(path, result);
        assertTrue(Files.exists(path));
        assertEquals("", output.toString());
    }

    @Test
    void runWithNoArgumentsStartsUntitledBuffer() {
        FakeBindings bindings = new FakeBindings();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Swim.run(new String[0], bindings, new PrintStream(output, true));

        assertNull(bindings.createdWindowPath);
        assertEquals(List.of(true), bindings.window.updateCalls);
        assertTrue(bindings.eventThread.started);
        assertTrue(output.toString().isEmpty());
    }

    @Test
    void runPrintsPathWhenFileCannotBeCreated() {
        FakeBindings bindings = new FakeBindings();
        Path path = tempDir.resolve("missing").resolve("file.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Swim.run(new String[] { path.toString() }, bindings, new PrintStream(output, true));

        assertNull(bindings.createdWindowPath);
        assertTrue(bindings.window.updateCalls.isEmpty());
        assertTrue(output.toString().contains("Did not find file at path: " + path));
    }

    @Test
    void runSwallowsStartupExceptions() throws Exception {
        FakeBindings bindings = new FakeBindings();
        bindings.failCreateWindow = true;
        Path path = Files.createFile(tempDir.resolve("boom.txt"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertDoesNotThrow(() ->
            Swim.run(new String[] { path.toString() }, bindings, new PrintStream(output, true))
        );
        assertTrue(bindings.window.updateCalls.isEmpty());
        assertEquals("", output.toString());
    }

    @Test
    void defaultRuntimeBindingsCreateWindowEventThreadAndIoThread() throws Exception {
        TerminalContextTestSupport.install(80, 24);
        Path path = Files.createFile(tempDir.resolve("default-bindings.txt"));
        Object bindings = createDefaultBindings();

        Object windowAccess = invoke(bindings, "createWindow", new Class<?>[] { Path.class }, path);
        invoke(windowAccess, "update", new Class<?>[] { boolean.class }, false);

        var rootView = org.fisk.swim.ui.Window.getInstance().getRootView();
        assertFalse(rootView.needsRedraw());

        Object eventThreadAccess = invoke(bindings, "getEventThread", new Class<?>[0]);
        var latch = new CountDownLatch(1);
        invoke(eventThreadAccess, "addOnEvent", new Class<?>[] { Runnable.class }, (Runnable) latch::countDown);
        invoke(eventThreadAccess, "start", new Class<?>[0]);
        EventThread.getInstance().enqueue(new org.fisk.swim.event.RunnableEvent(() -> {}));
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        Thread ioThread = (Thread) invoke(bindings, "createIoThread", new Class<?>[0]);
        assertInstanceOf(IOThread.class, ioThread);
        assertTrue(ioThread.isDaemon());
    }

    private static Object createDefaultBindings() throws Exception {
        Class<?> type = Class.forName("org.fisk.swim.Swim$DefaultRuntimeBindings");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class FakeBindings implements Swim.RuntimeBindings {
        private final FakeWindow window = new FakeWindow();
        private final FakeEventThread eventThread = new FakeEventThread();
        private final FakeThread ioThread = new FakeThread();
        private Path createdWindowPath;
        private boolean failCreateWindow;

        @Override
        public Swim.WindowAccess createWindow(Path path) {
            if (failCreateWindow) {
                throw new IllegalStateException("boom");
            }
            createdWindowPath = path;
            return window;
        }

        @Override
        public Swim.EventThreadAccess getEventThread() {
            return eventThread;
        }

        @Override
        public Thread createIoThread() {
            return ioThread;
        }
    }

    private static final class FakeWindow implements Swim.WindowAccess {
        private final List<Boolean> updateCalls = new ArrayList<>();

        @Override
        public void update(boolean forced) {
            updateCalls.add(forced);
        }
    }

    private static final class FakeEventThread implements Swim.EventThreadAccess {
        private final List<Runnable> onEvent = new ArrayList<>();
        private boolean started;

        @Override
        public void addOnEvent(Runnable runnable) {
            onEvent.add(runnable);
        }

        @Override
        public void start() {
            started = true;
        }

        void fireOnEvent() {
            for (var runnable : onEvent) {
                runnable.run();
            }
        }
    }

    private static final class FakeThread extends Thread {
        private boolean started;

        @Override
        public synchronized void start() {
            started = true;
        }
    }
}
