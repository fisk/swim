package org.fisk.swim.plugins.cppdebug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GdbMiParser {
    record ParsedChunk(List<GdbMiValue.Tuple> resultRecords, List<GdbMiValue.Tuple> asyncRecords) {
    }

    private final String _text;
    private int _index;

    private GdbMiParser(String text) {
        _text = text;
    }

    static ParsedChunk parseChunk(String chunk) {
        var results = new ArrayList<GdbMiValue.Tuple>();
        var async = new ArrayList<GdbMiValue.Tuple>();
        for (String line : chunk.split("\\R")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '^' || line.charAt(0) == '*') {
                var parser = new GdbMiParser(line.substring(1));
                String recordType = parser.readIdentifier();
                var fields = parser.readFieldMap();
                var tuple = new GdbMiValue.Tuple(fields);
                fields.put("_record", new GdbMiValue.Const(recordType));
                if (line.charAt(0) == '^') {
                    results.add(tuple);
                } else {
                    async.add(tuple);
                }
            }
        }
        return new ParsedChunk(List.copyOf(results), List.copyOf(async));
    }

    private Map<String, GdbMiValue> readFieldMap() {
        var map = new LinkedHashMap<String, GdbMiValue>();
        while (_index < _text.length()) {
            if (_text.charAt(_index) == ',') {
                _index++;
            }
            if (_index >= _text.length()) {
                break;
            }
            String key = readIdentifier();
            if (_index < _text.length() && _text.charAt(_index) == '=') {
                _index++;
            }
            map.put(key, readValue());
        }
        return map;
    }

    private GdbMiValue readValue() {
        if (_index >= _text.length()) {
            return new GdbMiValue.Const("");
        }
        char current = _text.charAt(_index);
        return switch (current) {
        case '"' -> new GdbMiValue.Const(readQuoted());
        case '{' -> readTuple();
        case '[' -> readArray();
        default -> new GdbMiValue.Const(readBare());
        };
    }

    private GdbMiValue.Tuple readTuple() {
        _index++;
        int start = _index;
        int depth = 1;
        var content = new StringBuilder();
        while (_index < _text.length() && depth > 0) {
            char current = _text.charAt(_index++);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            content.append(current);
        }
        return new GdbMiValue.Tuple(new GdbMiParser(content.toString()).readFieldMap());
    }

    private GdbMiValue.Array readArray() {
        _index++;
        var items = new ArrayList<GdbMiValue>();
        while (_index < _text.length() && _text.charAt(_index) != ']') {
            if (_text.charAt(_index) == ',') {
                _index++;
                continue;
            }
            if (peekIdentifierFollowedBy('=')) {
                String key = readIdentifier();
                _index++;
                items.add(new GdbMiValue.Tuple(Map.of(key, readValue())));
            } else {
                items.add(readValue());
            }
        }
        if (_index < _text.length() && _text.charAt(_index) == ']') {
            _index++;
        }
        return new GdbMiValue.Array(List.copyOf(items));
    }

    private boolean peekIdentifierFollowedBy(char next) {
        int cursor = _index;
        while (cursor < _text.length()
                && (Character.isLetterOrDigit(_text.charAt(cursor)) || _text.charAt(cursor) == '-'
                        || _text.charAt(cursor) == '_')) {
            cursor++;
        }
        return cursor < _text.length() && _text.charAt(cursor) == next;
    }

    private String readQuoted() {
        _index++;
        var builder = new StringBuilder();
        while (_index < _text.length()) {
            char current = _text.charAt(_index++);
            if (current == '\\' && _index < _text.length()) {
                char escaped = _text.charAt(_index++);
                builder.append(switch (escaped) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> escaped;
                });
                continue;
            }
            if (current == '"') {
                break;
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private String readBare() {
        int start = _index;
        while (_index < _text.length() && _text.charAt(_index) != ',' && _text.charAt(_index) != ']'
                && _text.charAt(_index) != '}') {
            _index++;
        }
        return _text.substring(start, _index);
    }

    private String readIdentifier() {
        int start = _index;
        while (_index < _text.length() && (Character.isLetterOrDigit(_text.charAt(_index))
                || _text.charAt(_index) == '-' || _text.charAt(_index) == '_')) {
            _index++;
        }
        return _text.substring(start, _index);
    }
}
