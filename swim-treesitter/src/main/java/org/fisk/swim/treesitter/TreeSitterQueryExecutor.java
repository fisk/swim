package org.fisk.swim.treesitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Executes structural query patterns against the portable syntax-span model. */
public final class TreeSitterQueryExecutor {
    public List<TreeSitterQueryCapture> execute(TreeSitterQuery query, TreeSitterSyntaxTree tree) {
        if (query == null || tree == null) return List.of();
        var captures = new ArrayList<TreeSitterQueryCapture>();
        for (TreeSitterQueryNode pattern : query.patterns()) {
            for (TreeSitterSyntaxSpan span : tree.spans()) {
                var bindings = new LinkedHashMap<String, TreeSitterSyntaxSpan>();
                if (matches(pattern, span, tree, bindings) && predicatesMatch(pattern.predicates(), bindings, tree.snapshot().text())) {
                    bindings.forEach((name, boundSpan) -> captures.add(new TreeSitterQueryCapture(name, boundSpan)));
                }
            }
        }
        return captures.stream().sorted(Comparator.comparingInt(capture -> capture.span().start())).toList();
    }

    private boolean matches(TreeSitterQueryNode pattern, TreeSitterSyntaxSpan span, TreeSitterSyntaxTree tree,
            Map<String, TreeSitterSyntaxSpan> bindings) {
        if ("__alternation__".equals(pattern.type())) {
            for (TreeSitterQueryNode alternative : pattern.children()) {
                var attempt = new LinkedHashMap<>(bindings);
                if (matches(alternative, span, tree, attempt)) {
                    addCaptures(pattern, span, attempt);
                    bindings.clear(); bindings.putAll(attempt);
                    return true;
                }
            }
            return false;
        }
        if (!typeMatches(pattern.type(), span, tree.snapshot().text())) return false;
        addCaptures(pattern, span, bindings);
        for (TreeSitterQueryNode child : pattern.children()) {
            boolean found = false;
            for (TreeSitterSyntaxSpan candidate : tree.spans()) {
                if (candidate == span || candidate.start() < span.start() || candidate.end() > span.end()) continue;
                if (!child.field().isEmpty() && !child.field().equals(candidate.field())) continue;
                var attempt = new LinkedHashMap<>(bindings);
                if (matches(child, candidate, tree, attempt)) {
                    bindings.clear(); bindings.putAll(attempt); found = true; break;
                }
            }
            if (!found) return false;
        }
        return predicatesMatch(pattern.predicates(), bindings, tree.snapshot().text());
    }

    private static void addCaptures(TreeSitterQueryNode pattern, TreeSitterSyntaxSpan span,
            Map<String, TreeSitterSyntaxSpan> bindings) {
        pattern.captures().forEach(capture -> bindings.put(capture, span));
    }

    private static boolean typeMatches(String patternType, TreeSitterSyntaxSpan span, String text) {
        if (patternType.equals(span.type())) return true;
        if (patternType.length() >= 2 && patternType.startsWith("\"") && patternType.endsWith("\"")) {
            String literal = patternType.substring(1, patternType.length() - 1);
            return literal.equals(text.substring(span.start(), span.end()));
        }
        return false;
    }

    private static boolean predicatesMatch(List<TreeSitterQueryPredicate> predicates,
            Map<String, TreeSitterSyntaxSpan> bindings, String text) {
        for (TreeSitterQueryPredicate predicate : predicates) {
            List<String> args = predicate.arguments();
            if ("#match?".equals(predicate.name()) && args.size() >= 2) {
                TreeSitterSyntaxSpan span = bindings.get(args.getFirst());
                if (span == null || !Pattern.compile(unquote(args.get(1))).matcher(text.substring(span.start(), span.end())).find()) return false;
            } else if ("#eq?".equals(predicate.name()) && args.size() >= 2) {
                TreeSitterSyntaxSpan span = bindings.get(args.getFirst());
                if (span == null || !text.substring(span.start(), span.end()).equals(unquote(args.get(1)))) return false;
            }
        }
        return true;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
