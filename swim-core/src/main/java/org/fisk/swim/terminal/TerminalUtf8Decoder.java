package org.fisk.swim.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class TerminalUtf8Decoder {
    private byte[] _pending = new byte[0];

    public String decode(byte[] bytes, int length) {
        if (length <= 0) {
            return "";
        }
        byte[] combined = Arrays.copyOf(_pending, _pending.length + length);
        System.arraycopy(bytes, 0, combined, _pending.length, length);
        int decodableLength = decodablePrefixLength(combined);
        if (decodableLength <= 0) {
            _pending = combined.length > 4 ? new byte[0] : combined;
            return combined.length > 4 ? new String(combined, StandardCharsets.UTF_8) : "";
        }
        String decoded = new String(combined, 0, decodableLength, StandardCharsets.UTF_8);
        _pending = Arrays.copyOfRange(combined, decodableLength, combined.length);
        return decoded;
    }

    public String flush() {
        if (_pending.length == 0) {
            return "";
        }
        String decoded = new String(_pending, StandardCharsets.UTF_8);
        _pending = new byte[0];
        return decoded;
    }

    private static int decodablePrefixLength(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int trailing = 0;
        for (int index = bytes.length - 1; index >= 0 && isContinuation(bytes[index]) && trailing < 3; index--) {
            trailing++;
        }
        int leadIndex = bytes.length - trailing - 1;
        if (leadIndex < 0) {
            return 0;
        }
        int expectedLength = expectedUtf8Length(bytes[leadIndex]);
        if (expectedLength == 1) {
            return bytes.length;
        }
        if (expectedLength < 0) {
            return bytes.length;
        }
        return trailing + 1 >= expectedLength ? bytes.length : leadIndex;
    }

    private static boolean isContinuation(byte value) {
        return (value & 0b1100_0000) == 0b1000_0000;
    }

    private static int expectedUtf8Length(byte value) {
        int unsigned = value & 0xff;
        if ((unsigned & 0b1000_0000) == 0) {
            return 1;
        }
        if ((unsigned & 0b1110_0000) == 0b1100_0000) {
            return 2;
        }
        if ((unsigned & 0b1111_0000) == 0b1110_0000) {
            return 3;
        }
        if ((unsigned & 0b1111_1000) == 0b1111_0000) {
            return 4;
        }
        return -1;
    }
}
