package org.fisk.swim.help;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ManPageDocument {
    private static final DateTimeFormatter MAN_PAGE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private ManPageDocument() {
    }

    public static String render() {
        var lines = new ArrayList<String>();
        lines.add(".TH SWIM 1 \"" + MAN_PAGE_DATE_FORMAT.format(LocalDate.now()) + "\" \"SWIM\" \"User Commands\"");
        lines.add(".SH NAME");
        lines.add("swim \\- terminal editor with persistent sessions and reloadable plugins");
        lines.add(".SH SYNOPSIS");
        lines.add(".B swim");
        lines.add(".RI [ file ...]");
        lines.add(".br");
        lines.add(".B swim");
        lines.add(".B --attach");
        lines.add(".I session");
        lines.add(".br");
        lines.add(".B swim");
        lines.add(".B --kill-session");
        lines.add(".RI [ session ]");
        lines.add(".SH DESCRIPTION");
        paragraph(lines, "swim starts the SWIM terminal editor. Normal launches attach the terminal client "
                + "to a background SWIM session server. If the named session is not already running, the server "
                + "starts an editor app process using the runtime version selected by the client.");
        paragraph(lines, "File arguments are opened by the editor app. Relative file paths are resolved from the "
                + "client working directory.");
        paragraph(lines, "For the complete editor manual, run :help inside SWIM. The EDITOR HELP section below is "
                + "generated from that same in-editor help document.");
        lines.add(".SH OPTIONS");
        taggedParagraph(lines, "--attach session",
                "Attach this terminal client to an existing or new named session without passing file launch arguments.");
        taggedParagraph(lines, "--kill-session [session]",
                "Ask the session server to terminate the named session process tree. When session is omitted, the "
                        + "current SWIM_SESSION environment value is used, falling back to default.");
        taggedParagraph(lines, "--swim-kill-session [session]", "Compatibility alias for --kill-session.");
        lines.add(".SH SESSIONS");
        paragraph(lines, "SWIM keeps editor apps alive behind a small launcher client. Detaching a client leaves "
                + "the app process running; exiting the last editor tab exits the app process and removes the "
                + "server-side session.");
        taggedParagraph(lines, ":sessions", "List live sessions.");
        taggedParagraph(lines, ":session name", "Move the current terminal client to another named session.");
        taggedParagraph(lines, ":detach", "Detach the client without killing the server-side session.");
        lines.add(".SH ENVIRONMENT");
        taggedParagraph(lines, "SWIM_SESSION",
                "Default session name used by normal launches and by --kill-session when no session argument is supplied.");
        taggedParagraph(lines, "SWIM_SERVER_SOCKET", "Override the Unix-domain socket used to contact the session server.");
        lines.add(".SH FILES");
        taggedParagraph(lines, "~/.swim/bin/swim", "Installed launcher intended for PATH.");
        taggedParagraph(lines, "~/.swim/share/man/man1/swim.1",
                "Installed man page paired with the launcher path.");
        taggedParagraph(lines, "~/.swim/plugins", "Installed core and plugin runtime artifacts.");
        taggedParagraph(lines, "~/.swim/config.json", "Editor configuration.");
        taggedParagraph(lines, "~/.swim/session.json", "Reload and rebuild session snapshot.");
        lines.add(".SH EDITOR HELP");
        paragraph(lines, "This section is generated from the in-editor :help document.");
        for (HelpDocument.Chapter chapter : HelpDocument.chapters()) {
            lines.add(".SS " + escape(chapter.title()));
            paragraph(lines, chapter.summary());
            for (HelpDocument.Section section : chapter.sections()) {
                lines.add(".TP");
                lines.add(".B " + escape(section.title()));
                sectionParagraphs(lines, section.paragraphs());
                example(lines, section.example());
            }
        }
        lines.add(".SH EXIT STATUS");
        taggedParagraph(lines, "0", "The launcher completed successfully.");
        taggedParagraph(lines, "non-zero", "Startup, attach, control-command, or terminal setup failed.");
        lines.add(".SH SEE ALSO");
        lines.add(".BR java (1)");
        return String.join("\n", lines) + "\n";
    }

    private static void taggedParagraph(List<String> lines, String tag, String text) {
        lines.add(".TP");
        lines.add(".B " + escape(tag));
        wrappedText(lines, text);
    }

    private static void paragraph(List<String> lines, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!previousLineIsHeading(lines)) {
            lines.add(".PP");
        }
        wrappedText(lines, text);
    }

    private static void sectionParagraphs(List<String> lines, List<String> paragraphs) {
        boolean first = true;
        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.isBlank()) {
                continue;
            }
            if (first) {
                wrappedText(lines, paragraph);
                first = false;
            } else {
                paragraph(lines, paragraph);
            }
        }
    }

    private static void example(List<String> lines, String example) {
        if (example == null || example.isBlank()) {
            return;
        }
        lines.add(".PP");
        lines.add(".B Example:");
        lines.add(".RS");
        lines.add(".nf");
        for (String line : example.split("\\R", -1)) {
            lines.add(escape(line));
        }
        lines.add(".fi");
        lines.add(".RE");
    }

    private static String escape(String text) {
        String escaped = text == null ? "" : text.replace("\\", "\\e");
        if (escaped.startsWith(".") || escaped.startsWith("'")) {
            escaped = "\\&" + escaped;
        }
        return escaped;
    }

    private static void wrappedText(List<String> lines, String text) {
        String remaining = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        while (remaining.length() > 78) {
            int breakAt = remaining.lastIndexOf(' ', 78);
            if (breakAt <= 0) {
                breakAt = remaining.indexOf(' ');
            }
            if (breakAt <= 0) {
                break;
            }
            lines.add(escape(remaining.substring(0, breakAt)));
            remaining = remaining.substring(breakAt + 1);
        }
        if (!remaining.isBlank()) {
            lines.add(escape(remaining));
        }
    }

    private static boolean previousLineIsHeading(List<String> lines) {
        if (lines.isEmpty()) {
            return false;
        }
        String previous = lines.getLast();
        return previous.startsWith(".SH ") || previous.startsWith(".SS ");
    }
}
