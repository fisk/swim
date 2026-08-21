package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TreeSitterGrammarModelTest {
    @Test
    void preservesNestedCppCastRulesAndFields() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("fixtures/cpp-grammar.json")) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var grammar = TreeSitterGrammarInspector.parse(new TreeSitterGrammarSnapshot(Path.of("cpp/grammar.json"), 3, source));
        var cast = grammar.rules().get("cast_expression");

        assertEquals("cpp", grammar.name());
        assertEquals("SEQ", cast.type());
        assertEquals("CHOICE", cast.children("members").get(0).type());
        assertEquals("FIELD", cast.children("members").get(1).type());
        assertEquals("type", cast.children("members").get(1).attribute("name"));
        assertEquals(1, grammar.conflicts().size());
    }
}
