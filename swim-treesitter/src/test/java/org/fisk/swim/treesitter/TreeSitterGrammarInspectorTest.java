package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class TreeSitterGrammarInspectorTest {
    @Test
    void inspectsGeneratedGrammarJsonWithoutEvaluatingJavaScript() {
        String source = """
                {
                  "name": "demo",
                  "word": "identifier",
                  "rules": {
                    "source_file": {"type": "REPEAT", "content": {"type": "SYMBOL", "name": "item"}},
                    "item": {"type": "STRING", "value": "item"}
                  },
                  "externals": [{"type": "SYMBOL", "name": "indent"}],
                  "extras": [{"type": "PATTERN", "value": "\\\\s"}],
                  "conflicts": [["item", "source_file"]]
                }
                """;

        var inspection = TreeSitterGrammarInspector.inspect(new TreeSitterGrammarSnapshot(Path.of("grammar.json"), 7, source));

        assertEquals("demo", inspection.name());
        assertEquals(7, inspection.version());
        assertEquals(List.of("source_file", "item"), inspection.ruleNames());
        assertEquals(List.of("indent"), inspection.externalTokens());
        assertEquals(1, inspection.extraTokens().size());
        assertEquals(1, inspection.conflictCount());
        assertTrue(inspection.hasWordToken());
    }
}
