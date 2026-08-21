package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TreeSitterSubsetCompatibilityAnalyzerTest {
    @Test
    void acceptsTheConstrainedCppCastFixture() {
        assertTrue(TreeSitterSubsetCompatibilityAnalyzer.analyze(TreeSitterSubsetRuntimeTest.castGrammar()).supported());
    }

    @Test
    void reportsExternalScannersAndUnknownGeneratedRuleForms() {
        var grammar = TreeSitterGrammarInspector.parse(new TreeSitterGrammarSnapshot(Path.of("unsupported.json"), 1, """
                {"name":"unsupported","externals":[{"type":"SYMBOL","name":"indent"}],
                 "rules":{"source_file":{"type":"RESERVED","content":{"type":"BLANK"}}}}
                """));

        var compatibility = TreeSitterSubsetCompatibilityAnalyzer.analyze(grammar);

        assertFalse(compatibility.supported());
        assertTrue(compatibility.issues().stream().anyMatch(issue -> "externals".equals(issue.feature())));
        assertTrue(compatibility.issues().stream().anyMatch(issue -> "RESERVED".equals(issue.feature())));
    }
}
