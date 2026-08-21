package org.fisk.swim.treesitter;

/** Immutable capability summary read from a generated Tree-sitter {@code parser.c}. */
public record TreeSitterGeneratedParserMetadata(
        String language, int languageVersion, int stateCount, int largeStateCount, int symbolCount,
        int tokenCount, int externalTokenCount, int fieldCount, boolean hasExternalScanner) {
}
