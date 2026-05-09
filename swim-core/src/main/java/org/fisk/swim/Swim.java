package org.fisk.swim;

import java.io.PrintStream;
import java.nio.file.Path;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

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
        try {
            // Get the current PID
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String fileName = "/tmp/swim-" + pid + ".log";
            
            // Programmatically add or update the File Appender
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            
            PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("%d [%t] %-5p %c - %m%n")
                .build();
            
            FileAppender appender = FileAppender.newBuilder()
                .setName("FileAppender")
                .withFileName(fileName)
                .setLayout(layout)
                .setConfiguration(config)
                .build();
            appender.start();
            config.addAppender(appender);
            
            // Attach to root logger so ALL loggers write here
            LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);

            // Remove all existing appenders
            for (String appenderName : loggerConfig.getAppenders().keySet()) {
                loggerConfig.removeAppender(appenderName);
            }

            loggerConfig.addAppender(appender, Level.DEBUG, null);
            
            // Ensure desired log level
            loggerConfig.setLevel(Level.DEBUG);
            
            context.updateLoggers();
            
            _log = LogManager.getLogger(Swim.class);
        } catch (Throwable e) {
        }
    }

    static Path checkArguments(String[] args, PrintStream out) {
        if (args.length != 1) {
            out.println("swim: Wrong number of arguments: " + args.length + ".");
            out.println("Try: swim <file_path>");
            return null;
        }

        try {
            var path = Path.of(args[0]);
            var file = path.toFile();
            if (!file.exists()) {
                try {
                    if (file.createNewFile()) {
                        return path;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                out.println("swim: No such file: " + path.toString());
                return null;
            } else {
                return path;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    static void run(String[] args, RuntimeBindings bindings, PrintStream out) {
        try {
            setupLogging();
            var path = checkArguments(args, out);
            if (path == null) {
                if (args.length == 1) {
                    out.println("Did not find file at path: " + args[0]);
                }
                return;
            }
            _log.info("swim started");
            var window = bindings.createWindow(path);
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
