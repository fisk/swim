package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyMenuViewTest {
    @Test
    void deletePrefixShowsAvailableContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.key('d'));

        assertEquals("d", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("i inner"));
        assertTrue(view.bodyText().contains("w word"));
        assertTrue(view.bodyText().contains("d line"));
    }

    @Test
    void leaderChainShowsCodeActionContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.key(' '));
        view.observe(HeadlessWindowHarness.key('e'));

        assertEquals("SPC e", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("i organize imports"));
        assertTrue(view.bodyText().contains("f make final"));
        assertTrue(view.bodyText().contains("a generate accessors"));
    }

    @Test
    void ctrlWChainShowsPaneContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.ctrl('w'));

        assertEquals("<CTRL>-w", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("s split below"));
        assertTrue(view.bodyText().contains("v split right"));
        assertTrue(view.bodyText().contains("h focus left"));
        assertTrue(view.bodyText().contains("q close pane"));
    }

    @Test
    void invalidContinuationResetsAndStartsNewRootChain() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.key('d'));
        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("g top of buffer"));
    }

    @Test
    void nonNormalModesFallBackToPassiveHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));
        view.observe(HeadlessWindowHarness.key('d'));

        view.setModeName("INPUT");

        assertEquals("", view.getBreadcrumb());
        assertTrue(view.buildHeaderLine().toString().contains("INPUT"));
        assertTrue(view.bodyText().contains("type to insert"));
    }

    @Test
    void commandContextShowsCommandHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.COMMAND);
        view.setCommandState(":", "");

        assertTrue(view.buildHeaderLine().toString().contains("command line active"));
        assertTrue(view.bodyText().contains("Tab complete"));
    }

    @Test
    void searchContextShowsSearchHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.COMMAND);
        view.setCommandState("/", "needle");
        view.setContextLabel("forward search");

        assertTrue(view.buildHeaderLine().toString().contains("forward search"));
        assertTrue(view.bodyText().contains("Enter search forward"));
        assertTrue(view.bodyText().contains("n/N repeat"));
    }

    @Test
    void listContextShowsPanelHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.LIST_PANEL);
        view.setContextLabel("Files");

        assertTrue(view.buildHeaderLine().toString().contains("list navigation"));
        assertTrue(view.buildHeaderLine().toString().contains("Files"));
        assertTrue(view.bodyText().contains("type to filter"));
    }
}
