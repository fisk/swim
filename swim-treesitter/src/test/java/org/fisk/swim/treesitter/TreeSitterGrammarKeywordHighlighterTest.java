package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TreeSitterGrammarKeywordHighlighterTest {
    @Test
    void cppKeywordsComeFromTheBundledGeneratedGrammarNotAHandwrittenList() {
        var highlighter = new TreeSitterGrammarKeywordHighlighter(TreeSitterBundledGrammars.load("cpp"));

        assertTrue(highlighter.keywords().contains("constexpr"));
        assertTrue(highlighter.keywords().contains("static_cast"));
        assertTrue(highlighter.highlight("constexpr auto value = 1; // constexpr").stream()
                .anyMatch(span -> span.start() == 0 && span.end() == 9));
        assertFalse(highlighter.highlight("// constexpr").stream().findAny().isPresent());
    }

    @Test
    void javaKeywordsComeFromTheBundledGeneratedGrammar() {
        var highlighter = new TreeSitterGrammarKeywordHighlighter(TreeSitterBundledGrammars.load("java"));

        assertTrue(highlighter.keywords().contains("class"));
        assertTrue(highlighter.keywords().contains("record"));
        assertTrue(highlighter.highlight("public class Example {}").stream()
                .anyMatch(span -> span.start() == 7 && span.end() == 12));
    }
}
