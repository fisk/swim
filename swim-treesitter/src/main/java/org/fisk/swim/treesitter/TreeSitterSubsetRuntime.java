package org.fisk.swim.treesitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Experimental Java executor for a small, explicit subset of generated
 * Tree-sitter grammars. It is not an LR/GLR Tree-sitter replacement: recursive
 * symbols and unsupported generated rule forms fail explicitly.
 */
public final class TreeSitterSubsetRuntime {
    public TreeSitterSyntaxTree parse(TreeSitterSyntaxSnapshot snapshot) {
        var compatibility = TreeSitterSubsetCompatibilityAnalyzer.analyze(snapshot.grammar());
        if (!compatibility.supported()) {
            return new TreeSitterSyntaxTree(snapshot, List.of(), compatibility.issues().stream()
                    .map(issue -> new TreeSitterParseError(0, issue.message() + " (" + issue.feature() + ")"))
                    .toList());
        }
        if (snapshot.startRule().isBlank()) {
            return new TreeSitterSyntaxTree(snapshot, List.of(), List.of(new TreeSitterParseError(0, "Grammar has no start rule")));
        }
        var parser = new Parser(snapshot);
        try {
            Match match = parser.namedRule(snapshot.startRule(), 0, "");
            int end = parser.skipExtras(match.end());
            if (end != snapshot.text().length()) {
                return new TreeSitterSyntaxTree(snapshot, match.spans(),
                        List.of(new TreeSitterParseError(end, "Unexpected source text")));
            }
            return new TreeSitterSyntaxTree(snapshot, sort(match.spans()), List.of());
        } catch (ParseFailure failure) {
            return new TreeSitterSyntaxTree(snapshot, List.of(), List.of(new TreeSitterParseError(failure.offset, failure.getMessage())));
        } catch (UnsupportedFeature unsupported) {
            return new TreeSitterSyntaxTree(snapshot, List.of(),
                    List.of(new TreeSitterParseError(unsupported.offset, unsupported.getMessage())));
        }
    }

    private static List<TreeSitterSyntaxSpan> sort(List<TreeSitterSyntaxSpan> spans) {
        return spans.stream().sorted(Comparator.comparingInt(TreeSitterSyntaxSpan::start)
                .thenComparing((TreeSitterSyntaxSpan span) -> -span.end())).toList();
    }

    private static final class Parser {
        private final TreeSitterSyntaxSnapshot snapshot;
        private final ArrayDeque<String> symbols = new ArrayDeque<>();

        private Parser(TreeSitterSyntaxSnapshot snapshot) { this.snapshot = snapshot; }

        private Match namedRule(String name, int offset, String field) {
            if (symbols.contains(name)) throw unsupported(offset, "recursive symbol '" + name + "'");
            TreeSitterGrammarRule rule = snapshot.grammar().rules().get(name);
            if (rule == null) throw new ParseFailure(offset, "Unknown grammar symbol '" + name + "'");
            symbols.push(name);
            try {
                int start = skipExtras(offset);
                Match inner = rule(rule, start, field);
                var spans = new ArrayList<TreeSitterSyntaxSpan>(inner.spans());
                spans.add(new TreeSitterSyntaxSpan(name, field, start, inner.end()));
                return new Match(inner.end(), spans);
            } finally {
                symbols.pop();
            }
        }

        private Match rule(TreeSitterGrammarRule rule, int offset, String field) {
            int start = skipExtras(offset);
            return switch (rule.type()) {
            case "STRING" -> literal(rule.attribute("value"), start, field);
            case "PATTERN" -> pattern(rule.attribute("value"), start, field);
            case "SYMBOL" -> namedRule(rule.attribute("name"), start, field);
            case "SEQ" -> sequence(rule.children("members"), start, field);
            case "CHOICE" -> choice(rule.children("members"), start, field);
            case "REPEAT" -> repeat(single(rule, "content", start), start, field, false);
            case "REPEAT1" -> repeat(single(rule, "content", start), start, field, true);
            case "OPTIONAL" -> optional(single(rule, "content", start), start, field);
            case "FIELD" -> rule(single(rule, "content", start), start, rule.attribute("name"));
            case "ALIAS" -> alias(single(rule, "content", start), start, field, rule.attribute("value"));
            case "TOKEN", "PREC", "PREC_LEFT", "PREC_RIGHT", "PREC_DYNAMIC", "IMMEDIATE_TOKEN" ->
                    rule(single(rule, "content", start), start, field);
            case "BLANK" -> new Match(start, List.of());
            default -> throw unsupported(start, "generated rule type '" + rule.type() + "'");
            };
        }

        private TreeSitterGrammarRule single(TreeSitterGrammarRule rule, String child, int offset) {
            var values = rule.children(child);
            if (values.size() != 1) throw unsupported(offset, "'" + rule.type() + "' requires one " + child + " rule");
            return values.getFirst();
        }

        private Match literal(String value, int offset, String field) {
            if (value.isEmpty() || !snapshot.text().startsWith(value, offset)) throw new ParseFailure(offset, "Expected '" + value + "'");
            return new Match(offset + value.length(), List.of(new TreeSitterSyntaxSpan("STRING", field, offset, offset + value.length())));
        }

        private Match pattern(String source, int offset, String field) {
            if (source.isEmpty()) throw unsupported(offset, "empty PATTERN rule");
            var matcher = Pattern.compile(source).matcher(snapshot.text());
            matcher.region(offset, snapshot.text().length());
            if (!matcher.lookingAt()) throw new ParseFailure(offset, "Expected pattern '" + source + "'");
            if (matcher.end() == offset) throw unsupported(offset, "zero-width PATTERN rule");
            return new Match(matcher.end(), List.of(new TreeSitterSyntaxSpan("PATTERN", field, offset, matcher.end())));
        }

        private Match sequence(List<TreeSitterGrammarRule> members, int offset, String field) {
            int end = offset;
            var spans = new ArrayList<TreeSitterSyntaxSpan>();
            for (TreeSitterGrammarRule member : members) {
                Match match = rule(member, end, field);
                end = match.end();
                spans.addAll(match.spans());
            }
            return new Match(end, spans);
        }

        private Match choice(List<TreeSitterGrammarRule> members, int offset, String field) {
            ParseFailure failure = new ParseFailure(offset, "No choice matched");
            Match best = null;
            for (TreeSitterGrammarRule member : members) {
                try {
                    Match match = rule(member, offset, field);
                    if (best == null || match.end() > best.end()) best = match;
                } catch (ParseFailure candidateFailure) {
                    if (candidateFailure.offset > failure.offset) failure = candidateFailure;
                }
            }
            if (best == null) throw failure;
            return best;
        }

        private Match repeat(TreeSitterGrammarRule content, int offset, String field, boolean atLeastOne) {
            int end = offset;
            int count = 0;
            var spans = new ArrayList<TreeSitterSyntaxSpan>();
            while (true) {
                try {
                    Match match = rule(content, end, field);
                    if (match.end() <= end) throw unsupported(end, "zero-width repeated rule");
                    end = match.end(); count++; spans.addAll(match.spans());
                } catch (ParseFailure failure) {
                    break;
                }
            }
            if (atLeastOne && count == 0) throw new ParseFailure(offset, "Expected one or more repeated values");
            return new Match(end, spans);
        }

        private Match optional(TreeSitterGrammarRule content, int offset, String field) {
            try { return rule(content, offset, field); }
            catch (ParseFailure failure) { return new Match(offset, List.of()); }
        }

        private Match alias(TreeSitterGrammarRule content, int offset, String field, String alias) {
            Match match = rule(content, offset, field);
            if (alias.isBlank()) return match;
            var spans = new ArrayList<TreeSitterSyntaxSpan>(match.spans());
            spans.add(new TreeSitterSyntaxSpan(alias, field, offset, match.end()));
            return new Match(match.end(), spans);
        }

        private int skipExtras(int offset) {
            int end = offset;
            boolean matched;
            do {
                matched = false;
                for (TreeSitterGrammarRule extra : snapshot.grammar().extras()) {
                    try {
                        Match match = ruleWithoutExtras(extra, end);
                        if (match.end() > end) { end = match.end(); matched = true; break; }
                    } catch (ParseFailure ignored) { }
                }
            } while (matched);
            return end;
        }

        private Match ruleWithoutExtras(TreeSitterGrammarRule rule, int offset) {
            return switch (rule.type()) {
            case "STRING" -> literal(rule.attribute("value"), offset, "");
            case "PATTERN" -> pattern(rule.attribute("value"), offset, "");
            case "SYMBOL" -> throw unsupported(offset, "SYMBOL extras");
            default -> throw unsupported(offset, "complex extra rule '" + rule.type() + "'");
            };
        }

        private UnsupportedFeature unsupported(int offset, String feature) {
            return new UnsupportedFeature(offset, "Subset runtime does not support " + feature);
        }
    }

    private record Match(int end, List<TreeSitterSyntaxSpan> spans) { }
    private static final class ParseFailure extends RuntimeException {
        private final int offset;
        private ParseFailure(int offset, String message) { super(message); this.offset = offset; }
    }
    private static final class UnsupportedFeature extends RuntimeException {
        private final int offset;
        private UnsupportedFeature(int offset, String message) { super(message); this.offset = offset; }
    }
}
