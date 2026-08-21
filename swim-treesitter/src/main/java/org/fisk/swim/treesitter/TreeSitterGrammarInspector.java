package org.fisk.swim.treesitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the portable grammar.json emitted by {@code tree-sitter generate}.
 * It intentionally does not evaluate grammar.js or load parser.c: those are
 * separate phases of a future Java Tree-sitter runtime.
 */
public final class TreeSitterGrammarInspector {
    private TreeSitterGrammarInspector() {
    }

    public static TreeSitterGrammarInspection inspect(TreeSitterGrammarSnapshot snapshot) {
        TreeSitterGrammar grammar = parse(snapshot);
        return new TreeSitterGrammarInspection(
                snapshot.path(), snapshot.version(), grammar.name(), new ArrayList<>(grammar.rules().keySet()),
                symbols(grammar.externals()), symbols(grammar.extras()), grammar.conflicts().size(),
                !grammar.wordToken().isBlank());
    }

    public static TreeSitterGrammar parse(TreeSitterGrammarSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Grammar snapshot is required");
        }
        JsonElement parsed = JsonParser.parseString(snapshot.text());
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Tree-sitter grammar.json must contain a JSON object");
        }
        JsonObject grammar = parsed.getAsJsonObject();
        JsonObject rules = object(grammar, "rules");
        var parsedRules = new LinkedHashMap<String, TreeSitterGrammarRule>();
        if (rules != null) {
            for (var entry : rules.entrySet()) {
                parsedRules.put(entry.getKey(), parseRule(entry.getValue()));
            }
        }
        return new TreeSitterGrammar(string(grammar, "name"), parsedRules,
                parseRules(array(grammar, "externals")), parseRules(array(grammar, "extras")),
                conflicts(array(grammar, "conflicts")), string(grammar, "word"));
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static TreeSitterGrammarRule parseRule(JsonElement value) {
        if (!value.isJsonObject()) {
            return new TreeSitterGrammarRule("LITERAL", Map.of("value", value == null ? "" : value.toString()), Map.of());
        }
        JsonObject object = value.getAsJsonObject();
        var attributes = new LinkedHashMap<String, String>();
        var children = new LinkedHashMap<String, List<TreeSitterGrammarRule>>();
        for (var entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                attributes.put(entry.getKey(), entry.getValue().getAsString());
            } else if (entry.getValue().isJsonObject()) {
                children.put(entry.getKey(), List.of(parseRule(entry.getValue())));
            } else if (entry.getValue().isJsonArray()) {
                children.put(entry.getKey(), parseRules(entry.getValue().getAsJsonArray()));
            }
        }
        return new TreeSitterGrammarRule(attributes.getOrDefault("type", ""), attributes, children);
    }

    private static List<TreeSitterGrammarRule> parseRules(JsonArray values) {
        if (values == null) {
            return List.of();
        }
        var rules = new ArrayList<TreeSitterGrammarRule>();
        for (JsonElement value : values) {
            rules.add(parseRule(value));
        }
        return List.copyOf(rules);
    }

    private static List<List<String>> conflicts(JsonArray values) {
        if (values == null) {
            return List.of();
        }
        var conflicts = new ArrayList<List<String>>();
        for (JsonElement value : values) {
            if (value.isJsonArray()) {
                var conflict = new ArrayList<String>();
                for (JsonElement symbol : value.getAsJsonArray()) {
                    if (symbol.isJsonPrimitive()) {
                        conflict.add(symbol.getAsString());
                    }
                }
                conflicts.add(List.copyOf(conflict));
            }
        }
        return List.copyOf(conflicts);
    }

    private static List<String> symbols(List<TreeSitterGrammarRule> rules) {
        var symbols = new ArrayList<String>();
        for (TreeSitterGrammarRule rule : rules) {
            String name = rule.attribute("name");
            symbols.add(name.isBlank() ? rule.type() : name);
        }
        return List.copyOf(symbols);
    }
}
