package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TreeSitterGeneratedParserInspectorTest {
    @Test
    void readsBundledJavaParserCapabilitiesWithoutLoadingNativeCode() {
        var metadata = TreeSitterGeneratedParserAssets.metadata("java");

        assertEquals("java", metadata.language());
        assertEquals(1385, metadata.stateCount());
        assertFalse(metadata.hasExternalScanner());
    }

    @Test
    void identifiesCppExternalScannerRequirement() {
        var metadata = TreeSitterGeneratedParserAssets.metadata("cpp");

        assertEquals("cpp", metadata.language());
        assertTrue(metadata.stateCount() > 10_000);
        assertEquals(2, metadata.externalTokenCount());
        assertTrue(metadata.hasExternalScanner());
    }
}
