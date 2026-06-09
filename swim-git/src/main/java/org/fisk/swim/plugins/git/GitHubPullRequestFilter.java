package org.fisk.swim.plugins.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GitHubPullRequestFilter {
    enum Field {
        ANY,
        TITLE,
        AUTHOR,
        LABEL,
        NUMBER,
        BRANCH
    }

    record Term(Field field, String value, boolean negated, String displayText) {
    }

    private final List<Term> _terms;

    private GitHubPullRequestFilter(List<Term> terms) {
        _terms = List.copyOf(terms);
    }

    static GitHubPullRequestFilter parse(String query) {
        var terms = new ArrayList<Term>();
        for (String token : tokenize(query)) {
            Term term = parseToken(token);
            if (term != null) {
                terms.add(term);
            }
        }
        return new GitHubPullRequestFilter(terms);
    }

    static List<Term> displayTerms(String query) {
        return parse(query)._terms;
    }

    boolean isBlank() {
        return _terms.isEmpty();
    }

    boolean matches(GitHubPullRequest pullRequest) {
        for (Term term : _terms) {
            boolean matched = matches(pullRequest, term);
            if (term.negated() ? matched : !matched) {
                return false;
            }
        }
        return true;
    }

    private static Term parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String display = rawToken.strip();
        boolean negated = false;
        String token = display;
        while (token.startsWith("-") || token.startsWith("!")) {
            negated = !negated;
            token = token.substring(1).stripLeading();
        }
        if (token.isBlank()) {
            return null;
        }
        if (token.startsWith("#") && token.length() > 1) {
            return new Term(Field.NUMBER, token.substring(1), negated, display);
        }
        int separator = token.indexOf(':');
        if (separator <= 0) {
            return new Term(Field.ANY, token, negated, display);
        }
        String key = token.substring(0, separator).toLowerCase(Locale.ROOT);
        String value = token.substring(separator + 1).strip();
        if (value.isBlank()) {
            return null;
        }
        Field field = switch (key) {
        case "title", "t" -> Field.TITLE;
        case "author", "user", "username", "by" -> Field.AUTHOR;
        case "label", "labels", "l" -> Field.LABEL;
        case "number", "pr", "id" -> Field.NUMBER;
        case "branch", "ref" -> Field.BRANCH;
        default -> Field.ANY;
        };
        return new Term(field, value, negated, display);
    }

    private static boolean matches(GitHubPullRequest pullRequest, Term term) {
        String value = normalize(term.value());
        return switch (term.field()) {
        case TITLE -> contains(pullRequest.title(), value);
        case AUTHOR -> contains(pullRequest.author(), stripAt(value))
                || contains(pullRequest.authorName(), stripAt(value));
        case LABEL -> pullRequest.labels().stream().anyMatch(label -> contains(label, value));
        case NUMBER -> Integer.toString(pullRequest.number()).equals(stripHash(value));
        case BRANCH -> contains(pullRequest.headRef(), value) || contains(pullRequest.baseRef(), value);
        case ANY -> searchable(pullRequest).contains(value);
        };
    }

    private static String searchable(GitHubPullRequest pullRequest) {
        return normalize("#" + pullRequest.number() + " "
                + pullRequest.title() + " "
                + pullRequest.author() + " "
                + pullRequest.authorName() + " "
                + pullRequest.headRef() + " "
                + pullRequest.baseRef() + " "
                + String.join(" ", pullRequest.labels()));
    }

    private static boolean contains(String haystack, String needle) {
        return normalize(haystack).contains(needle);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String stripAt(String value) {
        return value.startsWith("@") ? value.substring(1) : value;
    }

    private static String stripHash(String value) {
        return value.startsWith("#") ? value.substring(1) : value;
    }

    private static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                appendToken(tokens, current);
                continue;
            }
            current.append(c);
        }
        appendToken(tokens, current);
        return List.copyOf(tokens);
    }

    private static void appendToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
