package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TodoQuickCaptureViewTest {
    @Test
    void typingAndBackspaceUpdateTitle() {
        var view = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));

        dispatch(view, HeadlessWindowHarness.key('f'));
        dispatch(view, HeadlessWindowHarness.key('i'));
        dispatch(view, HeadlessWindowHarness.key('x'));
        dispatch(view, HeadlessWindowHarness.backspace());

        assertEquals("fi", view.getValue());
    }

    @Test
    void escapeCancelsWithoutSubmitting() {
        var cancelled = new AtomicInteger();
        var submitted = new AtomicInteger();
        var view = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));
        view.setOnCancel(cancelled::incrementAndGet);
        view.setOnSubmit(ignored -> submitted.incrementAndGet());

        dispatch(view, HeadlessWindowHarness.key('x'));
        dispatch(view, HeadlessWindowHarness.escape());

        assertEquals(1, cancelled.get());
        assertEquals(0, submitted.get());
    }

    @Test
    void blankEnterKeepsCaptureOpen() {
        var submitted = new AtomicInteger();
        var view = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));
        view.setOnSubmit(ignored -> submitted.incrementAndGet());

        dispatch(view, HeadlessWindowHarness.enter());

        assertEquals(0, submitted.get());
        assertEquals("Todo title cannot be empty", view.getMessage());
    }

    @Test
    void validEnterSubmitsTrimmedTitle() {
        var submitted = new AtomicReference<String>();
        var view = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));
        view.setOnSubmit(submitted::set);

        dispatch(view, HeadlessWindowHarness.key(' '));
        dispatch(view, HeadlessWindowHarness.key('f'));
        dispatch(view, HeadlessWindowHarness.key('i'));
        dispatch(view, HeadlessWindowHarness.key('x'));
        dispatch(view, HeadlessWindowHarness.key(' '));
        dispatch(view, HeadlessWindowHarness.enter());

        assertEquals("fix", submitted.get());
    }

    @Test
    void ctrlTIsSwallowedWhileCaptureIsFocused() {
        var submitted = new AtomicInteger();
        var cancelled = new AtomicInteger();
        var view = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));
        view.setOnSubmit(ignored -> submitted.incrementAndGet());
        view.setOnCancel(cancelled::incrementAndGet);

        dispatch(view, HeadlessWindowHarness.ctrl('t'));

        assertEquals(0, submitted.get());
        assertEquals(0, cancelled.get());
    }

    private static void dispatch(TodoQuickCaptureView view, com.googlecode.lanterna.input.KeyStroke key) {
        HeadlessWindowHarness.dispatch(view, key);
    }
}
