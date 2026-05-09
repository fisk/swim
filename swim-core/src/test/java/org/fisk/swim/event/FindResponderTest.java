package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class FindResponderTest {
    @TempDir
    java.nio.file.Path tempDir;

    @AfterEach
    void tearDown() {
        EventTestSupport.clearWindow();
    }

    @Test
    void forwardSearchTreatsRegexCharactersLiterally() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "a*b");
        context.bufferContext().getBuffer().getCursor().setPosition(0);
        var responder = new FindResponder(context.bufferContext(), "f", true);

        assertEquals(Response.YES, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('f', false, false),
                new KeyStroke('*', false, false))));

        responder.respond();

        assertEquals(1, context.bufferContext().getBuffer().getCursor().getPosition());
    }

    @Test
    void returnsMaybeWhenOnlyMotionPrefixArrives() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "abc");
        var responder = new FindResponder(context.bufferContext(), "f", true);

        assertEquals(Response.MAYBE, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('f', false, false))));
    }

    @Test
    void returnsMaybeWhenCountAndMotionArriveWithoutTarget() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "abc");
        var responder = new FindResponder(context.bufferContext(), "f", true);

        assertEquals(Response.MAYBE, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('3', false, false),
                new KeyStroke('f', false, false))));
    }

    @Test
    void backwardSearchCanReachBeginningOfBuffer() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "*ab");
        context.bufferContext().getBuffer().getCursor().setPosition(2);
        var responder = new FindResponder(context.bufferContext(), "F", false);

        assertEquals(Response.YES, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('F', false, false),
                new KeyStroke('*', false, false))));

        responder.respond();

        assertEquals(0, context.bufferContext().getBuffer().getCursor().getPosition());
    }

    @Test
    void rejectsNonCharacterTargetKey() throws IOException {
        var context = EventTestSupport.createContext(tempDir, "abc");
        var responder = new FindResponder(context.bufferContext(), "f", true);

        assertEquals(Response.NO, responder.processEvent(EventTestSupport.keys(
                new KeyStroke('f', false, false),
                new KeyStroke(KeyType.Enter))));
    }
}
