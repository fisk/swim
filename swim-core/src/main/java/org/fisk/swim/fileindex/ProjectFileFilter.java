package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;

public final class ProjectFileFilter {
    private static final List<String> IGNORED_DIRECTORIES = List.of("target", "build", "out", "node_modules");
    private static final Pattern IGNORED_FILE_PATTERN = Pattern.compile(".*\\.(class|jar)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    private final IgnoreNode _projectIgnoreRules;

    private ProjectFileFilter(IgnoreNode projectIgnoreRules) {
        _projectIgnoreRules = projectIgnoreRules;
    }

    public static ProjectFileFilter load(Path root) {
        return new ProjectFileFilter(loadProjectIgnoreRules(root));
    }

    public boolean isIncluded(Path relativePath, boolean directory) {
        if (relativePath == null) {
            return false;
        }
        if (isHiddenOrGenerated(relativePath, directory)) {
            return false;
        }
        return !isIgnoredByProject(relativePath, directory);
    }

    private boolean isHiddenOrGenerated(Path relativePath, boolean directory) {
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            String component = relativePath.getName(i).toString();
            if (component.startsWith(".")) {
                return true;
            }
            if (IGNORED_DIRECTORIES.contains(component)) {
                return true;
            }
        }
        if (directory) {
            return false;
        }
        String fileName = relativePath.getFileName() == null ? "" : relativePath.getFileName().toString();
        return IGNORED_FILE_PATTERN.matcher(fileName).matches();
    }

    private boolean isIgnoredByProject(Path relativePath, boolean directory) {
        if (_projectIgnoreRules == null || _projectIgnoreRules.getRules().isEmpty()) {
            return false;
        }
        String normalizedPath = normalize(relativePath);
        Boolean direct = _projectIgnoreRules.checkIgnored(normalizedPath, directory);
        if (direct != null) {
            return direct;
        }
        Path parent = relativePath.getParent();
        while (parent != null) {
            Boolean ancestor = _projectIgnoreRules.checkIgnored(normalize(parent), true);
            if (ancestor != null) {
                return ancestor;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static IgnoreNode loadProjectIgnoreRules(Path root) {
        if (root == null) {
            return new IgnoreNode();
        }
        Path marker = root.resolve(".swim");
        Path rulesPath = null;
        if (Files.isRegularFile(marker)) {
            rulesPath = marker;
        } else if (Files.isDirectory(marker) && Files.isRegularFile(marker.resolve("ignore"))) {
            rulesPath = marker.resolve("ignore");
        }
        if (rulesPath == null) {
            return new IgnoreNode();
        }
        var rules = new ArrayList<FastIgnoreRule>();
        try {
            for (String line : Files.readAllLines(rulesPath)) {
                String rule = parseRuleLine(line);
                if (rule == null) {
                    continue;
                }
                try {
                    var ignoreRule = new FastIgnoreRule(rule);
                    if (!ignoreRule.isEmpty()) {
                        rules.add(ignoreRule);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException e) {
            return new IgnoreNode();
        }
        return new IgnoreNode(rules);
    }

    private static String parseRuleLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        if (isConfigLine(trimmed)) {
            return null;
        }
        if (trimmed.startsWith("-") && trimmed.length() > 1 && !trimmed.startsWith("--")) {
            trimmed = trimmed.substring(1).strip();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isConfigLine(String trimmed) {
        int separator = trimmed.indexOf('=');
        if (separator < 0) {
            separator = trimmed.indexOf(':');
        }
        if (separator <= 0) {
            return false;
        }
        String key = trimmed.substring(0, separator).strip();
        return CONFIG_KEY_PATTERN.matcher(key).matches();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
