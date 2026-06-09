package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.help.HelpDocument;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

public class HelpWorkspaceView extends View implements KeyBindingHintProvider {
    private record NavRow(int chapterIndex, String text, boolean section, String sectionTitle) {
    }

    private List<HelpDocument.Chapter> _chapters;
    private final ListEventResponder _responders = new ListEventResponder();
    private Runnable _pendingAction;
    private int _selectedChapter;
    private int _navStart;
    private int _articleStart;

    public HelpWorkspaceView(Rect bounds) {
        super(bounds);
        _chapters = HelpDocument.chapters();
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<ESC>", "Help", "return", this::hideWorkspace);
        _responders.addEventResponder("q", "Help", "return", this::hideWorkspace);
        _responders.addEventResponder("<DOWN>", "Article", "scroll down", () -> scrollArticle(1));
        _responders.addEventResponder("j", "Article", "scroll down", () -> scrollArticle(1));
        _responders.addEventResponder("<UP>", "Article", "scroll up", () -> scrollArticle(-1));
        _responders.addEventResponder("k", "Article", "scroll up", () -> scrollArticle(-1));
        _responders.addEventResponder("<RIGHT>", "Chapters", "next chapter", () -> moveChapter(1));
        _responders.addEventResponder("]", "Chapters", "next chapter", () -> moveChapter(1));
        _responders.addEventResponder("J", "Chapters", "next chapter", () -> moveChapter(1));
        _responders.addEventResponder("<LEFT>", "Chapters", "previous chapter", () -> moveChapter(-1));
        _responders.addEventResponder("[", "Chapters", "previous chapter", () -> moveChapter(-1));
        _responders.addEventResponder("K", "Chapters", "previous chapter", () -> moveChapter(-1));
        _responders.addEventResponder("g g", "Article", "top", this::scrollArticleToStart);
        _responders.addEventResponder("G", "Article", "bottom", this::scrollArticleToEnd);
        _responders.addEventResponder("<SPACE>", "Article", "page down", () -> scrollArticle(pageStep()));
        _responders.addEventResponder("<BACKSPACE>", "Article", "page up", () -> scrollArticle(-pageStep()));
        _responders.addEventResponder("<PAGEDOWN>", "Article", "page down", () -> scrollArticle(pageStep()));
        _responders.addEventResponder("<PAGEUP>", "Article", "page up", () -> scrollArticle(-pageStep()));
        _responders.addEventResponder("<CTRL>-d", "Article", "half page down",
                () -> scrollArticle(Math.max(1, pageStep() / 2)));
        _responders.addEventResponder("<CTRL>-u", "Article", "half page up",
                () -> scrollArticle(-Math.max(1, pageStep() / 2)));
        _responders.addEventResponder("<CTRL>-f", "Article", "page down", () -> scrollArticle(pageStep()));
        _responders.addEventResponder("<CTRL>-b", "Article", "page up", () -> scrollArticle(-pageStep()));
    }

    String getTitle() {
        return "SWIM Help";
    }

    @Override
    public String keyHintContext() {
        return "help workspace";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _responders.keyBindingHints();
    }

    String selectedChapterId() {
        refreshChapters();
        return currentChapter().id();
    }

    int articleStartLine() {
        return _articleStart;
    }

    String articleText() {
        refreshChapters();
        return HelpDocument.renderChapter(currentChapter());
    }

    private HelpDocument.Chapter currentChapter() {
        refreshChapters();
        int index = Math.max(0, Math.min(_selectedChapter, _chapters.size() - 1));
        return _chapters.get(index);
    }

    private void refreshChapters() {
        List<HelpDocument.Chapter> chapters = HelpDocument.chapters();
        if (chapters.equals(_chapters)) {
            return;
        }
        String selectedId = _chapters.isEmpty() ? "" : _chapters.get(Math.max(0,
                Math.min(_selectedChapter, _chapters.size() - 1))).id();
        _chapters = chapters;
        if (_chapters.isEmpty()) {
            _selectedChapter = 0;
            _navStart = 0;
            _articleStart = 0;
            return;
        }
        int replacement = indexOfChapter(selectedId);
        if (replacement >= 0) {
            _selectedChapter = replacement;
        } else {
            _selectedChapter = Math.max(0, Math.min(_selectedChapter, _chapters.size() - 1));
        }
        _navStart = Math.max(0, Math.min(_navStart, _chapters.size() - 1));
    }

    private int indexOfChapter(String chapterId) {
        for (int i = 0; i < _chapters.size(); i++) {
            if (_chapters.get(i).id().equals(chapterId)) {
                return i;
            }
        }
        return -1;
    }

    private void hideWorkspace() {
        Window window = Window.getInstance();
        if (window != null) {
            window.hideCurrentWorkspaceWindow();
        }
    }

    private void moveChapter(int delta) {
        selectChapter(_selectedChapter + delta);
    }

    private void selectChapter(int index) {
        refreshChapters();
        if (_chapters.isEmpty()) {
            return;
        }
        int clamped = Math.max(0, Math.min(index, _chapters.size() - 1));
        if (clamped != _selectedChapter) {
            _selectedChapter = clamped;
            _articleStart = 0;
        }
        setNeedsRedraw();
    }

    private int pageStep() {
        return Math.max(1, getBounds().getSize().getHeight() - 5);
    }

    private void scrollArticle(int delta) {
        List<String> lines = articleLines(articleTextWidth());
        _articleStart = Math.max(0, Math.min(maxArticleStart(lines), _articleStart + delta));
        setNeedsRedraw();
    }

    private void scrollArticleToStart() {
        _articleStart = 0;
        setNeedsRedraw();
    }

    private void scrollArticleToEnd() {
        List<String> lines = articleLines(articleTextWidth());
        _articleStart = maxArticleStart(lines);
        setNeedsRedraw();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        refreshChapters();
        _pendingAction = null;
        if (events.remaining() == 0 && events.current() instanceof MouseAction mouseAction) {
            _pendingAction = mouseAction(mouseAction);
            return _pendingAction == null ? Response.NO : Response.YES;
        }
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        if (_pendingAction != null) {
            _pendingAction.run();
            _pendingAction = null;
            return;
        }
        _responders.respond();
    }

    private Runnable mouseAction(MouseAction action) {
        Point origin = absoluteOrigin();
        int localX = action.getPosition().getColumn() - origin.getX();
        int localY = action.getPosition().getRow() - origin.getY();
        if (localX < 0 || localY < 0
                || localX >= getBounds().getSize().getWidth()
                || localY >= getBounds().getSize().getHeight()) {
            return null;
        }
        if (action.getActionType() == MouseActionType.SCROLL_DOWN
                || action.getActionType() == MouseActionType.SCROLL_UP) {
            int direction = action.getActionType() == MouseActionType.SCROLL_DOWN ? 3 : -3;
            return () -> scrollArticle(direction);
        }
        if (action.getActionType() != MouseActionType.CLICK_DOWN
                && action.getActionType() != MouseActionType.CLICK_RELEASE) {
            return null;
        }
        int sidebarWidth = sidebarWidth();
        if (localX >= sidebarWidth || localY < 2) {
            return null;
        }
        int row = _navStart + localY - 2;
        List<NavRow> rows = navRows();
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        NavRow navRow = rows.get(row);
        return () -> selectNavRow(navRow);
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (View parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    @Override
    public void draw(Rect rect) {
        refreshChapters();
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        int width = rect.getSize().getWidth();
        int height = rect.getSize().getHeight();
        int sidebarWidth = sidebarWidth();
        int articleWidth = Math.max(0, width - sidebarWidth - 1);

        drawHeader(rect, width);
        drawSidebar(rect, sidebarWidth, height);
        if (articleWidth > 0) {
            drawDivider(rect, sidebarWidth, height);
            drawArticle(rect, sidebarWidth + 1, articleWidth, height);
        }
    }

    private void drawHeader(Rect rect, int width) {
        var line = new AttributedString();
        line.append(" SWIM Help ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(), rect.getPoint(), width, line,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
    }

    private void drawSidebar(Rect rect, int width, int height) {
        var graphics = TerminalContext.getInstance().getGraphics();
        var title = new AttributedString();
        title.append(" Chapters ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        title.append((_selectedChapter + 1) + "/" + _chapters.size(), UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width, title,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);

        List<NavRow> rows = navRows();
        int listHeight = Math.max(0, height - 2);
        int selectedRow = selectedNavRow(rows);
        if (selectedRow >= _navStart + listHeight) {
            _navStart = selectedRow - listHeight + 1;
        } else if (selectedRow < _navStart) {
            _navStart = selectedRow;
        }
        _navStart = Math.max(0, Math.min(_navStart, Math.max(0, rows.size() - listHeight)));

        for (int row = 0; row < listHeight; row++) {
            int index = _navStart + row;
            int y = rect.getPoint().getY() + 2 + row;
            TextColor background = row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            if (index < rows.size() && rows.get(index).chapterIndex() == _selectedChapter && !rows.get(index).section()) {
                background = UiTheme.PANEL_SELECTION_BACKGROUND;
            }
            UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX(), y), width, background);
            if (index >= rows.size()) {
                continue;
            }
            NavRow navRow = rows.get(index);
            boolean selected = navRow.chapterIndex() == _selectedChapter && !navRow.section();
            var line = new AttributedString();
            String prefix = navRow.section() ? "    " : selected ? "> " : "  ";
            TextColor foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND
                    : navRow.section() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY;
            line.append(prefix + UiTheme.fit(navRow.text(), Math.max(1, width - prefix.length() - 1)),
                    foreground, background);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), y), width, line,
                    UiTheme.TEXT_MUTED, background);
        }
    }

    private void drawDivider(Rect rect, int x, int height) {
        var graphics = TerminalContext.getInstance().getGraphics();
        for (int row = 1; row < height; row++) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX() + x, rect.getPoint().getY() + row), 1,
                    AttributedString.create("|", UiTheme.TEXT_SUBTLE, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_SUBTLE, UiTheme.SURFACE_MUTED);
        }
    }

    private void drawArticle(Rect rect, int offsetX, int width, int height) {
        var graphics = TerminalContext.getInstance().getGraphics();
        List<String> lines = articleLines(width - 2);
        int bodyHeight = Math.max(0, height - 3);
        int maxStart = Math.max(0, lines.size() - bodyHeight);
        _articleStart = Math.max(0, Math.min(maxStart, _articleStart));

        HelpDocument.Chapter chapter = currentChapter();
        var header = new AttributedString();
        header.append(" " + chapter.title() + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + chapter.id() + " ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX() + offsetX, rect.getPoint().getY() + 1),
                width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        for (int row = 0; row < bodyHeight; row++) {
            int index = _articleStart + row;
            int y = rect.getPoint().getY() + 2 + row;
            TextColor background = row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX() + offsetX, y), width, background);
            if (index >= lines.size()) {
                continue;
            }
            String text = lines.get(index);
            TextColor foreground = articleForeground(text);
            var line = AttributedString.create(" " + text, foreground, background);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX() + offsetX, y), width, line,
                    UiTheme.TEXT_MUTED, background);
        }

        var footer = new AttributedString();
        int visibleEnd = Math.min(lines.size(), _articleStart + bodyHeight);
        footer.append(" " + visibleEnd + "/" + lines.size() + " lines ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_MUTED);
        footer.append(" swim_help topic gives Nemo this same chapter ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX() + offsetX,
                rect.getPoint().getY() + Math.max(0, height - 1)), width, footer,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private TextColor articleForeground(String text) {
        if (text == null || text.isBlank()) {
            return UiTheme.TEXT_MUTED;
        }
        if (!text.startsWith(" ") && !text.endsWith(".") && !text.endsWith(":")) {
            return UiTheme.ACCENT_GOLD;
        }
        if (text.startsWith("  ")) {
            return UiTheme.ACCENT_GREEN;
        }
        return UiTheme.TEXT_PRIMARY;
    }

    private int sidebarWidth() {
        int width = getBounds().getSize().getWidth();
        if (width <= 1) {
            return Math.max(0, width);
        }
        int maxSidebar = Math.max(1, width - 2);
        int preferred;
        if (width < 48) {
            preferred = Math.max(16, Math.min(24, width / 2));
        } else {
            preferred = Math.min(Math.max(24, width / 4), Math.max(24, Math.min(38, width - 32)));
        }
        return Math.max(1, Math.min(maxSidebar, preferred));
    }

    private int articleColumnWidth() {
        return Math.max(1, getBounds().getSize().getWidth() - sidebarWidth() - 1);
    }

    private int articleTextWidth() {
        return Math.max(1, articleColumnWidth() - 2);
    }

    private int articleBodyHeight() {
        return Math.max(1, getBounds().getSize().getHeight() - 3);
    }

    private int maxArticleStart(List<String> lines) {
        return Math.max(0, lines.size() - articleBodyHeight());
    }

    private List<String> articleLines(int width) {
        return TextPanelView.wrapText(HelpDocument.renderChapter(currentChapter()), Math.max(1, width));
    }

    private void selectNavRow(NavRow navRow) {
        selectChapter(navRow.chapterIndex());
        if (navRow.section()) {
            scrollArticleToSection(navRow.sectionTitle());
        }
    }

    private void scrollArticleToSection(String sectionTitle) {
        if (sectionTitle == null || sectionTitle.isBlank()) {
            return;
        }
        List<String> lines = articleLines(articleTextWidth());
        for (int index = 0; index < lines.size(); index++) {
            if (sectionTitle.equals(lines.get(index))) {
                _articleStart = Math.max(0, Math.min(maxArticleStart(lines), index));
                setNeedsRedraw();
                return;
            }
        }
    }

    private List<NavRow> navRows() {
        refreshChapters();
        var rows = new ArrayList<NavRow>();
        for (int i = 0; i < _chapters.size(); i++) {
            HelpDocument.Chapter chapter = _chapters.get(i);
            rows.add(new NavRow(i, chapter.title(), false, null));
            if (i == _selectedChapter) {
                for (HelpDocument.Section section : chapter.sections()) {
                    rows.add(new NavRow(i, section.title(), true, section.title()));
                }
            }
        }
        return rows;
    }

    private int selectedNavRow(List<NavRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            NavRow row = rows.get(i);
            if (row.chapterIndex() == _selectedChapter && !row.section()) {
                return i;
            }
        }
        return 0;
    }
}
