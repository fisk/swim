package org.fisk.swim.copy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.event.RecordedKey;

public class Copy {
    public record RegisterValue(String text, boolean isLine) {
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

    public void setText(String text, boolean isLine) {
        setText(text, isLine, null);
    }

    public void setText(String text, boolean isLine, Character register) {
        var value = new RegisterValue(text == null ? "" : text, isLine);
        _registers.put('"', value);
        Character normalized = normalizeRegister(register);
        if (normalized != null) {
            _registers.put(normalized, value);
        }
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
        char value = Character.toLowerCase(register);
        return value >= 'a' && value <= 'z' ? value : null;
    }
}
