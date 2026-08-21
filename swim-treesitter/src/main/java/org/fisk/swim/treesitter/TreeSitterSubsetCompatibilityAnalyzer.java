package org.fisk.swim.treesitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Identifies constructs that the experimental Java subset runtime must not silently accept. */
public final class TreeSitterSubsetCompatibilityAnalyzer {
    private static final Set<String> SUPPORTED = Set.of(
            "STRING", "PATTERN", "SYMBOL", "SEQ", "CHOICE", "REPEAT", "REPEAT1", "OPTIONAL",
            "FIELD", "ALIAS", "TOKEN", "PREC", "PREC_LEFT", "PREC_RIGHT", "PREC_DYNAMIC", "IMMEDIATE_TOKEN", "BLANK");

    private TreeSitterSubsetCompatibilityAnalyzer() {
    }

    public static TreeSitterSubsetCompatibility analyze(TreeSitterGrammar grammar) {
        if (grammar == null) return new TreeSitterSubsetCompatibility(List.of(
                new TreeSitterSubsetCompatibility.Issue("", "grammar", "Grammar is required")));
        var issues = new ArrayList<TreeSitterSubsetCompatibility.Issue>();
        if (!grammar.externals().isEmpty()) {
            issues.add(new TreeSitterSubsetCompatibility.Issue("", "externals",
                    "External scanners require a future Java or native implementation"));
        }
        for (TreeSitterGrammarRule extra : grammar.extras()) {
            if (!"STRING".equals(extra.type()) && !"PATTERN".equals(extra.type())) {
                issues.add(new TreeSitterSubsetCompatibility.Issue("", extra.type(), "Only STRING and PATTERN extras are supported"));
            }
        }
        for (var entry : grammar.rules().entrySet()) inspect(entry.getKey(), entry.getValue(), issues);
        for (String rule : grammar.rules().keySet()) detectCycles(grammar, rule, new ArrayDeque<>(), new HashSet<>(), issues);
        return new TreeSitterSubsetCompatibility(issues);
    }

    private static void inspect(String ruleName, TreeSitterGrammarRule rule, List<TreeSitterSubsetCompatibility.Issue> issues) {
        if (!SUPPORTED.contains(rule.type())) {
            issues.add(new TreeSitterSubsetCompatibility.Issue(ruleName, rule.type(), "Generated rule type is not implemented"));
        }
        for (var children : rule.children().values()) {
            for (TreeSitterGrammarRule child : children) inspect(ruleName, child, issues);
        }
    }

    private static void detectCycles(TreeSitterGrammar grammar, String rule, ArrayDeque<String> stack,
            Set<String> visited, List<TreeSitterSubsetCompatibility.Issue> issues) {
        if (stack.contains(rule)) {
            issues.add(new TreeSitterSubsetCompatibility.Issue(rule, "recursive symbol",
                    "Subset runtime does not yet implement recursive/GLR grammars"));
            return;
        }
        if (!visited.add(rule)) return;
        TreeSitterGrammarRule definition = grammar.rules().get(rule);
        if (definition == null) return;
        stack.push(rule);
        for (String symbol : symbols(definition)) {
            if (grammar.rules().containsKey(symbol)) detectCycles(grammar, symbol, stack, visited, issues);
        }
        stack.pop();
    }

    private static List<String> symbols(TreeSitterGrammarRule rule) {
        var result = new ArrayList<String>();
        if ("SYMBOL".equals(rule.type()) && !rule.attribute("name").isBlank()) result.add(rule.attribute("name"));
        for (var children : rule.children().values()) {
            for (TreeSitterGrammarRule child : children) result.addAll(symbols(child));
        }
        return result;
    }
}
