package org.fisk.swim.plugins.git;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

record GitPullRequestFilters(String name, String labels, String author) {
    GitPullRequestFilters {
        name = clean(name);
        labels = clean(labels);
        author = clean(author);
    }

    static GitPullRequestFilters empty() {
        return new GitPullRequestFilters("", "", "");
    }

    static GitPullRequestFilters fromLegacyQuery(String query) {
        if (query == null || query.isBlank()) {
            return empty();
        }
        String name = "";
        String labels = "";
        String author = "";
        for (GitHubPullRequestFilter.Term term : GitHubPullRequestFilter.displayTerms(query)) {
            if (term.negated()) {
                continue;
            }
            switch (term.field()) {
            case TITLE, ANY -> name = append(name, term.value());
            case LABEL -> labels = append(labels, term.value());
            case AUTHOR -> author = append(author, term.value());
            default -> {
            }
            }
        }
        return new GitPullRequestFilters(name, labels, author);
    }

    boolean isBlank() {
        return name.isBlank() && labels.isBlank() && author.isBlank();
    }

    boolean matches(GitHubPullRequest pullRequest) {
        return matchesName(pullRequest)
                && matchesLabels(pullRequest)
                && matchesAuthor(pullRequest);
    }

    private boolean matchesName(GitHubPullRequest pullRequest) {
        return name.isBlank() || contains(pullRequest.title(), name);
    }

    private boolean matchesLabels(GitHubPullRequest pullRequest) {
        for (String label : labelTerms()) {
            boolean matched = pullRequest.labels().stream()
                    .anyMatch(value -> contains(value, label));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAuthor(GitHubPullRequest pullRequest) {
        if (author.isBlank()) {
            return true;
        }
        if (author.contains("@")) {
            return contains(pullRequest.author(), author.replace("@", ""));
        }
        String displayName = pullRequest.authorName().isBlank() ? pullRequest.author() : pullRequest.authorName();
        return contains(displayName, author);
    }

    private List<String> labelTerms() {
        if (labels.isBlank()) {
            return List.of();
        }
        String[] terms = labels.contains(",") ? labels.split(",") : labels.split("\\s+");
        return Arrays.stream(terms)
                .map(String::strip)
                .filter(term -> !term.isBlank())
                .toList();
    }

    private static boolean contains(String haystack, String needle) {
        return normalize(haystack).contains(normalize(needle));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String append(String existing, String value) {
        if (value == null || value.isBlank()) {
            return existing;
        }
        return existing.isBlank() ? value.strip() : existing + " " + value.strip();
    }
}
