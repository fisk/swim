package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

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
    void ctrlGThenCShowsShellCreationContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.ctrl('g'));
        view.observe(HeadlessWindowHarness.key('c'));

        assertEquals("C-g c", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("w new shell workspace"));
        assertTrue(view.bodyText().contains("v shell in split right"));
        assertTrue(view.bodyText().contains("h shell in split below"));
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
    void gotoPrefixShowsDefinitionContinuation() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("g top of buffer"));
        assertTrue(view.bodyText().contains("d definition"));
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
    void headerLineUsesPowerlineTransitionsAcrossSegments() throws Exception {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        AttributedString line = view.buildHeaderLine();

        assertEquals(" SWIM ", fragmentText(line, 0));
        assertEquals(UiTheme.MENU_ACCENT, background(line, 0));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 1));
        assertEquals(UiTheme.MODE_NORMAL, background(line, 1));
        assertEquals(" NORMAL ", fragmentText(line, 2));
        assertEquals(UiTheme.MODE_NORMAL, background(line, 2));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 3));
        assertEquals(UiTheme.MENU_SEGMENT_BACKGROUND, background(line, 3));
        assertTrue(fragmentText(line, 4).contains("explore key chains"));
        assertEquals(UiTheme.MENU_SEGMENT_BACKGROUND, background(line, 4));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 5));
        assertEquals(UiTheme.MENU_BACKGROUND, background(line, 5));
    }

    @Test
    void bodyLineUsesSegmentBlockAndResetsToBaseBackground() throws Exception {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        AttributedString line = view.buildBodyLines(80).get(0);

        assertTrue(fragmentText(line, 0).contains("move h/j/k/l"));
        assertEquals(UiTheme.MENU_SEGMENT_BACKGROUND, background(line, 0));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 1));
        assertEquals(UiTheme.MENU_SECONDARY_BACKGROUND, background(line, 1));
    }

    @Test
    void defaultBodyIncludesEscForStartingNemo() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        assertTrue(view.bodyText().contains("Esc Nemo chat"));
    }

    @Test
    void defaultBodyIncludesMailShortcut() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        assertTrue(view.bodyText().contains("mail e"));
    }

    @Test
    void defaultBodyIncludesProjectSearchShortcut() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        assertTrue(view.bodyText().contains("grep M"));
    }

    @Test
    void projectSearchContextShowsPanelHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.SEARCH_PANEL);
        view.setContextLabel("Project Search");

        assertTrue(view.buildHeaderLine().toString().contains("project search"));
        assertTrue(view.bodyText().contains("type to search project"));
    }

    @Test
    void narrowMenuWrapsBodyIntoMultipleLines() {
        var view = new KeyMenuView(Rect.create(0, 0, 18, 4));

        var lines = view.buildBodyLines(18);

        assertTrue(lines.size() > 1);
        assertTrue(view.preferredHeight(18, 12) > 2);
    }

    @Test
    void escShowsNemoChainDescription() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.observe(HeadlessWindowHarness.escape());

        assertEquals("", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Esc Nemo chat"));
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
    void listContextShowsPanelHints() throws Exception {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.LIST_PANEL);
        view.setContextLabel("Files");
        var line = view.buildHeaderLine();

        assertTrue(line.toString().contains("list navigation"));
        assertTrue(line.toString().contains("Files"));
        assertTrue(view.bodyText().contains("type to filter"));
        assertEquals(UiTheme.MENU_CONTEXT_BACKGROUND, background(line, 6));
    }

    private static String fragmentText(AttributedString line, int fragmentIndex) {
        return line.getFragments().get(fragmentIndex).toString();
    }

    private static TextColor background(AttributedString line, int fragmentIndex) throws Exception {
        var attributes = line.getFragments().get(fragmentIndex).getAttributes();
        var field = attributes.getClass().getDeclaredField("_backgroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }
}
