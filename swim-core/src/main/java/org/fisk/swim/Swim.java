package org.fisk.swim;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.fisk.swim.event.IOThread;
import org.fisk.swim.terminal.TerminalContext;

public class Swim {
    interface WindowAccess {
        void update(boolean forced);
    }

    interface EventThreadAccess {
        void addOnEvent(Runnable runnable);
        void start();
    }

    interface RuntimeBindings {
        WindowAccess createWindow(Path path);
        default WindowAccess createWindow(List<Path> paths) {
            return createWindow(paths == null || paths.isEmpty() ? null : paths.getFirst());
        }
        EventThreadAccess getEventThread();
        Thread createIoThread();
    }

    private static final class DefaultRuntimeBindings implements RuntimeBindings {
        @Override
        public WindowAccess createWindow(Path path) {
            org.fisk.swim.ui.Window.createInstance(path);
            var window = org.fisk.swim.ui.Window.getInstance();
            return window::update;
        }

        @Override
        public WindowAccess createWindow(List<Path> paths) {
            org.fisk.swim.ui.Window.createInstance(paths);
            var window = org.fisk.swim.ui.Window.getInstance();
            return window::update;
        }

        @Override
        public EventThreadAccess getEventThread() {
            var eventThread = EventThread.getInstance();
            return new EventThreadAccess() {
                @Override
                public void addOnEvent(Runnable runnable) {
                    eventThread.addOnEvent(runnable);
                }

                @Override
                public void start() {
                    eventThread.start();
                }
            };
        }

        @Override
        public Thread createIoThread() {
            return new IOThread(TerminalContext.getInstance().getScreen());
        }
    }

    private static Logger _log = LogManager.getLogger(Swim.class);

    private static void setupLogging() {
        SwimLogging.setup();
        _log = LogManager.getLogger(Swim.class);
    }

    static Path checkArguments(String[] args, PrintStream out) {
        if (args.length != 1) {
            out.println("swim: Wrong number of arguments: " + args.length + ".");
            out.println("Try: swim [file_path ...]");
            return null;
        }

        List<Path> paths = checkArgumentPaths(args, out);
        return paths == null || paths.isEmpty() ? null : paths.getFirst();
    }

    static List<Path> checkArgumentPaths(String[] args, PrintStream out) {
        var paths = new ArrayList<Path>();
        try {
            for (String arg : args) {
                var path = Path.of(arg);
                var file = path.toFile();
                if (!file.exists()) {
                    try {
                        if (file.createNewFile()) {
                            paths.add(path);
                            continue;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    out.println("swim: No such file: " + path.toString());
                    return null;
                } else {
                    paths.add(path);
                }
            }
            return List.copyOf(paths);
        } catch (Throwable e) {
            return null;
        }
    }

    static void run(String[] args, RuntimeBindings bindings, PrintStream out) {
        try {
            setupLogging();
            List<Path> paths = List.of();
            if (args.length > 0) {
                paths = checkArgumentPaths(args, out);
                if (paths == null) {
                    out.println("Did not find file at path: " + args[0]);
                    return;
                }
            }
            _log.info("swim started");
            var window = bindings.createWindow(paths);
            window.update(true /* forced */);
            var eventThread = bindings.getEventThread();
            eventThread.addOnEvent(() -> {
                window.update(false /* forced */);
            });
            eventThread.start();
            bindings.createIoThread().start();
        } catch (Exception e) {
            _log.error("Error starting: ", e);
        }
    }

    public static void main(String[] args) {
        run(args, new DefaultRuntimeBindings(), System.out);
    }
}
