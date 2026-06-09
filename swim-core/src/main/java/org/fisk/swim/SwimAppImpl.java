package org.fisk.swim;

import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.mail.MailPluginRegistry;
import org.fisk.swim.mail.MailUiSupport;
import org.fisk.swim.event.IOThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.ui.Window;

public class SwimAppImpl implements SwimApp {
    private static final Logger LOG = LogManager.getLogger(SwimAppImpl.class);

    interface WindowAccess {
        void update(boolean forced);
        Path getCurrentPath();
        void setMessage(String message);
        default void checkpointForReload() {
        }
        void dispose();
    }

    interface EventThreadAccess {
        void addOnEvent(Runnable runnable);
        void start();
        boolean isAlive();
        void enqueue(Runnable runnable);
    }

    interface RuntimeBindings {
        void setHost(SwimHost host);
        WindowAccess createWindow(Path path);
        default WindowAccess createWindow(List<Path> paths) {
            return createWindow(paths == null || paths.isEmpty() ? null : paths.getFirst());
        }
        WindowAccess getWindow();
        EventThreadAccess getEventThread();
        Thread createIoThread();
        void preloadMailPlugin(Path path);
        void shutdownEventThread();
        void shutdownTerminalContext();
        void clearRuntime();
    }

    private static final class DefaultRuntimeBindings implements RuntimeBindings {
        private static final class DefaultWindowAccess implements WindowAccess {
            @Override
            public void update(boolean forced) {
                Window.getInstance().update(forced);
            }

            @Override
            public Path getCurrentPath() {
                return Window.getInstance().getBufferContext().getBuffer().getPath();
            }

            @Override
            public void setMessage(String message) {
                Window.getInstance().getCommandView().setMessage(message);
            }

            @Override
            public void checkpointForReload() {
                Window.getInstance().saveSessionForReload();
            }

            @Override
            public void dispose() {
                Window.getInstance().dispose();
            }
        }

        private static final class DefaultEventThreadAccess implements EventThreadAccess {
            @Override
            public void addOnEvent(Runnable runnable) {
                EventThread.getInstance().addOnEvent(runnable);
            }

            @Override
            public void start() {
                EventThread.getInstance().start();
            }

            @Override
            public boolean isAlive() {
                return EventThread.getInstance().isAlive();
            }

            @Override
            public void enqueue(Runnable runnable) {
                EventThread.getInstance().enqueue(new RunnableEvent(runnable));
            }
        }

        private static final WindowAccess WINDOW = new DefaultWindowAccess();
        private static final EventThreadAccess EVENT_THREAD = new DefaultEventThreadAccess();

        @Override
        public void setHost(SwimHost host) {
            SwimRuntime.setHost(host);
        }

        @Override
        public WindowAccess createWindow(Path path) {
            Window.createInstance(path);
            return WINDOW;
        }

        @Override
        public WindowAccess createWindow(List<Path> paths) {
            Window.createInstance(paths);
            return WINDOW;
        }

        @Override
        public WindowAccess getWindow() {
            return Window.getInstance() == null ? null : WINDOW;
        }

        @Override
        public EventThreadAccess getEventThread() {
            return EVENT_THREAD;
        }

        @Override
        public Thread createIoThread() {
            return new IOThread(TerminalContext.getInstance().getScreen());
        }

        @Override
        public void preloadMailPlugin(Path path) {
            if (MailPluginRegistry.getClient() != null) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    SwimRuntime.loadPlugin(MailUiSupport.PLUGIN_ID, path);
                } catch (RuntimeException e) {
                    LOG.debug("Background mail preload failed", e);
                }
            }, "mail-plugin-preload");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void shutdownEventThread() {
            EventThread.shutdownInstance();
        }

        @Override
        public void shutdownTerminalContext() {
            TerminalContext.shutdownInstance();
        }

        @Override
        public void clearRuntime() {
            SwimRuntime.clear();
        }
    }

    private final RuntimeBindings _bindings;
    private Thread _ioThread;

    public SwimAppImpl() {
        this(new DefaultRuntimeBindings());
    }

    SwimAppImpl(RuntimeBindings bindings) {
        _bindings = bindings;
    }

    @Override
    public void start(Path path, SwimHost host) {
        start(path == null ? List.of() : List.of(path), host);
    }

    @Override
    public void start(List<Path> paths, SwimHost host) {
        SwimLogging.setup();
        _bindings.setHost(host);
        List<Path> launchPaths = paths == null ? List.of() : List.copyOf(paths);
        var window = _bindings.createWindow(launchPaths);
        window.update(true);
        var eventThread = _bindings.getEventThread();
        eventThread.addOnEvent(() -> {
            var currentWindow = _bindings.getWindow();
            if (currentWindow != null) {
                currentWindow.update(false);
            }
        });
        eventThread.start();
        _ioThread = _bindings.createIoThread();
        if (!_ioThread.isAlive()) {
            _ioThread.start();
        }
        _bindings.preloadMailPlugin(launchPaths.isEmpty() ? null : launchPaths.getFirst());
        LOG.info("swim started");
    }

    @Override
    public void refresh(boolean forced) {
        var window = _bindings.getWindow();
        if (window != null) {
            window.update(forced);
        }
    }

    @Override
    public Path getCurrentPath() {
        return _bindings.getWindow().getCurrentPath();
    }

    @Override
    public void showMessage(String message) {
        var eventThread = _bindings.getEventThread();
        if (eventThread.isAlive()) {
            eventThread.enqueue(() -> {
                var window = _bindings.getWindow();
                if (window != null) {
                    window.setMessage(message);
                }
            });
        } else {
            var window = _bindings.getWindow();
            if (window != null) {
                window.setMessage(message);
            }
        }
    }

    @Override
    public void checkpointForReload() {
        var window = _bindings.getWindow();
        if (window != null) {
            window.checkpointForReload();
        }
    }

    @Override
    public void close() {
        Thread ioThread = _ioThread;
        if (_ioThread != null) {
            _ioThread.interrupt();
            _ioThread = null;
        }
        _bindings.shutdownEventThread();
        var window = _bindings.getWindow();
        if (window != null) {
            window.dispose();
        }
        _bindings.shutdownTerminalContext();
        if (ioThread != null && Thread.currentThread() != ioThread) {
            try {
                ioThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        _bindings.clearRuntime();
    }
}
