package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TreeSitterQueryParserTest {
    @Test
    void parsesJavaHighlightFieldsCapturesAndAnonymousKeyword() throws Exception {
        var query = TreeSitterQueryParser.parse(fixture("java-highlights.scm"));

        assertEquals(3, query.patterns().size());
        assertEquals("class_declaration", query.patterns().get(0).type());
        assertEquals("name", query.patterns().get(0).children().getFirst().field());
        assertEquals("@type", query.patterns().get(0).children().getFirst().captures().getFirst());
        assertEquals("\"public\"", query.patterns().get(2).type());
        assertEquals("@keyword", query.patterns().get(2).captures().getFirst());
    }

    @Test
    void parsesMarkdownInjectionCaptureNames() throws Exception {
        var query = TreeSitterQueryParser.parse(fixture("markdown-injections.scm"));
        var fence = query.patterns().getFirst();

        assertEquals("fenced_code_block", fence.type());
        assertEquals("@injection.language", fence.children().get(0).captures().getFirst());
        assertEquals("@injection.content", fence.children().get(1).captures().getFirst());
    }

    @Test
    void parsesCppPredicatesAndAlternatives() throws Exception {
        var query = TreeSitterQueryParser.parse(fixture("cpp-highlights.scm"));
        var pattern = query.patterns().getFirst();
        var alternatives = pattern.children().getFirst();

        assertEquals("#match?", pattern.predicates().getFirst().name());
        assertEquals("@variable", pattern.predicates().getFirst().arguments().getFirst());
        assertEquals("__alternation__", alternatives.type());
        assertEquals(2, alternatives.children().size());
        assertEquals("@keyword", alternatives.captures().getFirst());
    }

    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
