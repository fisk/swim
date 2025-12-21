package org.fisk.swim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.fisk.swim.event.IOThread;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class Swim {
    private static final Logger _log = LogFactory.createLog();

    private static void setupLogging() {
        try {
            File file = new File("/tmp/swim.log");
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            System.setErr(ps);
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
