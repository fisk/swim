package org.fisk.swim.lsp.cpp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Include-block ordering compatible with HotSpot's SortIncludes jtreg test. */
final class CppIncludeSorter {
    private static final String INCLUDE_LINE = "^ *# *include *(<[^>]+>|\"[^\"]+\") *$\\n";
    private static final String BLANK_LINE = "^$\\n";
    private static final Pattern INCLUDE_BLOCK = Pattern.compile(
            String.format("%s(?:(?:%s)*%s)*", INCLUDE_LINE, BLANK_LINE, INCLUDE_LINE), Pattern.MULTILINE);

    private CppIncludeSorter() { }

    static String sort(Path path, String source) {
        if (path == null || source == null || source.isEmpty() || !ClangdLspPluginSupport.isCppPath(path)) return source;
        Matcher matcher = INCLUDE_BLOCK.matcher(source);
        var result = new StringBuilder(source.length());
        int end = 0;
        while (matcher.find()) {
            result.append(source, end, matcher.start());
            result.append(sortBlock(path, matcher.group()));
            end = matcher.end();
        }
        if (end == 0) return source;
        return result.append(source, end, source.length()).toString();
    }

    private static String sortBlock(Path path, String block) {
        String[] lines = block.split("\\n");
        SortedSet<String> userIncludes = new TreeSet<>(includeComparator('"'));
        SortedSet<String> systemIncludes = new TreeSet<>(includeComparator('<'));
        var result = new ArrayList<String>(lines.length);
        String expectedHeader = expectedInlineHeader(path);

        for (String line : lines) {
            if (line.indexOf('"') >= 0) {
                if (expectedHeader != null && expectedHeader.endsWith(extract(line, '"', '"'))) result.add(line);
                else userIncludes.add(line);
            } else if (line.indexOf('<') >= 0) {
                systemIncludes.add(line);
            }
        }
        if (!result.isEmpty() && (!userIncludes.isEmpty() || !systemIncludes.isEmpty())) result.add("");
        result.addAll(userIncludes);
        if (!userIncludes.isEmpty() && !systemIncludes.isEmpty()) result.add("");
        result.addAll(systemIncludes);
        return String.join("\n", result) + "\n";
    }

    private static Comparator<String> includeComparator(char delimiter) {
        return Comparator.comparing(line -> line.toLowerCase(Locale.ROOT).substring(line.indexOf(delimiter)));
    }

    private static String expectedInlineHeader(Path path) {
        String value = path.toString();
        int inline = value.lastIndexOf(".inline.");
        if (inline < 1 || inline + ".inline.".length() >= value.length()) return null;
        String header = value.substring(0, inline) + value.substring(inline + ".inline".length());
        return File.separatorChar == '/' ? header : header.replace(File.separatorChar, '/');
    }

    private static String extract(String line, char start, char end) {
        int startIndex = line.indexOf(start);
        int endIndex = line.indexOf(end, startIndex + 1);
        if (startIndex < 0 || endIndex < 0) throw new IllegalArgumentException(line);
        return line.substring(startIndex + 1, endIndex);
    }
}
