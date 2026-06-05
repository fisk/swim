package org.fisk.swim.event;

import java.util.ArrayList;
import java.util.List;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public record RecordedKey(
        KeyType keyType,
        Character character,
        boolean ctrlDown,
        boolean altDown) {
    public static RecordedKey fromKeyStroke(KeyStroke keyStroke) {
        return new RecordedKey(
                keyStroke.getKeyType(),
                keyStroke.getCharacter(),
                keyStroke.isCtrlDown(),
                keyStroke.isAltDown());
    }

    public KeyStroke toKeyStroke() {
        return switch (keyType) {
        case Character -> new KeyStroke(character, ctrlDown, altDown);
        case Escape -> new KeyStroke(KeyType.Escape);
        case Enter -> new KeyStroke(KeyType.Enter);
        case Backspace -> new KeyStroke(KeyType.Backspace);
        case Tab -> new KeyStroke(KeyType.Tab);
        case ReverseTab -> new KeyStroke(KeyType.ReverseTab);
        case ArrowUp -> new KeyStroke(KeyType.ArrowUp);
        case ArrowDown -> new KeyStroke(KeyType.ArrowDown);
        case ArrowLeft -> new KeyStroke(KeyType.ArrowLeft);
        case ArrowRight -> new KeyStroke(KeyType.ArrowRight);
        default -> new KeyStroke(keyType);
        };
    }

    public String notation() {
        if (keyType == KeyType.Character && character != null) {
            String token = character == ' ' ? "<SPACE>" : Character.toString(character);
            if (ctrlDown) {
                return "<CTRL>-" + token;
            }
            if (altDown) {
                return "<ALT>-" + token;
            }
            return token;
        }
        return switch (keyType) {
        case Escape -> "<ESC>";
        case Enter -> "<ENTER>";
        case Backspace -> "<BACKSPACE>";
        case Tab -> "<TAB>";
        case ReverseTab -> "<REVERSE-TAB>";
        case ArrowUp -> "<UP>";
        case ArrowDown -> "<DOWN>";
        case ArrowLeft -> "<LEFT>";
        case ArrowRight -> "<RIGHT>";
        default -> "<" + keyType.name() + ">";
        };
    }

    public static List<RecordedKey> parseSequence(String notation) {
        var result = new ArrayList<RecordedKey>();
        if (notation == null || notation.isBlank()) {
            return result;
        }
        for (String token : notation.trim().split("\\s+")) {
            result.add(parseToken(token));
        }
        return result;
    }

    public static RecordedKey parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Key token must not be blank");
        }
        boolean ctrl = false;
        boolean alt = false;
        if (token.startsWith("<CTRL>-")) {
            ctrl = true;
            token = token.substring("<CTRL>-".length());
        } else if (token.startsWith("<ALT>-")) {
            alt = true;
            token = token.substring("<ALT>-".length());
        }
        return switch (token) {
        case "<ESC>" -> new RecordedKey(KeyType.Escape, null, false, alt);
        case "<ENTER>" -> new RecordedKey(KeyType.Enter, null, false, alt);
        case "<BACKSPACE>" -> new RecordedKey(KeyType.Backspace, null, false, alt);
        case "<TAB>" -> new RecordedKey(KeyType.Tab, null, false, alt);
        case "<REVERSE-TAB>" -> new RecordedKey(KeyType.ReverseTab, null, false, alt);
        case "<UP>" -> new RecordedKey(KeyType.ArrowUp, null, false, alt);
        case "<DOWN>" -> new RecordedKey(KeyType.ArrowDown, null, false, alt);
        case "<LEFT>" -> new RecordedKey(KeyType.ArrowLeft, null, false, alt);
        case "<RIGHT>" -> new RecordedKey(KeyType.ArrowRight, null, false, alt);
        case "<SPACE>" -> new RecordedKey(KeyType.Character, ' ', ctrl, alt);
        default -> {
            if (token.length() != 1) {
                throw new IllegalArgumentException("Unsupported key token: " + token);
            }
            yield new RecordedKey(KeyType.Character, token.charAt(0), ctrl, alt);
        }
        };
    }
}
