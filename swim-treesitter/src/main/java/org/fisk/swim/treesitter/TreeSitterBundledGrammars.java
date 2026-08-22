package org.fisk.swim.treesitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads versioned grammar assets bundled with this plugin; no native parser is loaded. */
public final class TreeSitterBundledGrammars {
    private static final Map<String, TreeSitterGrammar> CACHE = new ConcurrentHashMap<>();

    private TreeSitterBundledGrammars() {
    }

    public static TreeSitterGrammar load(String language) {
        if (language == null || language.isBlank()) throw new IllegalArgumentException("language is required");
        return CACHE.computeIfAbsent(language, TreeSitterBundledGrammars::read);
    }

    /** Returns an upstream bundled query such as {@code highlights.scm} or {@code injections.scm}. */
    public static String queryText(String language, String query) {
        if (language == null || language.isBlank() || query == null || query.isBlank()) {
            throw new IllegalArgumentException("language and query are required");
        }
        String resource = "/org/fisk/swim/treesitter/grammars/" + language + "/queries/" + query;
        try (InputStream input = TreeSitterBundledGrammars.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("No bundled Tree-sitter query " + query + " for " + language);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled Tree-sitter query for " + language, e);
        }
    }

    private static TreeSitterGrammar read(String language) {
        String resource = "/org/fisk/swim/treesitter/grammars/" + language + "/grammar.json";
        try (InputStream input = TreeSitterBundledGrammars.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("No bundled Tree-sitter grammar for " + language);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            TreeSitterGrammar grammar = TreeSitterGrammarInspector.parse(
                    new TreeSitterGrammarSnapshot(Path.of(language + ".json"), 1, text));
            // tree-sitter-cpp intentionally inherits the generated C grammar rather than copying
            // its terminals. Compose the portable models before deriving fallback tokens.
            if ("cpp".equals(language)) grammar = inherit(load("c"), grammar);
            return applyExtension(grammar, language);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled Tree-sitter grammar for " + language, e);
        }
    }

    private static TreeSitterGrammar inherit(TreeSitterGrammar parent, TreeSitterGrammar child) {
        var rules = new java.util.LinkedHashMap<>(parent.rules());
        rules.putAll(child.rules());
        var extras = new java.util.ArrayList<>(parent.extras());
        extras.addAll(child.extras());
        return new TreeSitterGrammar(child.name(), rules, child.externals(), extras, child.conflicts(), child.wordToken());
    }

    private static TreeSitterGrammar applyExtension(TreeSitterGrammar grammar, String language) {
        var keywords = TreeSitterGrammarExtensions.missingLiteralKeywords(language);
        if (keywords.isEmpty()) return grammar;
        var rules = new java.util.LinkedHashMap<>(grammar.rules());
        rules.put("__swim_extension_keywords", new TreeSitterGrammarRule("CHOICE", java.util.Map.of(),
                java.util.Map.of("members", keywords.stream()
                        .map(keyword -> new TreeSitterGrammarRule("STRING", java.util.Map.of("value", keyword), java.util.Map.of()))
                        .toList())));
        return new TreeSitterGrammar(grammar.name(), rules, grammar.externals(), grammar.extras(), grammar.conflicts(), grammar.wordToken());
    }
}
