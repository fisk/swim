package org.fisk.swim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

final class SwimLogging {
    static final String LOG_DIR_PROPERTY = "swim.log.dir";
    static final String LOG_PATH_PROPERTY = "swim.log.path";

    private static final String APPENDER_NAME = "SwimFileAppender";

    private SwimLogging() {
    }

    static Path setup() {
        Path logPath = resolveLogPath();
        try {
            Files.createDirectories(logPath.getParent());

            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            LoggerConfig rootLogger = config.getRootLogger();
            var existingAppender = config.getAppender(APPENDER_NAME);
            if (existingAppender instanceof FileAppender fileAppender
                    && logPath.toString().equals(fileAppender.getFileName())) {
                System.setProperty(LOG_PATH_PROPERTY, logPath.toString());
                return logPath;
            }
            if (existingAppender != null) {
                rootLogger.removeAppender(APPENDER_NAME);
                existingAppender.stop();
                config.getAppenders().remove(APPENDER_NAME);
            }
            for (String appenderName : List.copyOf(rootLogger.getAppenders().keySet())) {
                rootLogger.removeAppender(appenderName);
            }

            PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern("%d [%t] %-5p %c - %m%n")
                    .build();
            FileAppender appender = FileAppender.newBuilder()
                    .setName(APPENDER_NAME)
                    .withFileName(logPath.toString())
                    .withAppend(true)
                    .setLayout(layout)
                    .setConfiguration(config)
                    .build();
            appender.start();
            config.addAppender(appender);
            rootLogger.addAppender(appender, Level.DEBUG, null);
            rootLogger.setLevel(Level.DEBUG);
            context.updateLoggers();
            System.setProperty(LOG_PATH_PROPERTY, logPath.toString());
            return logPath;
        } catch (Throwable e) {
            System.err.println("swim: failed to initialize logging at " + logPath + ": " + e);
            return logPath;
        }
    }

    static Path resolveLogPath() {
        return resolveLogDirectory().resolve("swim-" + ProcessHandle.current().pid() + ".log");
    }

    private static Path resolveLogDirectory() {
        String configured = System.getProperty(LOG_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path tmp = Path.of("/tmp");
        if (Files.isDirectory(tmp)) {
            return tmp;
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }
}
