package org.fisk.swim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.config.LoggerConfig;

import org.fisk.swim.event.IOThread;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;

public class Swim {
    private static Logger _log;

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

    private static void setupWindow(Path path) {
        Window.createInstance(path);
        var window = Window.getInstance();
        window.update(true /* forced */);
    }

    private static Path checkArguments(String[] args) {
        if (args.length != 1) {
            System.out.println("swim: Wrong number of arguments: " + args.length + ".");
            System.out.println("Try: swim <file_path>");
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
                System.out.println("swim: No such file: " + path.toString());
                return null;
            } else {
                return path;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            setupLogging();
            var path = checkArguments(args);
            if (path == null) {
                System.out.println("Did not find file at path: " + args[0]);
                return;
            }
            _log.info("swim started");
            setupWindow(path);
            var eventThread = EventThread.getInstance();
            eventThread.addOnEvent(() -> {
                Window.getInstance().update(false /* forced */);
            });
            eventThread.start();
            new IOThread(TerminalContext.getInstance().getScreen()).start();
        } catch (Exception e) {
            _log.error("Error starting: ", e);
        }
    }
}
