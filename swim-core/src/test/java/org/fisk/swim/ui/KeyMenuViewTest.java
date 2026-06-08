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
        assertTrue(view.bodyText().contains("Editing"));
        assertTrue(view.bodyText().contains("i inner text object"));
        assertTrue(view.bodyText().contains("w word"));
        assertTrue(view.bodyText().contains("d line"));
    }

    @Test
    void leaderChainShowsCodeActionContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 180, 2));
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.key(' '));
        view.observe(HeadlessWindowHarness.key('e'));

        assertEquals("SPC e", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Code"));
        assertTrue(view.bodyText().contains("i organize imports"));
        assertTrue(view.bodyText().contains("f make final"));
        assertTrue(view.bodyText().contains("a generate accessors"));
    }

    @Test
    void ctrlWChainShowsPaneContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 160, 2));
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.ctrl('w'));

        assertEquals("<CTRL>-w", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Panes") || view.bodyText().contains("split below"));
        assertTrue(!view.bodyText().isBlank());
    }

    @Test
    void ctrlGThenCShowsShellCreationContinuations() {
        var view = new KeyMenuView(Rect.create(0, 0, 160, 2));
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.ctrl('g'));
        view.observe(HeadlessWindowHarness.key('c'));

        assertEquals("C-g c", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Shell"));
        assertTrue(view.bodyText().contains("w new shell workspace"));
        assertTrue(view.bodyText().contains("v shell in split right"));
        assertTrue(view.bodyText().contains("h shell in split below"));
    }

    @Test
    void invalidContinuationResetsAndStartsNewRootChain() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));
        view.setAnimationStepOverride(0L);

        view.observe(HeadlessWindowHarness.key('d'));
        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("g buffer start"));
    }

    @Test
    void gotoPrefixShowsDefinitionContinuation() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));
        view.setAnimationStepOverride(0L);

        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Navigation"));
        assertTrue(view.bodyText().contains("g buffer start"));
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
        assertTrue(line.toString().contains("discover"));
        assertTrue(line.toString().contains("groups"));
    }

    @Test
    void bodyLineUsesSegmentBlockAndResetsToBaseBackground() throws Exception {
        var view = new KeyMenuView(Rect.create(0, 0, 180, 2));
        view.setAnimationStepOverride(1L);

        AttributedString line = view.buildBodyLines(180).get(0);

        assertTrue(!fragmentText(line, 0).isBlank());
        assertEquals(UiTheme.MENU_SEGMENT_BACKGROUND, background(line, 0));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 1));
        assertEquals(UiTheme.MENU_SECONDARY_BACKGROUND, background(line, 1));
    }

    @Test
    void defaultBodyIncludesClassicWordMotions() {
        var view = new KeyMenuView(Rect.create(0, 0, 1000, 2));
        view.setAnimationStepOverride(0L);

        assertTrue(view.bodyText().contains("w word forward"));
        assertTrue(view.bodyText().contains("b word back"));
    }

    @Test
    void defaultBodyIncludesClassicLineMotions() {
        var view = new KeyMenuView(Rect.create(0, 0, 1000, 2));
        view.setAnimationStepOverride(0L);

        assertTrue(view.bodyText().contains("0 column zero"));
        assertTrue(view.bodyText().contains("$ line end"));
    }

    @Test
    void defaultBodyIncludesClassicEditingPage() {
        var view = new KeyMenuView(Rect.create(0, 0, 1000, 2));
        view.setAnimationStepOverride(1L);

        assertTrue(view.bodyText().contains("Editing"));
        assertTrue(view.bodyText().contains("x delete character"));
    }

    @Test
    void defaultBodyIncludesQuickTodoShortcut() {
        var view = new KeyMenuView(Rect.create(0, 0, 220, 2));
        view.setAnimationStepOverride(2L);

        assertTrue(view.bodyText().contains("C-t quick todo"));
    }

    @Test
    void defaultBodyIncludesCommandLineShortcut() {
        var view = new KeyMenuView(Rect.create(0, 0, 140, 2));
        view.setAnimationStepOverride(2L);

        assertTrue(view.bodyText().contains(": command line"));
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
        view.setAnimationStepOverride(0L);

        var lines = view.buildBodyLines(18);
        view.observe(HeadlessWindowHarness.key('g'));

        assertTrue(!lines.isEmpty());
        assertTrue(view.preferredHeight(18, 12) >= 2);
    }

    @Test
    void commandLineChainDescriptionRemainsAvailable() {
        var view = new KeyMenuView(Rect.create(0, 0, 140, 2));
        view.setAnimationStepOverride(2L);

        view.observe(HeadlessWindowHarness.key(':'));

        assertEquals("", view.getBreadcrumb());
        assertTrue(view.bodyText().contains(": command line"));
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

    @Test
    void prefixPathExpandsIntoDropdownRows() {
        var view = new KeyMenuView(Rect.create(0, 0, 80, 2));
        view.setAnimationStepOverride(0L);

        int baseHeight = view.preferredHeight(80, 12);
        view.observe(HeadlessWindowHarness.key('g'));

        assertTrue(view.preferredHeight(80, 12) >= baseHeight);
        assertTrue(view.bodyText().contains("Navigation"));
        assertTrue(view.bodyText().contains("Marks"));
    }

    @Test
    void overflowPagesBackAndForthAcrossRows() {
        var view = new KeyMenuView(Rect.create(0, 0, 28, 4));

        view.setAnimationStepOverride(0L);
        String first = view.bodyText();

        view.setAnimationStepOverride(1L);
        String second = view.bodyText();

        assertTrue(first.contains("Navigation"));
        assertTrue(second.contains("Editing") || second.contains("Workspace") || second.contains("Panes"));
        assertTrue(!first.equals(second));
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
