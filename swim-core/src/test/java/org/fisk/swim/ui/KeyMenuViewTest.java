package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class KeyMenuViewTest {
    @Test
    void deletePrefixShowsAvailableContinuations() {
        var view = normalMenu(80);

        view.observe(HeadlessWindowHarness.key('d'));

        assertEquals("d", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Editing"));
        assertTrue(view.bodyText().contains("i inner text object"));
        assertTrue(view.bodyText().contains("w word"));
        assertTrue(view.bodyText().contains("d line"));
    }

    @Test
    void leaderChainShowsCodeActionContinuations() {
        var view = normalMenu(180);
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.key(' '));
        view.observe(HeadlessWindowHarness.key('e'));

        assertEquals("SPC e", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Code"));
        assertTrue(view.bodyText().contains("f make final"));
        assertTrue(view.bodyText().contains("a generate accessors"));
    }

    @Test
    void leaderLChainShowsOrganizeImportsContinuation() {
        var view = normalMenu(180);
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.key(' '));
        view.observe(HeadlessWindowHarness.key('l'));

        assertEquals("SPC l", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Code"));
        assertTrue(view.bodyText().contains("o organize imports"));
    }

    @Test
    void leaderChainShowsMoveContinuations() {
        var view = normalMenu(180);
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.key(' '));

        assertEquals("SPC", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("move line or selection down"));
        assertTrue(view.bodyText().contains("indent line or selection"));
        assertTrue(view.bodyText().contains("m mail"));
        assertTrue(view.bodyText().contains("s Slack"));
        assertTrue(view.bodyText().contains("t Todo"));
    }

    @Test
    void ctrlWChainShowsPaneContinuations() {
        var view = normalMenu(160);
        view.setAnimationStepOverride(1L);

        view.observe(HeadlessWindowHarness.ctrl('w'));

        assertEquals("C-w", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Panes") || view.bodyText().contains("split below"));
        assertTrue(!view.bodyText().isBlank());
    }

    @Test
    void ctrlGThenCShowsShellCreationContinuations() {
        var view = normalMenu(160);
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
        var view = normalMenu(80);
        view.setAnimationStepOverride(0L);

        view.observe(HeadlessWindowHarness.key('d'));
        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("g buffer start"));
    }

    @Test
    void gotoPrefixShowsDefinitionContinuation() {
        var view = normalMenu(80);
        view.setAnimationStepOverride(0L);

        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals("g", view.getBreadcrumb());
        assertTrue(view.bodyText().contains("Navigation"));
        assertTrue(view.bodyText().contains("g buffer start"));
        assertTrue(view.bodyText().contains("d definition"));
    }

    @Test
    void nonNormalModesFallBackToPassiveHints() {
        var view = normalMenu(80);
        view.observe(HeadlessWindowHarness.key('d'));

        view.setModeName("INPUT");
        view.setContextKeyHints(null, List.of());

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
        assertTrue(!line.toString().contains("rows"));
    }

    @Test
    void bodyLineUsesSegmentBlockAndResetsToBaseBackground() throws Exception {
        var view = normalMenu(180);
        view.setAnimationStepOverride(1L);

        AttributedString line = view.buildBodyLines(180).get(0);

        assertTrue(!fragmentText(line, 0).isBlank());
        assertEquals(UiTheme.MENU_SEGMENT_BACKGROUND, background(line, 0));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 1));
        assertEquals(UiTheme.MENU_SECONDARY_BACKGROUND, background(line, 1));
    }

    @Test
    void defaultBodyIncludesClassicWordMotions() {
        var view = normalMenu(1000);
        view.setAnimationStepOverride(0L);

        assertTrue(view.bodyText().contains("w word forward"));
        assertTrue(view.bodyText().contains("b word back"));
    }

    @Test
    void defaultBodyIncludesClassicLineMotions() {
        var view = normalMenu(1000);
        view.setAnimationStepOverride(0L);

        assertTrue(view.bodyText().contains("0 column zero"));
        assertTrue(view.bodyText().contains("$ line end"));
    }

    @Test
    void defaultBodyIncludesClassicEditingPage() {
        var view = normalMenu(1000);
        view.setAnimationStepOverride(0L);
        view.observe(HeadlessWindowHarness.key('d'));

        assertTrue(view.bodyText().contains("Editing"));
        assertTrue(view.bodyText().contains("d line"));
    }

    @Test
    void defaultBodyIncludesQuickTodoShortcut() {
        var view = normalMenu(220);
        view.setAnimationStepOverride(216L);

        assertTrue(view.bodyText().contains("C-t quick todo"));
    }

    @Test
    void defaultBodyIncludesCommandLineShortcut() {
        var view = normalMenu(140);
        view.setAnimationStepOverride(216L);

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
    void narrowMenuKeepsBodyInSingleScrollingLine() {
        var view = normalMenu(18, 4);
        view.setAnimationStepOverride(0L);

        var lines = view.buildBodyLines(18);
        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals(1, lines.size());
        assertEquals(2, view.preferredHeight(18, 12));
    }

    @Test
    void commandLineChainDescriptionRemainsAvailable() {
        var view = normalMenu(140);
        view.setAnimationStepOverride(216L);

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
    void prefixPathKeepsSingleScrollingRow() {
        var view = normalMenu(80);
        view.setAnimationStepOverride(0L);

        int baseHeight = view.preferredHeight(80, 12);
        view.observe(HeadlessWindowHarness.key('g'));

        assertEquals(baseHeight, view.preferredHeight(80, 12));
        assertTrue(view.bodyText().contains("Navigation"));
        assertTrue(view.bodyText().contains("Marks"));
    }

    @Test
    void overflowScrollsSingleLineByCharacter() {
        var view = normalMenu(28, 4);

        view.setAnimationStepOverride(0L);
        String first = fragmentText(view.buildBodyLines(28).get(0), 0);

        view.setAnimationStepOverride(1L);
        String second = fragmentText(view.buildBodyLines(28).get(0), 0);

        assertTrue(first.contains("Navigation"));
        assertOneCharacterScroll(first, second);
        assertTrue(!first.equals(second));

    }

    @Test
    void passiveContextUsesProvidedKeyHints() {
        var view = new KeyMenuView(Rect.create(0, 0, 100, 2));

        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.PANEL);
        view.setContextLabel("Mail");
        view.setContextKeyHints("mail browse", List.of(
                KeyBindingHint.of("j", "Navigation", "move down"),
                KeyBindingHint.of("<ENTER>", "Message", "open buffer")));

        assertTrue(view.buildHeaderLine().toString().contains("mail browse"));
        assertTrue(view.bodyText().contains("Navigation"));
        assertTrue(view.bodyText().contains("j move down"));
        assertTrue(view.bodyText().contains("Enter open buffer"));
    }

    @Test
    void longContextLineScrollsByCharacter() {
        var view = new KeyMenuView(Rect.create(0, 0, 36, 2));
        view.setBufferFocused(false);
        view.setFocusContext(KeyMenuView.FocusContext.PANEL);
        view.setContextKeyHints("custom", List.of(
                KeyBindingHint.of("a", "Actions", "alpha action with a long description"),
                KeyBindingHint.of("b", "Actions", "beta action with a long description")));

        view.setAnimationStepOverride(0L);
        String first = fragmentText(view.buildBodyLines(36).get(0), 0);
        view.setAnimationStepOverride(1L);
        String second = fragmentText(view.buildBodyLines(36).get(0), 0);

        assertTrue(first.contains("Actions"));
        assertOneCharacterScroll(first, second);
        assertTrue(!first.equals(second));
        assertTrue(!first.contains("[1/"));
        assertTrue(!second.contains("[2/"));
    }

    private static void assertOneCharacterScroll(String first, String second) {
        int length = Math.min(12, Math.min(first.length() - 2, second.length() - 1));
        assertTrue(length > 4);
        assertEquals(first.substring(2, 2 + length), second.substring(1, 1 + length));
    }

    private static KeyMenuView normalMenu(int width) {
        return normalMenu(width, 2);
    }

    private static KeyMenuView normalMenu(int width, int height) {
        var view = new KeyMenuView(Rect.create(0, 0, width, height));
        view.setContextKeyHints("normal mode", normalHints());
        return view;
    }

    private static List<KeyBindingHint> normalHints() {
        return List.of(
                KeyBindingHint.of("w", "Navigation", "word forward"),
                KeyBindingHint.of("b", "Navigation", "word back"),
                KeyBindingHint.of("0", "Navigation", "column zero"),
                KeyBindingHint.of("$", "Navigation", "line end"),
                KeyBindingHint.of("g g", "Navigation", "buffer start"),
                KeyBindingHint.of("g d", "Code", "definition"),
                KeyBindingHint.of("g m <CHAR>", "Marks", "set mark"),
                KeyBindingHint.of("d i w", "Editing", "inner text object"),
                KeyBindingHint.of("d w", "Editing", "word"),
                KeyBindingHint.of("d d", "Editing", "line"),
                KeyBindingHint.of("<CTRL>-t", "Workspace", "quick todo"),
                KeyBindingHint.of(":", "Workspace", "command line"),
                KeyBindingHint.of("<CTRL>-w s", "Panes", "split below"),
                KeyBindingHint.of("<CTRL>-w v", "Panes", "split right"),
                KeyBindingHint.of("<CTRL>-g c w", "Shell", "new shell workspace"),
                KeyBindingHint.of("<CTRL>-g c v", "Shell", "shell in split right"),
                KeyBindingHint.of("<CTRL>-g c h", "Shell", "shell in split below"),
                KeyBindingHint.of("<SPACE> h", "Editing", "outdent line or selection"),
                KeyBindingHint.of("<SPACE> j", "Editing", "move line or selection down"),
                KeyBindingHint.of("<SPACE> k", "Editing", "move line or selection up"),
                KeyBindingHint.of("<SPACE> l", "Editing", "indent line or selection"),
                KeyBindingHint.of("<SPACE> f", "Workspace", "project files"),
                KeyBindingHint.of("<SPACE> /", "Search", "project grep"),
                KeyBindingHint.of("<SPACE> m", "Workspace", "mail"),
                KeyBindingHint.of("<SPACE> s", "Workspace", "Slack"),
                KeyBindingHint.of("<SPACE> t", "Workspace", "Todo"),
                KeyBindingHint.of("<SPACE> l o", "Code", "organize imports"),
                KeyBindingHint.of("<SPACE> e f", "Code", "make final"),
                KeyBindingHint.of("<SPACE> e a", "Code", "generate accessors"));
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
