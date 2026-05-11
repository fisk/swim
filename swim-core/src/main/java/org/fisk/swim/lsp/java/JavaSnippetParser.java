package org.fisk.swim.lsp.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JavaSnippetParser {
    static final class TabStop {
        private final int _id;
        private final int _start;
        private final int _end;

        TabStop(int id, int start, int end) {
            _id = id;
            _start = start;
            _end = end;
        }

        int id() {
            return _id;
        }

        int start() {
            return _start;
        }

        int end() {
            return _end;
        }
    }

    static final class ParseResult {
        private final String _text;
        private final List<TabStop> _tabStops;
        private final int _finalCursorOffset;

        ParseResult(String text, List<TabStop> tabStops, int finalCursorOffset) {
            _text = text;
            _tabStops = tabStops;
            _finalCursorOffset = finalCursorOffset;
        }

        String text() {
            return _text;
        }

        List<TabStop> tabStops() {
            return _tabStops;
        }

        int finalCursorOffset() {
            return _finalCursorOffset;
        }
    }

    private final String _snippet;
    private final Path _path;
    private final StringBuilder _output = new StringBuilder();
    private final Map<Integer, TabStop> _tabStops = new HashMap<>();

    private int _index;
    private int _finalCursorOffset = -1;

    private JavaSnippetParser(String snippet, Path path) {
        _snippet = snippet == null ? "" : snippet;
        _path = path;
    }

    static ParseResult parse(String snippet, Path path) {
        var parser = new JavaSnippetParser(snippet, path);
        parser.parseDocument();
        var tabStops = new ArrayList<>(parser._tabStops.values());
        tabStops.sort(Comparator.comparingInt(TabStop::id));
        int finalCursorOffset = parser._finalCursorOffset >= 0 ? parser._finalCursorOffset : parser._output.length();
        return new ParseResult(parser._output.toString().replace("\t", "    "), List.copyOf(tabStops), finalCursorOffset);
    }

    private void parseDocument() {
        while (_index < _snippet.length()) {
            parseToken(true);
        }
    }

    private void parseToken(boolean appendToRoot) {
        String token = parseInlineToken((char) 0);
        if (appendToRoot) {
            _output.append(token);
        }
    }

    private String parseInlineToken(char terminator) {
        if (_index >= _snippet.length()) {
            return "";
        }
        char current = _snippet.charAt(_index);
        if (current == '\\') {
            return parseEscape();
        }
        if (current == '$') {
            return parseDollar(terminator);
        }
        ++_index;
        return Character.toString(current);
    }

    private String parseEscape() {
        ++_index;
        if (_index >= _snippet.length()) {
            return "\\";
        }
        char escaped = _snippet.charAt(_index++);
        return Character.toString(escaped);
    }

    private String parseDollar(char terminator) {
        ++_index;
        if (_index >= _snippet.length()) {
            return "$";
        }
        char current = _snippet.charAt(_index);
        if (Character.isDigit(current)) {
            int id = parseNumber();
            return recordTabStop(id, "");
        }
        if (current == '{') {
            ++_index;
            return parseBracedExpression(terminator);
        }
        if (isVariableCharacter(current)) {
            String variable = parseIdentifier();
            return resolveVariable(variable, "");
        }
        return "$";
    }

    private String parseBracedExpression(char terminator) {
        if (_index >= _snippet.length()) {
            return "";
        }
        char current = _snippet.charAt(_index);
        if (Character.isDigit(current)) {
            int id = parseNumber();
            return parseTabStopBody(id, terminator);
        }
        String variable = parseIdentifier();
        return parseVariableBody(variable);
    }

    private String parseTabStopBody(int id, char terminator) {
        if (_index >= _snippet.length()) {
            return recordTabStop(id, "");
        }
        char current = _snippet.charAt(_index);
        if (current == '}') {
            ++_index;
            return recordTabStop(id, "");
        }
        if (current == ':') {
            ++_index;
            String value = parsePlainUntilClosingBrace(terminator);
            return recordTabStop(id, value);
        }
        if (current == '|') {
            ++_index;
            String value = parseChoice();
            return recordTabStop(id, value);
        }
        skipUntil('}');
        return recordTabStop(id, "");
    }

    private String parseVariableBody(String variable) {
        if (_index >= _snippet.length()) {
            return resolveVariable(variable, "");
        }
        char current = _snippet.charAt(_index);
        if (current == '}') {
            ++_index;
            return resolveVariable(variable, "");
        }
        if (current == ':') {
            ++_index;
            String fallback = parsePlainUntilClosingBrace((char) 0);
            return resolveVariable(variable, fallback);
        }
        skipUntil('}');
        return resolveVariable(variable, "");
    }

    private String parsePlainUntilClosingBrace(char terminator) {
        var value = new StringBuilder();
        while (_index < _snippet.length()) {
            char current = _snippet.charAt(_index);
            if (current == '\\') {
                value.append(parseEscape());
                continue;
            }
            if (current == '}') {
                ++_index;
                break;
            }
            if (terminator != 0 && current == terminator) {
                ++_index;
                break;
            }
            value.append(current);
            ++_index;
        }
        return value.toString();
    }

    private String parseChoice() {
        var option = new StringBuilder();
        var first = new StringBuilder();
        boolean firstCaptured = false;
        while (_index < _snippet.length()) {
            char current = _snippet.charAt(_index);
            if (current == '\\') {
                option.append(parseEscape());
                continue;
            }
            if (current == ',') {
                if (!firstCaptured) {
                    first.append(option);
                    firstCaptured = true;
                }
                option.setLength(0);
                ++_index;
                continue;
            }
            if (current == '|'
                    && _index + 1 < _snippet.length()
                    && _snippet.charAt(_index + 1) == '}') {
                if (!firstCaptured) {
                    first.append(option);
                }
                _index += 2;
                return first.toString();
            }
            option.append(current);
            ++_index;
        }
        if (!firstCaptured) {
            first.append(option);
        }
        return first.toString();
    }

    private String recordTabStop(int id, String value) {
        int start = _output.length();
        _output.append(value);
        int end = _output.length();
        if (id == 0) {
            _finalCursorOffset = start;
        } else if (!_tabStops.containsKey(id)) {
            _tabStops.put(id, new TabStop(id, start, end));
        }
        return "";
    }

    private int parseNumber() {
        int start = _index;
        while (_index < _snippet.length() && Character.isDigit(_snippet.charAt(_index))) {
            ++_index;
        }
        return Integer.parseInt(_snippet.substring(start, _index));
    }

    private String parseIdentifier() {
        int start = _index;
        while (_index < _snippet.length() && isVariableCharacter(_snippet.charAt(_index))) {
            ++_index;
        }
        return _snippet.substring(start, _index);
    }

    private static boolean isVariableCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private void skipUntil(char character) {
        while (_index < _snippet.length() && _snippet.charAt(_index) != character) {
            ++_index;
        }
        if (_index < _snippet.length() && _snippet.charAt(_index) == character) {
            ++_index;
        }
    }

    private String resolveVariable(String variable, String fallback) {
        if (variable == null || variable.isBlank()) {
            return fallback;
        }
        return switch (variable) {
        case "TM_FILENAME" -> fileName();
        case "TM_FILENAME_BASE" -> fileNameBase();
        case "TM_DIRECTORY" -> directory();
        default -> fallback;
        };
    }

    private String fileName() {
        if (_path == null || _path.getFileName() == null) {
            return "";
        }
        return _path.getFileName().toString();
    }

    private String fileNameBase() {
        String fileName = fileName();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    private String directory() {
        if (_path == null || _path.getParent() == null) {
            return "";
        }
        return _path.getParent().toString();
    }
}
