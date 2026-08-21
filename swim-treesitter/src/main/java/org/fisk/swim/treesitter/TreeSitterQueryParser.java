package org.fisk.swim.treesitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for structural Tree-sitter query patterns. Predicates are preserved as
 * nodes but deliberately not evaluated until a query runtime exists.
 */
public final class TreeSitterQueryParser {
    private final List<Token> _tokens;
    private int _index;

    private TreeSitterQueryParser(List<Token> tokens) {
        _tokens = tokens;
    }

    public static TreeSitterQuery parse(String source) {
        var parser = new TreeSitterQueryParser(tokenize(source));
        var patterns = new ArrayList<TreeSitterQueryNode>();
        while (!parser.atEnd()) {
            patterns.add(parser.node(""));
        }
        return new TreeSitterQuery(patterns);
    }

    private TreeSitterQueryNode node(String field) {
        expect("(");
        // Tree-sitter permits a pattern wrapper, for example
        // ((fenced_code_block ...) @injection.content). Keep it transparent
        // in the model while retaining any wrapper capture.
        if (peek("(")) {
            TreeSitterQueryNode nested = node(field);
            var captures = new ArrayList<>(nested.captures());
            var predicates = new ArrayList<>(nested.predicates());
            var children = new ArrayList<>(nested.children());
            while (!peek(")")) {
                if (atEnd()) throw error("Unterminated Tree-sitter query pattern");
                if (isCapture()) captures.add(next().value());
                else if (isPredicate()) predicates.add(predicate());
                else if (peek("(")) children.add(node(""));
                else if (peek("[")) children.add(alternatives());
                else throw error("Invalid Tree-sitter query wrapper content");
            }
            expect(")");
            return new TreeSitterQueryNode(nested.type(), nested.field(), captures, children, predicates);
        }
        String type = nextValue("Tree-sitter query node type is required");
        var captures = new ArrayList<String>();
        var children = new ArrayList<TreeSitterQueryNode>();
        var predicates = new ArrayList<TreeSitterQueryPredicate>();
        while (!peek(")")) {
            if (atEnd()) {
                throw error("Unterminated Tree-sitter query pattern");
            }
            if (isCapture()) {
                captures.add(next().value());
            } else if (isPredicate()) {
                predicates.add(predicate());
            } else if (peek("(")) {
                children.add(node(""));
            } else if (peek("[")) {
                children.add(alternatives());
            } else {
                String value = next().value();
                if (peek(":")) {
                    next();
                    if (!peek("(")) {
                        throw error("A query field must contain a node pattern");
                    }
                    children.add(node(value));
                } else if (value.startsWith("@")) {
                    captures.add(value);
                } else {
                    // Predicates and anonymous literals are represented as a
                    // child so inspection never discards grammar information.
                    children.add(new TreeSitterQueryNode(value, "", List.of(), List.of(), List.of()));
                }
            }
        }
        expect(")");
        while (isCapture()) {
            captures.add(next().value());
        }
        return new TreeSitterQueryNode(type, field, captures, children, predicates);
    }

    private TreeSitterQueryNode alternatives() {
        expect("[");
        var alternatives = new ArrayList<TreeSitterQueryNode>();
        while (!peek("]")) {
            if (atEnd()) throw error("Unterminated Tree-sitter query alternation");
            alternatives.add(node(""));
        }
        expect("]");
        var captures = new ArrayList<String>();
        while (isCapture()) captures.add(next().value());
        return new TreeSitterQueryNode("__alternation__", "", captures, alternatives, List.of());
    }

    private boolean isPredicate() {
        return peek("(") && _index + 1 < _tokens.size() && _tokens.get(_index + 1).value().startsWith("#");
    }

    private boolean isCapture() {
        return !atEnd() && _tokens.get(_index).value().startsWith("@");
    }

    private TreeSitterQueryPredicate predicate() {
        expect("(");
        String name = nextValue("Tree-sitter query predicate name is required");
        var arguments = new ArrayList<String>();
        while (!peek(")")) {
            if (atEnd()) throw error("Unterminated Tree-sitter query predicate");
            arguments.add(next().value());
        }
        expect(")");
        return new TreeSitterQueryPredicate(name, arguments);
    }

    private boolean atEnd() {
        return _index >= _tokens.size();
    }

    private boolean peek(String value) {
        return !atEnd() && value.equals(_tokens.get(_index).value());
    }

    private Token next() {
        if (atEnd()) {
            throw error("Unexpected end of Tree-sitter query");
        }
        return _tokens.get(_index++);
    }

    private String nextValue(String message) {
        if (atEnd() || peek("(") || peek(")") || peek(":")) {
            throw error(message);
        }
        return next().value();
    }

    private void expect(String value) {
        if (!peek(value)) {
            throw error("Expected '" + value + "' in Tree-sitter query");
        }
        _index++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " near token " + _index);
    }

    private static List<Token> tokenize(String source) {
        String input = source == null ? "" : source;
        var tokens = new ArrayList<Token>();
        for (int index = 0; index < input.length();) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == ';') {
                while (index < input.length() && input.charAt(index) != '\n') index++;
            } else if (current == '(' || current == ')' || current == ':' || current == '[' || current == ']') {
                tokens.add(new Token(String.valueOf(current)));
                index++;
            } else if (current == '@') {
                int start = index++;
                while (index < input.length() && isWord(input.charAt(index))) index++;
                tokens.add(new Token(input.substring(start, index)));
            } else if (current == '"') {
                int start = index++;
                boolean escaped = false;
                while (index < input.length()) {
                    char character = input.charAt(index++);
                    if (character == '"' && !escaped) break;
                    escaped = character == '\\' && !escaped;
                    if (character != '\\') escaped = false;
                }
                tokens.add(new Token(input.substring(start, index)));
            } else {
                int start = index++;
                while (index < input.length() && !Character.isWhitespace(input.charAt(index))
                        && "()[]:;".indexOf(input.charAt(index)) < 0) index++;
                tokens.add(new Token(input.substring(start, index)));
            }
        }
        return List.copyOf(tokens);
    }

    private static boolean isWord(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '.' || character == '-';
    }

    private record Token(String value) {
    }
}
