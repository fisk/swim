package org.fisk.swim;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Runtime-owned interception; restored when the application closes or reloads. */
final class ErrorCapture implements AutoCloseable {
    private static ErrorCapture instance;
    private static final String APPENDER = "SwimErrorReports";
    private final PrintStream previousErr = System.err;
    private final Thread.UncaughtExceptionHandler previousUncaught = Thread.getDefaultUncaughtExceptionHandler();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("swim-error-capture").factory());
    private final StderrOutput stderr = new StderrOutput();
    private final PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8);
    private final Thread.UncaughtExceptionHandler uncaught = (thread, failure) ->
            LogManager.getLogger("swim.uncaught").error("Uncaught exception on " + thread.getName(), failure);
    private final java.util.logging.Logger julRoot = java.util.logging.Logger.getLogger("");
    private final java.util.logging.Logger julLsp = java.util.logging.Logger.getLogger("org.eclipse.lsp4j");
    private final boolean previousLspPropagation = julLsp.getUseParentHandlers();
    private final java.util.List<Handler> previousRootConsole = java.util.Arrays.stream(julRoot.getHandlers())
            .filter(handler -> handler instanceof ConsoleHandler).toList();
    private final java.util.List<Handler> previousLspConsole = java.util.Arrays.stream(julLsp.getHandlers())
            .filter(handler -> handler instanceof ConsoleHandler).toList();
    private final Handler jul = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            int severity = record.getLevel().intValue();
            Level level = severity >= 1000 ? Level.ERROR : severity >= 900 ? Level.WARN
                    : severity >= 800 ? Level.INFO : Level.DEBUG;
            String message = new java.util.logging.SimpleFormatter().formatMessage(record);
            LogManager.getLogger(record.getLoggerName() == null ? "java.util.logging" : record.getLoggerName())
                    .log(level, message, record.getThrown());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Runnable julConfiguration = this::configureJavaLogging;
    private final AbstractAppender appender = new AbstractAppender(APPENDER, null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            String operation = event.getLoggerName() + ": " + event.getMessage().getFormattedMessage();
            if (event.getThrown() != null) {
                ErrorReports.getInstance().capture(operation, event.getThreadName(), event.getThrown());
            } else if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                ErrorReports.getInstance().captureText(event.getLoggerName(), event.getThreadName(), operation);
            }
        }
    };
    private volatile boolean closed;

    static synchronized void install() {
        if (instance == null) {
            instance = new ErrorCapture();
        } else {
            instance.attachAppender();
        }
    }

    static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private ErrorCapture() {
        ErrorReports.getInstance().enablePersistence();
        attachAppender();
        jul.setLevel(java.util.logging.Level.ALL);
        configureJavaLogging();
        java.util.logging.LogManager.getLogManager().addConfigurationListener(julConfiguration);
        Thread.setDefaultUncaughtExceptionHandler(uncaught);
        System.setErr(capturedErr);
    }

    private void attachAppender() {
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        if (!appender.isStarted()) {
            appender.start();
        }
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.WARN, null);
        context.updateLoggers();
    }

    private void configureJavaLogging() {
        if (closed) {
            return;
        }
        // NetBeans reconfigures JUL while starting. A named LSP handler also keeps
        // transport exceptions away from its subsequently installed root handlers.
        for (var logger : new java.util.logging.Logger[] {julRoot, julLsp}) {
            for (Handler handler : logger.getHandlers()) {
                if (handler instanceof ConsoleHandler) {
                    logger.removeHandler(handler);
                }
            }
            if (java.util.Arrays.stream(logger.getHandlers()).noneMatch(handler -> handler == jul)) {
                logger.addHandler(jul);
            }
        }
        julLsp.setUseParentHandlers(false);
    }

    @Override
    public void close() {
        closed = true;
        if (System.err == capturedErr) {
            System.setErr(previousErr);
        }
        if (Thread.getDefaultUncaughtExceptionHandler() == uncaught) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaught);
        }
        java.util.logging.LogManager.getLogManager().removeConfigurationListener(julConfiguration);
        julRoot.removeHandler(jul);
        julLsp.removeHandler(jul);
        julLsp.setUseParentHandlers(previousLspPropagation);
        previousRootConsole.forEach(julRoot::addHandler);
        previousLspConsole.forEach(julLsp::addHandler);
        stderr.drain();
        scheduler.shutdownNow();
        var context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().getRootLogger().removeAppender(APPENDER);
        context.getConfiguration().getAppenders().remove(APPENDER);
        appender.stop();
        context.updateLoggers();
        ErrorReports.getInstance().clear();
    }

    private final class StderrOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private ScheduledFuture<?> pending;
        private String thread;
        private boolean truncated;

        @Override
        public synchronized void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            if (bytes.size() == 0) {
                thread = Thread.currentThread().getName();
            }
            int accepted = Math.min(length, 64 * 1024 - bytes.size());
            bytes.write(data, offset, accepted);
            truncated |= accepted < length;
            if (pending != null) {
                pending.cancel(false);
            }
            if (!closed) {
                pending = scheduler.schedule(this::drain, 150, TimeUnit.MILLISECONDS);
            }
        }

        void drain() {
            String text;
            String sourceThread;
            synchronized (this) {
                if (bytes.size() == 0) {
                    return;
                }
                text = bytes.toString(StandardCharsets.UTF_8) + (truncated ? "\n[truncated]" : "");
                sourceThread = thread;
                bytes.reset();
                truncated = false;
            }
            LogManager.getLogger("swim.stderr").warn("{}", text.stripTrailing());
            if (!closed && (text.contains("Exception") || text.contains("Error") || text.contains("\tat "))) {
                ErrorReports.getInstance().captureText("Standard error", sourceThread, text);
            }
        }
    }
}
