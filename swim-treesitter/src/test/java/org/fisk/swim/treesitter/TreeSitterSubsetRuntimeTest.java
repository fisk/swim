package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TreeSitterSubsetRuntimeTest {
    @Test
    void parsesCppShapedCastExpressionAndEmitsNamedFieldSpans() {
        var tree = new TreeSitterSubsetRuntime().parse(snapshot(castGrammar(), "static_cast < int > ( value )"));

        assertTrue(tree.succeeded());
        assertTrue(tree.spans().stream().anyMatch(span -> "cast_expression".equals(span.type())));
        assertTrue(tree.spans().stream().anyMatch(span -> "type_descriptor".equals(span.type()) && "type".equals(span.field())));
        assertTrue(tree.spans().stream().anyMatch(span -> "identifier".equals(span.type()) && "value".equals(span.field())));
    }

    @Test
    void reportsSourceErrorsWithoutThrowingAwayTheSnapshotBoundary() {
        var tree = new TreeSitterSubsetRuntime().parse(snapshot(castGrammar(), "static_cast < float > ( value )"));

        assertFalse(tree.succeeded());
        assertEquals(1, tree.errors().size());
        assertTrue(tree.errors().getFirst().message().contains("choice"));
    }

    @Test
    void rejectsRecursiveGrammarRulesExplicitly() {
        var grammar = TreeSitterGrammarInspector.parse(new TreeSitterGrammarSnapshot(Path.of("recursive.json"), 1, """
                {"name":"recursive","rules":{"source_file":{"type":"SYMBOL","name":"source_file"}}}
                """));

        var tree = new TreeSitterSubsetRuntime().parse(snapshot(grammar, "x"));

        assertFalse(tree.succeeded());
        assertTrue(tree.errors().getFirst().message().contains("recursive symbol"));
    }

    static TreeSitterSyntaxSnapshot snapshot(TreeSitterGrammar grammar, String source) {
        return new TreeSitterSyntaxSnapshot(Path.of("sample.cpp"), 4, source, grammar, "source_file");
    }

    static TreeSitterGrammar castGrammar() {
        return TreeSitterGrammarInspector.parse(new TreeSitterGrammarSnapshot(Path.of("cast.json"), 1, """
                {
                  "name":"subset-cpp",
                  "extras":[{"type":"PATTERN","value":"\\\\s+"}],
                  "rules":{
                    "source_file":{"type":"SYMBOL","name":"cast_expression"},
                    "cast_expression":{"type":"SEQ","members":[
                      {"type":"STRING","value":"static_cast"},
                      {"type":"STRING","value":"<"},
                      {"type":"FIELD","name":"type","content":{"type":"SYMBOL","name":"type_descriptor"}},
                      {"type":"STRING","value":">"},
                      {"type":"STRING","value":"("},
                      {"type":"FIELD","name":"value","content":{"type":"SYMBOL","name":"identifier"}},
                      {"type":"STRING","value":")"}
                    ]},
                    "type_descriptor":{"type":"CHOICE","members":[
                      {"type":"STRING","value":"int"},{"type":"STRING","value":"long"}]},
                    "identifier":{"type":"PATTERN","value":"[A-Za-z_][A-Za-z0-9_]*"}
                  }
                }
                """));
    }
}
