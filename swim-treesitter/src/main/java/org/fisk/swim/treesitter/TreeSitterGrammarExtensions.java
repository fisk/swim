package org.fisk.swim.treesitter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonParser;

/** Small SWIM-owned overlays for omissions in an otherwise upstream grammar. */
public final class TreeSitterGrammarExtensions {
    private TreeSitterGrammarExtensions() {
    }

    public static List<String> missingLiteralKeywords(String language) {
        String resource = "/org/fisk/swim/treesitter/extensions/" + language + ".json";
        try (InputStream input = TreeSitterGrammarExtensions.class.getResourceAsStream(resource)) {
            if (input == null) return List.of();
            var root = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!language.equals(root.get("language").getAsString())) throw new IllegalArgumentException("Extension language mismatch");
            var values = new java.util.ArrayList<String>();
            if (root.has("missingLiteralKeywords")) {
                for (var value : root.getAsJsonArray("missingLiteralKeywords")) values.add(value.getAsString());
            }
            return List.copyOf(values);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Tree-sitter extension for " + language, e);
        }
    }
}
