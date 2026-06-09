package org.fisk.swim.copy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.ui.Window;

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
    private ClipboardBridge _clipboardBridge = new CommandClipboardBridge();

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
        if (normalized == null || normalized == '"' || normalized == '+' || normalized == '*') {
            if (!systemClipboardAccessAllowed()) {
                return inProcessSystemValue(normalized);
            }
            return clipboardBackedValue(normalized);
        }
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

    static void setClipboardBridgeForTests(ClipboardBridge clipboardBridge) {
        getInstance()._clipboardBridge = clipboardBridge == null ? new CommandClipboardBridge() : clipboardBridge;
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
        if (isSystemClipboardRegister(normalized)) {
            _registers.put('"', value);
            if (systemClipboardAccessAllowed()) {
                writeSystemClipboard(value);
            }
        }
        if (kind == StoreKind.YANK) {
            _registers.put('0', value);
        } else if (kind == StoreKind.DELETE) {
            storeDeleteRegister(value);
        }
        if (normalized != null && normalized != '"') {
            putSelectedRegister(normalized, value);
        }
    }

    private static boolean isSystemClipboardRegister(Character register) {
        return register == null || register == '"' || register == '+' || register == '*';
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
            if (lower == '+' || lower == '*') {
                if (systemClipboardAccessAllowed()) {
                    writeSystemClipboard(value);
                }
            }
            return;
        }
        _registers.put(register, value);
        if (register == '+' || register == '*') {
            if (systemClipboardAccessAllowed()) {
                writeSystemClipboard(value);
            }
        }
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

    private RegisterValue inProcessSystemValue(Character register) {
        RegisterValue fallback = _registers.get(register == null ? '"' : register);
        if (fallback == null && (register == null || register == '"' || register == '+' || register == '*')) {
            fallback = _registers.get('"');
        }
        return fallback == null ? new RegisterValue("", false) : fallback;
    }

    private RegisterValue clipboardBackedValue(Character register) {
        RegisterValue fallback = inProcessSystemValue(register);
        String text = readSystemClipboard();
        if (text == null) {
            return fallback;
        }
        if (fallback.text().equals(text)) {
            return fallback;
        }
        return new RegisterValue(text, false);
    }

    private static boolean systemClipboardAccessAllowed() {
        Window window = Window.getInstance();
        return window == null || !window.isEditorDriveSandboxActive();
    }

    private String readSystemClipboard() {
        try {
            return _clipboardBridge.getText();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private void writeSystemClipboard(RegisterValue value) {
        try {
            _clipboardBridge.setText(value.text());
        } catch (Exception | LinkageError ignored) {
        }
    }

    interface ClipboardBridge {
        String getText() throws Exception;
        void setText(String text) throws Exception;
    }

    private static final class CommandClipboardBridge implements ClipboardBridge {
        private static final long TIMEOUT_SECONDS = 2L;

        @Override
        public String getText() throws Exception {
            for (var command : readCommands()) {
                var result = run(command, null);
                if (result.success()) {
                    return result.output();
                }
            }
            return null;
        }

        @Override
        public void setText(String text) throws Exception {
            Exception failure = null;
            for (var command : writeCommands()) {
                try {
                    if (run(command, text == null ? "" : text).success()) {
                        return;
                    }
                } catch (Exception e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static List<List<String>> readCommands() {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                return List.of(List.of("pbpaste"));
            }
            if (os.contains("win")) {
                return List.of(List.of("powershell.exe", "-NoProfile", "-Command", "Get-Clipboard -Raw"));
            }
            return List.of(
                    List.of("wl-paste"),
                    List.of("xclip", "-selection", "clipboard", "-out"),
                    List.of("xsel", "--clipboard", "--output"));
        }

        private static List<List<String>> writeCommands() {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                return List.of(List.of("pbcopy"));
            }
            if (os.contains("win")) {
                return List.of(List.of("powershell.exe", "-NoProfile", "-Command",
                        "Set-Clipboard -Value ([Console]::In.ReadToEnd())"));
            }
            return List.of(
                    List.of("wl-copy"),
                    List.of("xclip", "-selection", "clipboard"),
                    List.of("xsel", "--clipboard", "--input"));
        }

        private static CommandResult run(List<String> command, String input) throws Exception {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                return new CommandResult(false, "");
            }
            if (input != null) {
                try (var output = process.getOutputStream()) {
                    output.write(input.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] output = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "");
            }
            return new CommandResult(process.exitValue() == 0, new String(output, StandardCharsets.UTF_8));
        }
    }

    private record CommandResult(boolean success, String output) {
    }

    private enum StoreKind {
        YANK,
        DELETE
    }
}
