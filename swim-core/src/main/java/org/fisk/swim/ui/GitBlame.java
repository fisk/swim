package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reads the compact per-line attribution used by a buffer's blame fringe. */
final class GitBlame {
    private static final long TIMEOUT_SECONDS = 15;

    private GitBlame() {
    }

    static List<String> load(Path path) throws IOException, InterruptedException {
        if (path == null || path.getParent() == null) {
            return List.of();
        }
        var process = new ProcessBuilder("git", "blame", "--line-porcelain", "--", path.getFileName().toString())
                .directory(path.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
        } finally {
            process.destroy();
        }
        return process.exitValue() == 0 ? parse(output) : List.of();
    }

    static List<String> parse(String porcelain) {
        var annotations = new ArrayList<String>();
        String revision = null;
        String author = null;
        for (String line : porcelain == null ? List.<String>of() : porcelain.lines().toList()) {
            if (line.startsWith("\t")) {
                annotations.add(format(revision, author));
                revision = null;
                author = null;
            } else if (line.startsWith("author ")) {
                author = line.substring("author ".length());
            } else if (isRevisionHeader(line)) {
                int separator = line.indexOf(' ');
                revision = line.substring(0, separator);
            }
        }
        return List.copyOf(annotations);
    }

    private static String format(String revision, String author) {
        if (revision == null || revision.isBlank() || revision.chars().allMatch(character -> character == '0')) {
            return "Not committed";
        }
        String abbreviated = revision.length() > 8 ? revision.substring(0, 8) : revision;
        return author == null || author.isBlank() ? abbreviated : abbreviated + " " + author;
    }

    private static boolean isRevisionHeader(String line) {
        int separator = line.indexOf(' ');
        if (separator < 8) return false;
        String revision = line.substring(0, separator);
        if (revision.charAt(0) == '^') revision = revision.substring(1);
        return revision.length() >= 8 && revision.chars().allMatch(character -> Character.digit(character, 16) >= 0);
    }
}
