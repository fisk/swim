package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TerminalUtf8DecoderTest {
    @Test
    void buffersIncompleteUtf8SequenceAcrossReads() {
        var decoder = new TerminalUtf8Decoder();
        byte[] bytes = "A€B".getBytes(StandardCharsets.UTF_8);

        assertEquals("A", decoder.decode(bytes, 1));
        assertEquals("", decoder.decode(new byte[] { bytes[1], bytes[2] }, 2));
        assertEquals("€B", decoder.decode(new byte[] { bytes[3], bytes[4] }, 2));
    }

    @Test
    void flushEmitsBufferedTail() {
        var decoder = new TerminalUtf8Decoder();
        byte[] bytes = "€".getBytes(StandardCharsets.UTF_8);

        assertEquals("", decoder.decode(new byte[] { bytes[0], bytes[1] }, 2));
        assertEquals("€", decoder.decode(new byte[] { bytes[2] }, 1));
        assertEquals("", decoder.flush());
    }
}
