package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.input.KeyStroke;

class FancyJumpResponderTest {
    @TempDir
    java.nio.file.Path tempDir;

    @AfterEach
    void tearDown() {
        EventTestSupport.clearWindow();
    }

    @Test
    void jumpsToMatchingWordAfterSelectingHint() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "alpha beta");
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertEquals(Response.YES, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('a', false, false))));

        responder.respond();

        assertEquals(6, context.bufferContext().getBuffer().getCursor().getPosition());
    }

    @Test
    void ignoresRegexMetacharactersInSearchPrefix() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "alpha beta");
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertDoesNotThrow(() -> responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('[', false, false))));
    }
}
