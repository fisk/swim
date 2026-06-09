package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class GitProjectSwimConfig {
    private static final String VIEW_PREFIX = "git.pr.view.";
    private static final String VIEW_REMOTE_SUFFIX = ".remote";
    private static final String VIEW_FILTER_SUFFIX = ".filter";
    private static final String VIEW_QUERY_SUFFIX = ".query";
    private static final String VIEW_FILTER_NAME_SUFFIX = ".filter.name";
    private static final String VIEW_FILTER_LABELS_SUFFIX = ".filter.labels";
    private static final String VIEW_FILTER_AUTHOR_SUFFIX = ".filter.author";
    private static final String VIEW_HEADER = "# Git pull request views";
    private static final String LEGACY_FILTER_PREFIX = "git.pr.filter.";
    private static final String LEGACY_FILTER_HEADER = "# Git pull request filters";

    private GitProjectSwimConfig() {
    }

    static List<GitPullRequestSavedView> loadPullRequestViews(Path repositoryRoot) {
        Path path = configPath(repositoryRoot);
        if (path == null || !Files.isRegularFile(path)) {
            path = legacyConfigPath(repositoryRoot);
            if (path == null || !Files.isRegularFile(path)) {
                return List.of();
            }
        }
        try {
            var views = new LinkedHashMap<String, ViewBuilder>();
            for (String line : Files.readAllLines(path)) {
                parseViewLine(line, views);
            }
            return views.values().stream()
                    .map(ViewBuilder::build)
                    .filter(GitPullRequestSavedView::isUsable)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static void savePullRequestViews(Path repositoryRoot, List<GitPullRequestSavedView> views)
            throws IOException {
        Path path = configPath(repositoryRoot);
        if (path == null) {
            return;
        }
        Files.createDirectories(path.getParent());
        var lines = Files.isRegularFile(path) ? new ArrayList<>(Files.readAllLines(path)) : new ArrayList<String>();
        lines.removeIf(GitProjectSwimConfig::isViewLineOrHeader);

        var usable = views == null ? List.<GitPullRequestSavedView>of() : views.stream()
                .filter(GitPullRequestSavedView::isUsable)
                .toList();
        if (!usable.isEmpty()) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) {
                lines.add("");
            }
            lines.add(VIEW_HEADER);
            for (GitPullRequestSavedView view : usable) {
                lines.add(VIEW_PREFIX + view.name() + VIEW_REMOTE_SUFFIX + " = " + view.remoteName());
                lines.add(VIEW_PREFIX + view.name() + VIEW_FILTER_NAME_SUFFIX + " = " + view.filters().name());
                lines.add(VIEW_PREFIX + view.name() + VIEW_FILTER_LABELS_SUFFIX + " = " + view.filters().labels());
                lines.add(VIEW_PREFIX + view.name() + VIEW_FILTER_AUTHOR_SUFFIX + " = " + view.filters().author());
            }
        }
        Files.write(path, lines);
    }

    private static Path configPath(Path repositoryRoot) {
        if (repositoryRoot == null) {
            return null;
        }
        Path marker = repositoryRoot.resolve(".swim");
        return Files.isDirectory(marker) ? marker.resolve("git-pr-views") : marker;
    }

    private static Path legacyConfigPath(Path repositoryRoot) {
        if (repositoryRoot == null) {
            return null;
        }
        Path marker = repositoryRoot.resolve(".swim");
        return Files.isDirectory(marker) ? marker.resolve("git-pr-filters") : marker;
    }

    private static void parseViewLine(String line, LinkedHashMap<String, ViewBuilder> views) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        int separator = trimmed.indexOf('=');
        if (separator < 0) {
            separator = trimmed.indexOf(':');
        }
        if (separator < 0) {
            return;
        }
        String key = trimmed.substring(0, separator).strip();
        String value = trimmed.substring(separator + 1).strip();
        if (key.startsWith(LEGACY_FILTER_PREFIX)) {
            String name = key.substring(LEGACY_FILTER_PREFIX.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).setLegacyQuery(value);
            }
            return;
        }
        if (!key.startsWith(VIEW_PREFIX)) {
            return;
        }
        String suffix = key.substring(VIEW_PREFIX.length()).strip();
        if (suffix.endsWith(VIEW_REMOTE_SUFFIX)) {
            String name = suffix.substring(0, suffix.length() - VIEW_REMOTE_SUFFIX.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).remoteName = value;
            }
        } else if (suffix.endsWith(VIEW_FILTER_NAME_SUFFIX)) {
            String name = suffix.substring(0, suffix.length() - VIEW_FILTER_NAME_SUFFIX.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).filterName = value;
            }
        } else if (suffix.endsWith(VIEW_FILTER_LABELS_SUFFIX)) {
            String name = suffix.substring(0, suffix.length() - VIEW_FILTER_LABELS_SUFFIX.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).filterLabels = value;
            }
        } else if (suffix.endsWith(VIEW_FILTER_AUTHOR_SUFFIX)) {
            String name = suffix.substring(0, suffix.length() - VIEW_FILTER_AUTHOR_SUFFIX.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).filterAuthor = value;
            }
        } else if (suffix.endsWith(VIEW_FILTER_SUFFIX) || suffix.endsWith(VIEW_QUERY_SUFFIX)) {
            String matchedSuffix = suffix.endsWith(VIEW_FILTER_SUFFIX) ? VIEW_FILTER_SUFFIX : VIEW_QUERY_SUFFIX;
            String name = suffix.substring(0, suffix.length() - matchedSuffix.length()).strip();
            if (!name.isBlank()) {
                views.computeIfAbsent(name, ViewBuilder::new).setLegacyQuery(value);
            }
        }
    }

    private static boolean isViewLineOrHeader(String line) {
        String trimmed = line == null ? "" : line.strip();
        return VIEW_HEADER.equals(trimmed)
                || LEGACY_FILTER_HEADER.equals(trimmed)
                || trimmed.startsWith(VIEW_PREFIX)
                || trimmed.startsWith(LEGACY_FILTER_PREFIX);
    }

    private static final class ViewBuilder {
        private final String name;
        private String remoteName = "";
        private String filterName = "";
        private String filterLabels = "";
        private String filterAuthor = "";

        private ViewBuilder(String name) {
            this.name = name;
        }

        private void setLegacyQuery(String query) {
            GitPullRequestFilters filters = GitPullRequestFilters.fromLegacyQuery(query);
            filterName = filters.name();
            filterLabels = filters.labels();
            filterAuthor = filters.author();
        }

        private GitPullRequestSavedView build() {
            return new GitPullRequestSavedView(name, remoteName, filterName, filterLabels, filterAuthor);
        }
    }
}
