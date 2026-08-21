package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TreeSitterQueryExecutorTest {
    @Test
    void executesCapturesFieldsAlternativesAndMatchPredicates() {
        var grammar = TreeSitterSubsetRuntimeTest.castGrammar();
        var tree = new TreeSitterSubsetRuntime().parse(TreeSitterSubsetRuntimeTest.snapshot(grammar, "static_cast<int>(value)"));
        var query = TreeSitterQueryParser.parse("""
                (cast_expression type: (type_descriptor) @type value: (identifier) @variable)
                ((identifier) @name (#match? @name "^value$"))
                (cast_expression [ (type_descriptor) (identifier) ] @interesting)
                """);

        var captures = new TreeSitterQueryExecutor().execute(query, tree);

        assertEquals(1, captures.stream().filter(capture -> "@type".equals(capture.name())).count());
        assertEquals(1, captures.stream().filter(capture -> "@variable".equals(capture.name())).count());
        assertEquals(1, captures.stream().filter(capture -> "@name".equals(capture.name())).count());
        assertEquals(1, captures.stream().filter(capture -> "@interesting".equals(capture.name())).count());
    }
}
