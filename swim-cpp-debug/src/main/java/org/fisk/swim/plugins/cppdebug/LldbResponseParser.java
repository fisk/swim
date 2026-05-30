package org.fisk.swim.plugins.cppdebug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fisk.swim.debug.DebugFrameInfo;
import org.fisk.swim.debug.DebugSourceLocation;
import org.fisk.swim.debug.DebugThreadInfo;
import org.fisk.swim.debug.DebugVariable;

final class LldbResponseParser {
    private static final Pattern BREAKPOINT_ID = Pattern.compile("Breakpoint\\s+(\\d+):");
    private static final Pattern LOCATION = Pattern.compile("at\\s+(.+?):(\\d+)(?::(\\d+))?");
    private static final Pattern THREAD = Pattern.compile("^\\s*(\\*)?\\s*thread\\s+#(\\d+).*", Pattern.MULTILINE);
    private static final Pattern FRAME = Pattern.compile("^\\s*(?:\\*)?\\s*frame\\s+#(\\d+):\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern VARIABLE = Pattern.compile("^\\(([^)]+)\\)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$",
            Pattern.MULTILINE);

    private LldbResponseParser() {
    }

    static String breakpointId(String response) {
        Matcher matcher = BREAKPOINT_ID.matcher(response);
        return matcher.find() ? matcher.group(1) : null;
    }

    static boolean terminated(String response) {
        return response.contains("exited with status") || response.contains("Debugger terminated")
                || response.contains("Process must be launched");
    }

    static DebugSourceLocation sourceLocation(String response, Path sourceRoot) {
        Matcher matcher = LOCATION.matcher(response);
        if (!matcher.find()) {
            return null;
        }
        Path path = Path.of(matcher.group(1));
        if (!path.isAbsolute() && sourceRoot != null) {
            path = sourceRoot.resolve(path).normalize();
        }
        int line = Integer.parseInt(matcher.group(2));
        int column = matcher.group(3) == null ? 1 : Integer.parseInt(matcher.group(3));
        String function = null;
        int tick = response.indexOf('`');
        if (tick >= 0) {
            int nextSpace = response.indexOf(' ', tick + 1);
            if (nextSpace > tick) {
                function = response.substring(tick + 1, nextSpace);
            }
        }
        return new DebugSourceLocation(path, line, column, function);
    }

    static List<DebugThreadInfo> threads(String response) {
        var result = new ArrayList<DebugThreadInfo>();
        Matcher matcher = THREAD.matcher(response);
        while (matcher.find()) {
            boolean selected = "*".equals(matcher.group(1));
            int number = Integer.parseInt(matcher.group(2));
            String full = matcher.group().trim();
            result.add(new DebugThreadInfo(Integer.toString(number), full, selected));
        }
        return List.copyOf(result);
    }

    static List<DebugFrameInfo> frames(String response, Path sourceRoot) {
        var result = new ArrayList<DebugFrameInfo>();
        Matcher matcher = FRAME.matcher(response);
        while (matcher.find()) {
            String frameId = matcher.group(1);
            String label = matcher.group(2);
            DebugSourceLocation location = sourceLocation(matcher.group(), sourceRoot);
            result.add(new DebugFrameInfo(frameId, label, location));
        }
        return List.copyOf(result);
    }

    static List<DebugVariable> variables(String response) {
        var result = new ArrayList<DebugVariable>();
        Matcher matcher = VARIABLE.matcher(response);
        while (matcher.find()) {
            result.add(new DebugVariable(matcher.group(2), matcher.group(1), matcher.group(3)));
        }
        return List.copyOf(result);
    }
}
