package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.event.IOThread;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwimAppImplTest {
    @TempDir
    java.nio.file.Path tempDir;

    @AfterEach
    void tearDown() {
        TerminalContext.shutdownInstance();
        EventThread.shutdownInstance();
        SwimRuntime.clear();
    }

    @Test
    void startSetsHostCreatesWindowStartsThreadsAndRefreshesOnEvents() {
        FakeBindings bindings = new FakeBindings();
        SwimAppImpl app = new SwimAppImpl(bindings);
        RecordingHost host = new RecordingHost();
        Path path = Path.of("/tmp/start.txt");

        app.start(path, host);

        assertSame(host, bindings.host);
        assertEquals(path, bindings.createdWindowPath);
        assertEquals(List.of(true), bindings.window.updateCalls);
        assertTrue(bindings.eventThread.started);
        assertTrue(bindings.ioThread.started);

        bindings.eventThread.fireOnEvent();

        assertEquals(List.of(true, false), bindings.window.updateCalls);
    }

    @Test
    void refreshAndGetCurrentPathDelegateToWindow() {
        FakeBindings bindings = new FakeBindings();
        bindings.window.currentPath = Path.of("/tmp/current.txt");
        SwimAppImpl app = new SwimAppImpl(bindings);

        app.refresh(false);

        assertEquals(List.of(false), bindings.window.updateCalls);
        assertEquals(Path.of("/tmp/current.txt"), app.getCurrentPath());
    }

    @Test
    void showMessageEnqueuesWhenEventThreadIsAlive() {
        FakeBindings bindings = new FakeBindings();
        bindings.eventThread.alive = true;
        SwimAppImpl app = new SwimAppImpl(bindings);

        app.showMessage("queued");

        assertNull(bindings.window.message);
        assertEquals(1, bindings.eventThread.enqueued.size());

        bindings.eventThread.runQueued();

        assertEquals("queued", bindings.window.message);
    }

    @Test
    void showMessageUpdatesImmediatelyWhenEventThreadIsStopped() {
        FakeBindings bindings = new FakeBindings();
        bindings.eventThread.alive = false;
        SwimAppImpl app = new SwimAppImpl(bindings);

        app.showMessage("direct");

        assertEquals("direct", bindings.window.message);
        assertTrue(bindings.eventThread.enqueued.isEmpty());
    }

    @Test
    void closeInterruptsIoThreadAndShutsDownRuntimeServices() {
        FakeBindings bindings = new FakeBindings();
        SwimAppImpl app = new SwimAppImpl(bindings);

        app.start(Path.of("/tmp/close.txt"), new RecordingHost());
        app.close();

        assertTrue(bindings.ioThread.interrupted);
        assertTrue(bindings.shutdownJavaLspCalled);
        assertTrue(bindings.shutdownEventThreadCalled);
        assertTrue(bindings.window.disposed);
        assertTrue(bindings.shutdownTerminalContextCalled);
        assertTrue(bindings.clearRuntimeCalled);
    }

    @Test
    void defaultRuntimeBindingsDelegateToWindowEventThreadAndRuntimeStatics() throws Exception {
        var bindings = createDefaultBindings();
        TerminalContextTestSupport.install(80, 24);
        Path path = tempDir.resolve("bindings.txt");
        java.nio.file.Files.writeString(path, "hello");

        Object windowAccess = invoke(bindings, "createWindow", new Class<?>[] { Path.class }, path);
        Object currentWindowAccess = invoke(bindings, "getWindow", new Class<?>[0]);
        assertSame(windowAccess.getClass(), currentWindowAccess.getClass());
        assertEquals(path, invoke(windowAccess, "getCurrentPath", new Class<?>[0]));

        var rootView = org.fisk.swim.ui.Window.getInstance().getRootView();
        setField(rootView, "_needsRedraw", false);
        invoke(windowAccess, "update", new Class<?>[] { boolean.class }, false);

        invoke(windowAccess, "setMessage", new Class<?>[] { String.class }, "hello");
        assertEquals("hello", HeadlessWindowHarness.getField(org.fisk.swim.ui.Window.getInstance().getCommandView(), "_message", String.class));

        RecordingHost host = new RecordingHost();
        invoke(bindings, "setHost", new Class<?>[] { SwimHost.class }, host);
        SwimRuntime.reload();
        assertEquals(path, host.reloadPath);

        Object eventThreadAccess = invoke(bindings, "getEventThread", new Class<?>[0]);
        var ran = new CountDownLatch(1);
        invoke(eventThreadAccess, "addOnEvent", new Class<?>[] { Runnable.class }, (Runnable) ran::countDown);
        invoke(eventThreadAccess, "start", new Class<?>[0]);
        invoke(eventThreadAccess, "enqueue", new Class<?>[] { Runnable.class }, (Runnable) () -> {});
        assertTrue(ran.await(2, TimeUnit.SECONDS));

        Thread ioThread = (Thread) invoke(bindings, "createIoThread", new Class<?>[0]);
        assertInstanceOf(IOThread.class, ioThread);
        assertTrue(ioThread.isDaemon());

        invoke(bindings, "shutdownJavaLsp", new Class<?>[0]);
        invoke(bindings, "shutdownEventThread", new Class<?>[0]);
        invoke(windowAccess, "dispose", new Class<?>[0]);
        invoke(bindings, "shutdownTerminalContext", new Class<?>[0]);
        invoke(bindings, "clearRuntime", new Class<?>[0]);

        assertNull(org.fisk.swim.ui.Window.getInstance());
    }

    private static Object createDefaultBindings() throws Exception {
        Class<?> type = Class.forName("org.fisk.swim.SwimAppImpl$DefaultRuntimeBindings");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class FakeBindings implements SwimAppImpl.RuntimeBindings {
        private final FakeWindow window = new FakeWindow();
        private final FakeEventThread eventThread = new FakeEventThread();
        private final FakeThread ioThread = new FakeThread();
        private SwimHost host;
        private Path createdWindowPath;
        private boolean shutdownJavaLspCalled;
        private boolean shutdownEventThreadCalled;
        private boolean shutdownTerminalContextCalled;
        private boolean clearRuntimeCalled;

        @Override
        public void setHost(SwimHost host) {
            this.host = host;
        }

        @Override
        public SwimAppImpl.WindowAccess createWindow(Path path) {
            createdWindowPath = path;
            window.currentPath = path;
            return window;
        }

        @Override
        public SwimAppImpl.WindowAccess getWindow() {
            return window;
        }

        @Override
        public SwimAppImpl.EventThreadAccess getEventThread() {
            return eventThread;
        }

        @Override
        public Thread createIoThread() {
            return ioThread;
        }

        @Override
        public void shutdownJavaLsp() {
            shutdownJavaLspCalled = true;
        }

        @Override
        public void shutdownEventThread() {
            shutdownEventThreadCalled = true;
        }

        @Override
        public void shutdownTerminalContext() {
            shutdownTerminalContextCalled = true;
        }

        @Override
        public void clearRuntime() {
            clearRuntimeCalled = true;
        }
    }

    private static final class FakeWindow implements SwimAppImpl.WindowAccess {
        private final List<Boolean> updateCalls = new ArrayList<>();
        private Path currentPath;
        private String message;
        private boolean disposed;

        @Override
        public void update(boolean forced) {
            updateCalls.add(forced);
        }

        @Override
        public Path getCurrentPath() {
            return currentPath;
        }

        @Override
        public void setMessage(String message) {
            this.message = message;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static final class FakeEventThread implements SwimAppImpl.EventThreadAccess {
        private final List<Runnable> onEvent = new ArrayList<>();
        private final List<Runnable> enqueued = new ArrayList<>();
        private boolean alive;
        private boolean started;

        @Override
        public void addOnEvent(Runnable runnable) {
            onEvent.add(runnable);
        }

        @Override
        public void start() {
            started = true;
            alive = true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void enqueue(Runnable runnable) {
            enqueued.add(runnable);
        }

        void fireOnEvent() {
            for (var runnable : onEvent) {
                runnable.run();
            }
        }

        void runQueued() {
            for (var runnable : enqueued) {
                runnable.run();
            }
        }
    }

    private static final class FakeThread extends Thread {
        private boolean started;
        private boolean interrupted;

        @Override
        public synchronized void start() {
            started = true;
        }

        @Override
        public void interrupt() {
            interrupted = true;
        }
    }

    private static final class RecordingHost implements SwimHost {
        private Path reloadPath;

        @Override
        public void requestReload(Path path) {
            reloadPath = path;
        }

        @Override
        public void requestRebuildAndReload(Path path) {
        }

        @Override
        public void requestExit() {
        }

        @Override
        public Path getBuildRoot() {
            return null;
        }
    }
}
