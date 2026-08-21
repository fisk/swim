package org.fisk.swim.treesitter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads generated parser-table metadata as data; it never compiles or loads C code. */
public final class TreeSitterGeneratedParserInspector {
    private static final Pattern DEFINE = Pattern.compile("(?m)^#define\\s+([A-Z_]+)\\s+(\\d+)\\s*$");
    private static final Pattern LANGUAGE = Pattern.compile("TS_PUBLIC\\s+const\\s+TSLanguage\\s+\\*tree_sitter_([a-zA-Z0-9_]+)\\s*\\(");

    private TreeSitterGeneratedParserInspector() {
    }

    public static TreeSitterGeneratedParserMetadata inspect(String parserSource) {
        String source = parserSource == null ? "" : parserSource;
        var values = new java.util.HashMap<String, Integer>();
        Matcher defines = DEFINE.matcher(source);
        while (defines.find()) values.put(defines.group(1), Integer.parseInt(defines.group(2)));
        Matcher language = LANGUAGE.matcher(source);
        if (!language.find()) throw new IllegalArgumentException("Generated parser has no tree_sitter_<language> entry point");
        return new TreeSitterGeneratedParserMetadata(language.group(1), value(values, "LANGUAGE_VERSION"),
                value(values, "STATE_COUNT"), value(values, "LARGE_STATE_COUNT"), value(values, "SYMBOL_COUNT"),
                value(values, "TOKEN_COUNT"), value(values, "EXTERNAL_TOKEN_COUNT"), value(values, "FIELD_COUNT"),
                source.contains(".external_scanner ="));
    }

    private static int value(java.util.Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Generated parser omits " + key);
        return value;
    }
}
