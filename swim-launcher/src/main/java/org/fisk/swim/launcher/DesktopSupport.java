package org.fisk.swim.launcher;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class DesktopSupport {
    private DesktopSupport() {
    }

    public static boolean openUri(URI uri) {
        if (uri == null) {
            return false;
        }
        if (browseWithDesktop(uri)) {
            return true;
        }
        List<String> command = browserCommand(System.getProperty("os.name", ""), uri.toString());
        return command != null && start(command);
    }

    public static boolean copyText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (copyWithDesktop(text)) {
            return true;
        }
        for (var command : clipboardCommands(System.getProperty("os.name", ""))) {
            if (pipeTo(command, text)) {
                return true;
            }
        }
        return false;
    }

    static List<String> browserCommand(String osName, String target) {
        String normalizedOs = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac")) {
            return List.of("/usr/bin/open", target);
        }
        if (normalizedOs.contains("win")) {
            return List.of(resolveWindowsExecutable("rundll32.exe", "rundll32"), "url.dll,FileProtocolHandler", target);
        }
        return List.of(resolveExecutable("/usr/bin/xdg-open", "/bin/xdg-open", "xdg-open"), target);
    }

    static List<List<String>> clipboardCommands(String osName) {
        String normalizedOs = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac")) {
            return List.of(List.of(resolveExecutable("/usr/bin/pbcopy", "pbcopy")));
        }
        if (normalizedOs.contains("win")) {
            return List.of(List.of(resolveWindowsExecutable("clip.exe", "clip")));
        }
        return List.of(
                List.of(resolveExecutable("/usr/bin/wl-copy", "/bin/wl-copy", "wl-copy")),
                List.of(resolveExecutable("/usr/bin/xclip", "/bin/xclip", "xclip"), "-selection", "clipboard"));
    }

    private static boolean browseWithDesktop(URI uri) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(uri);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean copyWithDesktop(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean start(List<String> command) {
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean pipeTo(List<String> command, String text) {
        try {
            Process process = new ProcessBuilder(command).start();
            try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(text);
            }
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String resolveExecutable(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (candidate.contains("/") || candidate.contains("\\")) {
                if (Files.isExecutable(Path.of(candidate))) {
                    return candidate;
                }
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static String resolveWindowsExecutable(String executable, String fallback) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path path = Path.of(systemRoot, "System32", executable);
            if (Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return fallback;
    }
}
