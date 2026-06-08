package org.fisk.swim.copy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.event.RecordedKey;

public class Copy {
    public record RegisterValue(String text, boolean isLine, boolean isBlock, List<String> blockLines) {
        public RegisterValue(String text, boolean isLine) {
            this(text, isLine, false, List.of());
        }

        public RegisterValue {
            text = text == null ? "" : text;
            blockLines = blockLines == null ? List.of() : List.copyOf(blockLines);
        }
    }

    private final Map<Character, RegisterValue> _registers = new LinkedHashMap<>();
    private final Map<Character, List<RecordedKey>> _macros = new LinkedHashMap<>();
    private Character _lastMacroRegister;

    private static Copy _instance = new Copy();

    public static Copy getInstance() {
        return _instance;
    }

    public String getText() {
        return getText(null);
    }

    public String getText(Character register) {
        return getValue(register).text();
    }

    public boolean isLine() {
        return isLine(null);
    }

    public boolean isLine(Character register) {
        return getValue(register).isLine();
    }

    public boolean isBlock(Character register) {
        return getValue(register).isBlock();
    }

    public void setText(String text, boolean isLine) {
        setText(text, isLine, null);
    }

    public void setText(String text, boolean isLine, Character register) {
        setYank(text, isLine, register);
    }

    public void setYank(String text, boolean isLine, Character register) {
        store(new RegisterValue(text, isLine), register, StoreKind.YANK);
    }

    public void setDelete(String text, boolean isLine, Character register) {
        store(new RegisterValue(text, isLine), register, StoreKind.DELETE);
    }

    public void setBlock(List<String> lines, Character register) {
        var safeLines = lines == null ? List.<String>of() : List.copyOf(lines);
        store(new RegisterValue(String.join("\n", safeLines), false, true, safeLines), register, StoreKind.YANK);
    }

    public RegisterValue getValue(Character register) {
        Character normalized = normalizeRegister(register);
        if (normalized != null && _registers.containsKey(normalized)) {
            return _registers.get(normalized);
        }
        return _registers.getOrDefault('"', new RegisterValue("", false));
    }

    public Map<Character, RegisterValue> registerSnapshot() {
        return Map.copyOf(_registers);
    }

    public void setMacro(char register, List<RecordedKey> keys) {
        Character normalized = normalizeRegister(register);
        if (normalized == null) {
            return;
        }
        _macros.put(normalized, List.copyOf(keys));
        _lastMacroRegister = normalized;
    }

    public List<RecordedKey> getMacro(char register) {
        Character normalized = normalizeRegister(register);
        return normalized == null ? List.of() : _macros.getOrDefault(normalized, List.of());
    }

    public List<RecordedKey> getLastMacro() {
        return _lastMacroRegister == null ? List.of() : _macros.getOrDefault(_lastMacroRegister, List.of());
    }

    public Character getLastMacroRegister() {
        return _lastMacroRegister;
    }

    public Map<Character, List<RecordedKey>> macroSnapshot() {
        return Map.copyOf(_macros);
    }

    public void clear() {
        _registers.clear();
        _macros.clear();
        _lastMacroRegister = null;
    }

    private static Character normalizeRegister(Character register) {
        if (register == null) {
            return null;
        }
        char value = register;
        if (value >= 'A' && value <= 'Z') {
            return value;
        }
        if (value >= 'a' && value <= 'z') {
            return value;
        }
        if (value >= '0' && value <= '9') {
            return value;
        }
        return switch (value) {
        case '"', '-', '_', '+', '*' -> value;
        default -> null;
        };
    }

    private void store(RegisterValue value, Character register, StoreKind kind) {
        Character normalized = normalizeRegister(register);
        if (normalized != null && normalized == '_') {
            return;
        }
        _registers.put('"', value);
        if (kind == StoreKind.YANK) {
            _registers.put('0', value);
        } else if (kind == StoreKind.DELETE) {
            storeDeleteRegister(value);
        }
        if (normalized != null && normalized != '"') {
            putSelectedRegister(normalized, value);
        }
    }

    private void storeDeleteRegister(RegisterValue value) {
        if (!value.isLine() && !value.text().contains("\n")) {
            _registers.put('-', value);
            return;
        }
        for (char register = '9'; register >= '2'; register--) {
            var previous = _registers.get((char) (register - 1));
            if (previous != null) {
                _registers.put(register, previous);
            }
        }
        _registers.put('1', value);
    }

    private void putSelectedRegister(Character register, RegisterValue value) {
        if (register >= 'A' && register <= 'Z') {
            char lower = Character.toLowerCase(register);
            var existing = _registers.get(lower);
            if (existing != null) {
                value = append(existing, value);
            }
            _registers.put(lower, value);
            return;
        }
        _registers.put(register, value);
    }

    private static RegisterValue append(RegisterValue existing, RegisterValue value) {
        if (existing.isBlock() || value.isBlock()) {
            var merged = new java.util.ArrayList<String>();
            merged.addAll(existing.isBlock() ? existing.blockLines() : List.of(existing.text()));
            merged.addAll(value.isBlock() ? value.blockLines() : List.of(value.text()));
            return new RegisterValue(String.join("\n", merged), false, true, merged);
        }
        return new RegisterValue(existing.text() + value.text(), existing.isLine() || value.isLine());
    }

    private enum StoreKind {
        YANK,
        DELETE
    }
}
