package org.fisk.swim.treesitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Access to bundled generated parser tables for the future Java table interpreter. */
public final class TreeSitterGeneratedParserAssets {
    private static final ConcurrentHashMap<String, TreeSitterGeneratedParserMetadata> METADATA = new ConcurrentHashMap<>();

    private TreeSitterGeneratedParserAssets() {
    }

    public static TreeSitterGeneratedParserMetadata metadata(String language) {
        return METADATA.computeIfAbsent(language, TreeSitterGeneratedParserAssets::readMetadata);
    }

    private static TreeSitterGeneratedParserMetadata readMetadata(String language) {
        String resource = "/org/fisk/swim/treesitter/parsers/" + language + "/parser.c";
        try (InputStream input = TreeSitterGeneratedParserAssets.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("No bundled generated parser for " + language);
            return TreeSitterGeneratedParserInspector.inspect(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read generated parser for " + language, e);
        }
    }
}
