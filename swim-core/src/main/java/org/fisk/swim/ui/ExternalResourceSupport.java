package org.fisk.swim.ui;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.fisk.swim.copy.Copy;

final class ExternalResourceSupport {
    private ExternalResourceSupport() {
    }

    static boolean openUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("mac")) {
            command = List.of("open", url);
        } else if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else {
            command = List.of("xdg-open", url);
        }
        return start(command);
    }

    static boolean copyText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Copy.getInstance().setText(text, false);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return pipeTo(List.of("pbcopy"), text);
        }
        if (os.contains("win")) {
            return pipeTo(List.of("clip"), text);
        }
        if (pipeTo(List.of("wl-copy"), text)) {
            return true;
        }
        return pipeTo(List.of("xclip", "-selection", "clipboard"), text);
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
}
