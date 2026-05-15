package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    void jumpsDirectlyWhenVisibleSearchHasSingleMatch() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "alpha beta");
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertEquals(Response.YES, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false))));

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

    @Test
    void usesSingleLetterHintsWhenExactlyFiftyTwoTargetsAreVisible() throws IOException {
        String text = IntStream.range(0, 52)
                .mapToObj(i -> "b" + i)
                .collect(Collectors.joining(" "));
        var context = EventTestSupport.createContext(tempDir, text);
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertEquals(Response.YES, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('a', false, false))));

        responder.respond();

        assertEquals(0, context.bufferContext().getBuffer().getCursor().getPosition());
    }

    @Test
    void clearsVisibleHintsAfterInvalidContinuation() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "beta bravo");
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertEquals(Response.MAYBE, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false))));
        assertEquals("a", decorateAt(context, responder, 0));

        assertEquals(Response.NO, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('z', false, false))));
        assertEquals("b", decorateAt(context, responder, 0));
    }

    @Test
    void ignoresWrappedViewportStartsThatAreMidWord() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "alphabeta gamma", 5, 2);
        context.bufferContext().getBufferView().scrollDown();
        var responder = new FancyJumpResponder(context.bufferContext(), 'w');

        assertEquals(Response.NO, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('w', false, false),
                new KeyStroke('b', false, false))));
    }

    private static String decorateAt(EventTestSupport.TestContext context, FancyJumpResponder responder, int position) {
        var glyph = context.bufferContext().getTextLayout().getGlyphs()
                .filter(candidate -> candidate.getPosition() == position)
                .findFirst()
                .orElseThrow();
        var original = context.bufferContext().getBuffer().getAttributedString().getCharacter(position);
        return responder.decorate(glyph, original).toString();
    }
}
