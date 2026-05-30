package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.cpp.ClangdLspPluginSupport;
import org.fisk.swim.mail.MailStatusService;
import org.fisk.swim.mode.InputMode;
import org.fisk.swim.mode.Mode;
import org.fisk.swim.mode.NormalMode;
import org.fisk.swim.mode.VisualBlockMode;
import org.fisk.swim.mode.VisualLineMode;
import org.fisk.swim.mode.VisualMode;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.ListView.ListItem;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen.RefreshType;

public class Window implements Drawable {
    private static final int MIN_TOP_MENU_HEIGHT = 2;

    private enum WorkspaceKind {
        BUFFER,
        DIRECTORY,
        MAIL
    }

    private static final class WorkspaceState {
        private WorkspaceKind _kind;
        private View _workspaceView;
        private View _activeView;
        private BufferView _activeBufferView;
        private BufferContext _bufferContext;
        private IdentityHashMap<BufferView, BufferContext> _bufferContextsByView;
        private IdentityHashMap<BufferContext, Integer> _bufferViewCounts;
        private NormalMode _normalMode;
        private InputMode _inputMode;
        private VisualMode _visualMode;
        private VisualLineMode _visualLineMode;
        private VisualBlockMode _visualBlockMode;
        private Mode _currentMode;
        private View _panelView;
    }

    public enum Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    private static Logger _log = LogFactory.createLog();
    private static Window _instance;
    private View _rootView;
    private KeyMenuView _keyMenuView;
    private View _workspaceView;
    private View _activeView;
    private BufferView _activeBufferView;
    private ModeLineView _modeLineView;
    private CommandView _commandView;
    private CommandMenuView _commandMenuView;
    private MailNotificationView _mailNotificationView;
    private Size _size;
    private BufferContext _bufferContext;
    private IdentityHashMap<BufferView, BufferContext> _bufferContextsByView;
    private IdentityHashMap<BufferContext, Integer> _bufferViewCounts;
    private NormalMode _normalMode;
    private InputMode _inputMode;
    private VisualMode _visualMode;
    private VisualLineMode _visualLineMode;
    private VisualBlockMode _visualBlockMode;
    private Mode _currentMode;
    private View _panelView;
    private List<WorkspaceState> _workspaceHistory = new ArrayList<>();
    private WorkspaceState _currentWorkspace;

    public static Window getInstance() {
        return _instance;
    }

    public static void createInstance(Path path) {
        _instance = new Window(path);
    }

    public Window(Path path) {
        setupSplashScreen();
        setupViews(path != null && path.toFile().isDirectory() ? null : path);
        setupBindings();
        setupModes();
        initializeWorkspaceHistory();
        if (path != null && path.toFile().isDirectory()) {
            showDirectoryBrowser(path);
        }
    }

    private void ensureLayoutState() {
        if (_workspaceHistory == null) {
            _workspaceHistory = new ArrayList<>();
        }
        if (_bufferContextsByView == null) {
            _bufferContextsByView = new IdentityHashMap<>();
        }
        if (_bufferViewCounts == null) {
            _bufferViewCounts = new IdentityHashMap<>();
        }
        if (_bufferContext != null && _bufferContext.getBufferView() != null
                && !_bufferContextsByView.containsKey(_bufferContext.getBufferView())) {
            _bufferContextsByView.put(_bufferContext.getBufferView(), _bufferContext);
            _bufferViewCounts.put(_bufferContext, Math.max(1, _bufferViewCounts.getOrDefault(_bufferContext, 0)));
        }
        if (_workspaceView == null && _bufferContext != null) {
            _workspaceView = _bufferContext.getBufferView();
        }
        if (_activeBufferView == null && _bufferContext != null) {
            _activeBufferView = _bufferContext.getBufferView();
        }
        if (_activeView == null) {
            _activeView = _panelView != null ? _panelView : _activeBufferView;
        }
        if (_currentWorkspace == null && _workspaceView != null) {
            initializeWorkspaceHistory();
        }
    }

    private void setupModes() {
        rebuildModesForActiveBuffer(null);
    }

    public boolean setBufferPath(Path path) {
        if (path != null && path.toFile().isDirectory()) {
            return showDirectoryBrowser(path);
        }
        if (_currentWorkspace != null && _currentWorkspace._kind != WorkspaceKind.BUFFER) {
            return openBufferWorkspace(path);
        }
        ensureLayoutState();
        var currentBufferView = getEditableBufferView();
        var currentBufferContext = getBufferContextFor(currentBufferView);
        if (currentBufferView == null || currentBufferContext == null) {
            return false;
        }

        BufferContext nextBufferContext;
        try {
            nextBufferContext = new BufferContext(currentBufferView.getBounds(), path);
        } catch (Throwable e) {
            _log.error("Failed to open buffer " + path, e);
            return false;
        }
        ClangdLspPluginSupport.ensureStartedForProject(path);

        var nextBufferView = nextBufferContext.getBufferView();
        registerBufferView(nextBufferContext, nextBufferView);
        replaceViewInLayout(currentBufferView, nextBufferView);
        unregisterBufferView(currentBufferView);

        _bufferContext = nextBufferContext;
        _activeBufferView = nextBufferView;
        _activeView = nextBufferView;
        setupModes();
        activateView(nextBufferView);
        return true;
    }

    public boolean showDirectoryBrowser(Path directory) {
        if (directory == null || !directory.toFile().isDirectory()) {
            return false;
        }
        if (_currentWorkspace != null
                && _currentWorkspace._kind == WorkspaceKind.DIRECTORY
                && _workspaceView instanceof DirectoryBrowserView browser) {
            browser.setDirectory(directory);
            moveWorkspaceToFront(_currentWorkspace);
            activateView(browser);
            return true;
        }
        return openDirectoryWorkspace(directory);
    }

    public boolean showMailWorkspace(org.fisk.swim.mail.MailClient client) {
        if (client == null) {
            return false;
        }
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.MAIL) {
            moveWorkspaceToFront(_currentWorkspace);
            activateView(_workspaceView);
            return true;
        }
        return openMailWorkspace(client);
    }

    public BufferView splitActiveBufferHorizontally() {
        return splitActiveBuffer(SplitView.Orientation.HORIZONTAL);
    }

    public BufferView splitActiveBufferVertically() {
        return splitActiveBuffer(SplitView.Orientation.VERTICAL);
    }

    public boolean splitActiveViewHorizontally(View view) {
        if (!splitView(getActiveView(), view, SplitView.Orientation.HORIZONTAL, 0.5)) {
            return false;
        }
        activateView(view);
        return true;
    }

    public boolean splitActiveViewVertically(View view) {
        if (!splitView(getActiveView(), view, SplitView.Orientation.VERTICAL, 0.5)) {
            return false;
        }
        activateView(view);
        return true;
    }

    public boolean closeActiveView() {
        return closeView(getActiveView());
    }

    public boolean closeOtherViews() {
        ensureLayoutState();
        var keepView = getEditableBufferView();
        if (keepView == null) {
            return false;
        }
        var leaves = getLeafViews();
        if (leaves.size() <= 1 && _panelView == null) {
            return false;
        }

        for (var view : leaves) {
            if (view == keepView) {
                continue;
            }
            if (view == _panelView) {
                _panelView = null;
            }
            if (view instanceof BufferView bufferView) {
                unregisterBufferView(bufferView);
            }
            view.removeFromParent();
        }

        keepView.removeFromParent();
        _workspaceView = keepView;
        _rootView.addSubview(keepView);
        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        _rootView.setNeedsRedraw();
        activateView(keepView);
        return true;
    }

    public boolean focusNextView() {
        ensureLayoutState();
        var leaves = getLeafViews();
        if (leaves.size() <= 1) {
            return false;
        }
        int index = leaves.indexOf(getActiveView());
        if (index < 0) {
            activateView(leaves.get(0));
            return true;
        }
        activateView(leaves.get((index + 1) % leaves.size()));
        return true;
    }

    public boolean focusPreviousView() {
        ensureLayoutState();
        var leaves = getLeafViews();
        if (leaves.size() <= 1) {
            return false;
        }
        int index = leaves.indexOf(getActiveView());
        if (index < 0) {
            activateView(leaves.get(0));
            return true;
        }
        activateView(leaves.get((index - 1 + leaves.size()) % leaves.size()));
        return true;
    }

    public boolean focusView(Direction direction) {
        ensureLayoutState();
        var currentView = getActiveView();
        if (currentView == null) {
            return false;
        }
        var nextView = findNeighbor(currentView, direction);
        if (nextView == null || nextView == currentView) {
            return false;
        }
        activateView(nextView);
        return true;
    }

    public void activateView(View view) {
        ensureLayoutState();
        if (view == null) {
            return;
        }
        _activeView = view;
        Mode previousMode = _currentMode;
        BufferContext previousBufferContext = _bufferContext;
        if (view instanceof BufferView bufferView) {
            var bufferContext = getBufferContextFor(bufferView);
            if (bufferContext != null) {
                _activeBufferView = bufferView;
                _bufferContext = bufferContext;
                if (bufferContext != previousBufferContext) {
                    rebuildModesForActiveBuffer(previousMode);
                } else if (_currentMode != null) {
                    bufferView.setFirstResponder(_currentMode);
                }
                if (_modeLineView != null) {
                    _modeLineView.setNeedsRedraw();
                }
            }
        }
        if (_rootView != null) {
            _rootView.setFirstResponder(view);
            _rootView.setNeedsRedraw();
        }
        if (_currentWorkspace != null) {
            captureCurrentWorkspace();
            moveWorkspaceToFront(_currentWorkspace);
        }
        refreshChromeState();
    }

    public CommandView getCommandView() {
        return _commandView;
    }

    CommandMenuView getCommandMenuView() {
        return _commandMenuView;
    }

    public KeyMenuView getKeyMenuView() {
        return _keyMenuView;
    }

    public View getActiveView() {
        ensureLayoutState();
        return _activeView;
    }

    BufferView getActiveBufferView() {
        ensureLayoutState();
        return _activeBufferView;
    }

    public Mode getCurrentMode() {
        return _currentMode;
    }

    public Mode getNormalMode() {
        return _normalMode;
    }

    public Mode getInputMode() {
        return _inputMode;
    }

    public Mode getVisualMode() {
        return _visualMode;
    }

    public Mode getVisualLineMode() {
        return _visualLineMode;
    }

    public Mode getVisualBlockMode() {
        return _visualBlockMode;
    }

    public boolean switchToRecentWindow(int oneBasedIndex) {
        if (oneBasedIndex <= 0 || oneBasedIndex > _workspaceHistory.size()) {
            return false;
        }
        return activateWorkspace(_workspaceHistory.get(oneBasedIndex - 1));
    }

    public boolean isShowingMailWorkspace() {
        return _currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.MAIL;
    }

    public boolean closeCurrentWorkspaceWindow() {
        if (_currentWorkspace == null || _currentWorkspace._kind == WorkspaceKind.BUFFER) {
            return false;
        }
        var closing = _currentWorkspace;
        _workspaceHistory.remove(closing);
        if (_workspaceView != null) {
            _workspaceView.removeFromParent();
        }
        if (_workspaceHistory.isEmpty()) {
            _currentWorkspace = null;
            return openBufferWorkspace(null);
        }
        _currentWorkspace = null;
        return activateWorkspace(_workspaceHistory.getFirst());
    }

    public void switchToMode(Mode mode) {
        if (_currentMode != null) {
            _currentMode.deactivate();
        }
        _currentMode = mode;
        if (_activeBufferView != null) {
            _activeBufferView.setFirstResponder(_currentMode);
        }
        if (_modeLineView != null) {
            _modeLineView.setNeedsRedraw();
        }
        mode.activate();
        refreshChromeState();
    }

    public BufferContext getBufferContext() {
        ensureLayoutState();
        return _bufferContext;
    }

    public ModeLineView getModeLineView() {
        return _modeLineView;
    }

    public void dispose() {
        ensureLayoutState();
        if (_bufferContextsByView != null) {
            for (var context : new HashSet<>(_bufferContextsByView.values())) {
                context.getBuffer().close();
            }
            _bufferContextsByView.clear();
        } else if (_bufferContext != null) {
            _bufferContext.getBuffer().close();
        }
        if (_bufferViewCounts != null) {
            _bufferViewCounts.clear();
        }
        if (_modeLineView != null) {
            _modeLineView.close();
        }
        MailStatusService.shutdownInstance();
        _instance = null;
    }

    public void setRootView(View view) {
        _rootView = view;
        _size = view.getBounds().getSize();
        refreshChromeState();
    }

    public View getRootView() {
        return _rootView;
    }

    public void refreshChromeState() {
        ensureLayoutState();
        if (_keyMenuView != null) {
            EventResponder responder = _rootView == null ? null : _rootView.getFirstResponder();
            _keyMenuView.setModeName(_currentMode == null ? "NORMAL" : _currentMode.getName());
            _keyMenuView.setBufferFocused(_activeBufferView != null && responder == _activeBufferView);
            _keyMenuView.setFocusContext(focusContextFor(responder));
            _keyMenuView.setContextLabel(contextLabelFor(responder));
            _keyMenuView.setCommandState(_commandView == null || !_commandView.isActive() ? null : _commandView.getPrompt(),
                    _commandView == null ? "" : _commandView.getCommandText());
            _keyMenuView.setChatPending(responder instanceof ChatPanelView chatPanelView && chatPanelView.isPending());
            _keyMenuView.setRecentWindows(recentWindowLabels());
            _keyMenuView.setNeedsRedraw();
        }
        if (_commandMenuView != null) {
            EventResponder responder = _rootView == null ? null : _rootView.getFirstResponder();
            if (responder instanceof ChatPanelView chatPanelView && chatPanelView.isCommandInputActive()) {
                _commandMenuView.setState(chatPanelView.getCommandMenuState());
            } else {
                _commandMenuView.setState(_commandView == null ? CommandView.CommandMenuState.hidden() : _commandView.getMenuState());
            }
        }
        if (_modeLineView != null) {
            _modeLineView.setNeedsRedraw();
        }
        if (_commandView != null) {
            _commandView.setNeedsRedraw();
        }
        if (_mailNotificationView != null) {
            _mailNotificationView.setNeedsRedraw();
        }
    }

    private void initializeWorkspaceHistory() {
        var initial = captureCurrentWorkspace();
        initial._kind = WorkspaceKind.BUFFER;
        _workspaceHistory.clear();
        _workspaceHistory.add(initial);
        _currentWorkspace = initial;
    }

    private KeyMenuView.FocusContext focusContextFor(EventResponder responder) {
        if (responder == null) {
            return KeyMenuView.FocusContext.OTHER;
        }
        if (responder == _commandView) {
            return KeyMenuView.FocusContext.COMMAND;
        }
        if (responder instanceof ListView) {
            return KeyMenuView.FocusContext.LIST_PANEL;
        }
        if (responder instanceof JavaDefinitionPopupView) {
            return KeyMenuView.FocusContext.LIST_PANEL;
        }
        if (responder instanceof ProjectSearchPanelView) {
            return KeyMenuView.FocusContext.SEARCH_PANEL;
        }
        if (responder instanceof TextPanelView) {
            return KeyMenuView.FocusContext.TEXT_PANEL;
        }
        if (responder instanceof ChatPanelView) {
            return KeyMenuView.FocusContext.CHAT_PANEL;
        }
        if (responder instanceof BufferView) {
            return KeyMenuView.FocusContext.BUFFER;
        }
        if (responder instanceof View) {
            return KeyMenuView.FocusContext.PANEL;
        }
        return KeyMenuView.FocusContext.OTHER;
    }

    private String contextLabelFor(EventResponder responder) {
        if (responder == null) {
            return null;
        }
        if (responder == _commandView) {
            if ("/".equals(_commandView.getPrompt())) {
                return "forward search";
            }
            if ("?".equals(_commandView.getPrompt())) {
                return "reverse search";
            }
            return "command palette";
        }
        if (responder instanceof ListView listView) {
            return listView.getTitle();
        }
        if (responder instanceof JavaDefinitionPopupView popupView) {
            return popupView.getTitle();
        }
        if (responder instanceof ProjectSearchPanelView searchPanelView) {
            return searchPanelView.getTitle();
        }
        if (responder instanceof DirectoryBrowserView directoryBrowserView) {
            return directoryBrowserView.getTitle();
        }
        if (responder instanceof TextPanelView textPanelView) {
            return textPanelView.getTitle();
        }
        if (responder instanceof ShellPanelView) {
            return "shell input active";
        }
        if (responder instanceof ChatPanelView chatPanelView) {
            return chatPanelView.getTitle();
        }
        if (responder instanceof PluginPanelView pluginPanelView) {
            return pluginPanelView.getTitle();
        }
        return null;
    }

    public void update(boolean forced) {
        ensureLayoutState();
        _log.debug("Maybe relayout");
        if (!forced && !_rootView.needsRedraw()) {
            _log.debug("Relayout not needed");
            return;
        }
        var screen = TerminalContext.getInstance().getScreen();
        var terminalContext = TerminalContext.getInstance();
        var terminalSize = terminalContext.getTerminalSize();
        if (terminalSize == null) {
            terminalSize = screen.doResizeIfNecessary();
        }
        if (terminalSize == null) {
            terminalSize = new TerminalSize(_rootView.getBounds().getSize().getWidth(),
                    _rootView.getBounds().getSize().getHeight());
        }
        _log.debug("Terminal size: " + terminalSize.getColumns() + ", " + terminalSize.getRows());
        var size = Size.create(terminalSize.getColumns(), terminalSize.getRows());
        if (_size == null || !_size.equals(size)) {
            _log.debug("Relayout");
            applyLayout(size);
        } else {
            _log.debug("Relayout not needed due to same size");
        }
        _rootView.update(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows()), forced);
        _size = size;
        var cursor = _rootView.getCursor();
        if (cursor != null) {
            screen.setCursorPosition(new TerminalPosition(cursor.getXOnScreen(), cursor.getYOnScreen()));
        }
        try {
            screen.refresh(RefreshType.DELTA);
        } catch (IOException e) {}
    }

    @Override
    public void draw(Rect rect) {
        _rootView.draw(rect);
    }

    public boolean isShowingList() {
        return _panelView instanceof ListView;
    }

    public boolean isShowingPanel() {
        return _panelView != null;
    }

    public View getPanelView() {
        return _panelView;
    }

    public boolean showPanel(View panelView) {
        double ratio = panelView instanceof ChatPanelView ? 0.30 : 2.0 / 3.0;
        return showPanel(panelView, SplitView.Orientation.VERTICAL, ratio, false);
    }

    public boolean showSidePanel(View panelView, boolean leftSide, double ratio) {
        return showPanel(panelView, SplitView.Orientation.HORIZONTAL, ratio, leftSide);
    }

    private boolean showPanel(View panelView, SplitView.Orientation orientation, double ratio, boolean panelFirst) {
        ensureLayoutState();
        if (_panelView != null) {
            return false;
        }
        if (!splitView(getActiveView(), panelView, orientation, ratio, !panelFirst)) {
            return false;
        }
        _panelView = panelView;
        activateView(panelView);
        return true;
    }

    public void showList(List<? extends ListItem> list, String title) {
        showPanel(new ListView(Rect.create(0, 0, 0, 0), list, title));
    }

    public void showTextPanel(String title, String text) {
        showPanel(new TextPanelView(Rect.create(0, 0, 0, 0), title, text));
    }

    public void hideList() {
        if (!isShowingList()) {
            return;
        }
        hidePanel();
    }

    public void hidePanel() {
        if (_panelView == null) {
            return;
        }
        closeView(_panelView);
    }

    public void focusActiveBuffer() {
        if (_activeBufferView != null) {
            activateView(_activeBufferView);
        }
    }

    public boolean openBufferLocation(Path path, int lineNumber, int columnNumber) {
        if (!setBufferPath(path)) {
            return false;
        }
        return revealBufferLocation(getBufferContext(), lineNumber, columnNumber);
    }

    private void setupSplashScreen() {
        var terminalContext = TerminalContext.getInstance();
        var screen = terminalContext.getScreen();
        var terminalSize = terminalContext.getTerminalSize();
        var textGraphics = terminalContext.getGraphics();
        _log.debug("Draw splash screen");
        var attrString = new AttributedString();
        var str = " swim is warming up ";
        attrString.append(str, UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_BLUE);
        attrString.drawAt(Point.create(terminalSize.getColumns() / 2 - str.length() / 2, terminalSize.getRows() / 2), textGraphics);
        screen.setCursorPosition(new TerminalPosition(0, 0));
        try {
            screen.refresh(RefreshType.DELTA);
        } catch (IOException e) {}
    }

    private void setupViews(Path path) {
        ensureLayoutState();
        var terminalContext = TerminalContext.getInstance();
        var terminalSize = terminalContext.getTerminalSize();

        _log.debug("Terminal size: " + terminalSize.getColumns() + ", " + terminalSize.getRows());

        int initialMenuHeight = Math.min(MIN_TOP_MENU_HEIGHT, terminalSize.getRows());
        _bufferContext = new BufferContext(Rect.create(0, initialMenuHeight, terminalSize.getColumns(),
                Math.max(0, terminalSize.getRows() - initialMenuHeight - 2)), path);
        registerBufferView(_bufferContext, _bufferContext.getBufferView());

        _rootView = new View(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows()));
        _rootView.setBackgroundColour(UiTheme.ROOT_BACKGROUND);

        _keyMenuView = new KeyMenuView(Rect.create(0, 0, terminalSize.getColumns(), Math.min(MIN_TOP_MENU_HEIGHT,
                terminalSize.getRows())));
        _keyMenuView.setResizeMask(View.RESIZE_MASK_TOP | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT
                | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_keyMenuView);

        _workspaceView = _bufferContext.getBufferView();
        _activeView = _workspaceView;
        _activeBufferView = _bufferContext.getBufferView();
        _rootView.addSubview(_workspaceView);

        _modeLineView = new ModeLineView(Rect.create(0, Math.max(0, terminalSize.getRows() - 2), terminalSize.getColumns(),
                terminalSize.getRows() >= 2 ? 1 : 0));
        _modeLineView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_modeLineView);

        _commandView = new CommandView(Rect.create(0, Math.max(0, terminalSize.getRows() - 1), terminalSize.getColumns(),
                terminalSize.getRows() >= 1 ? 1 : 0));
        _commandView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_commandView);

        _commandMenuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        _commandMenuView.setResizeMask(View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_TOP
                | View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_WIDTH | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_commandMenuView);

        _mailNotificationView = new MailNotificationView(Rect.create(0, 0, 0, 0));
        _mailNotificationView.setResizeMask(View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_TOP
                | View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_WIDTH | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_mailNotificationView);

        _rootView.setFirstResponder(_workspaceView);
        _size = _rootView.getBounds().getSize();
        applyLayout(_size);
        MailStatusService.getInstance();
    }

    private void setupBindings() {
        var eventThread = EventThread.getInstance();
        var responders = eventThread.getResponder();
        responders.addEventResponder(new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                Window.this.getCommandView().setMessage(null);
                return Response.NO;
            }

            @Override
            public void respond() {
            }
        });
        responders.addEventResponder(new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                refreshChromeState();
                if (_keyMenuView != null && events.remaining() == 0 && events.current().getKeyType() != KeyType.EOF) {
                    _keyMenuView.observe(events.current());
                }
                return Response.NO;
            }

            @Override
            public void respond() {
            }
        });
        responders.addEventResponder(_rootView);
        responders.addEventResponder(new EventResponder() {
            private Integer _windowIndex;

            @Override
            public Response processEvent(KeyStrokes events) {
                _windowIndex = null;
                var sequence = new ArrayList<com.googlecode.lanterna.input.KeyStroke>();
                for (var keyStroke : events) {
                    sequence.add(keyStroke);
                }
                if (sequence.isEmpty()) {
                    return Response.NO;
                }
                var first = sequence.getFirst();
                if (first.getKeyType() != KeyType.Character || first.getCharacter() != 'w') {
                    return Response.NO;
                }
                if (sequence.size() == 1) {
                    return Response.NO;
                }
                var second = sequence.get(1);
                if (second.getKeyType() != KeyType.Character || !Character.isDigit(second.getCharacter())) {
                    return Response.NO;
                }
                StringBuilder digits = new StringBuilder();
                for (int i = 1; i < sequence.size(); i++) {
                    var stroke = sequence.get(i);
                    if (stroke.getKeyType() == KeyType.Enter) {
                        if (digits.isEmpty()) {
                            return Response.NO;
                        }
                        _windowIndex = Integer.parseInt(digits.toString());
                        return Response.YES;
                    }
                    if (stroke.getKeyType() != KeyType.Character || !Character.isDigit(stroke.getCharacter())) {
                        return Response.NO;
                    }
                    digits.append(stroke.getCharacter());
                }
                return Response.MAYBE;
            }

            @Override
            public void respond() {
                if (_windowIndex != null) {
                    switchToRecentWindow(_windowIndex);
                }
            }
        });
        responders.addEventResponder(new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                if (events.current().getKeyType() == KeyType.EOF) {
                    return Response.YES;
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                SwimRuntime.exit();
            }
        });
    }

    private BufferView splitActiveBuffer(SplitView.Orientation orientation) {
        ensureLayoutState();
        var currentBufferView = getEditableBufferView();
        var bufferContext = getBufferContextFor(currentBufferView);
        if (currentBufferView == null || bufferContext == null) {
            return null;
        }
        var nextBufferView = new BufferView(Rect.create(0, 0, 0, 0), bufferContext);
        registerBufferView(bufferContext, nextBufferView);
        if (!splitView(currentBufferView, nextBufferView, orientation, 0.5)) {
            unregisterBufferView(nextBufferView);
            return null;
        }
        activateView(nextBufferView);
        return nextBufferView;
    }

    private boolean splitView(View existingView, View newView, SplitView.Orientation orientation, double ratio) {
        return splitView(existingView, newView, orientation, ratio, true);
    }

    private boolean splitView(View existingView, View newView, SplitView.Orientation orientation, double ratio,
            boolean existingFirst) {
        ensureLayoutState();
        if (existingView == null || newView == null || existingView == newView) {
            return false;
        }

        var previousParent = existingView.getParent();
        boolean replacingWorkspaceRoot = existingView == _workspaceView;
        var bounds = existingView.getBounds();
        existingView.removeFromParent();

        var splitView = existingFirst
                ? new SplitView(bounds, orientation, existingView, newView, ratio)
                : new SplitView(bounds, orientation, newView, existingView, ratio);
        if (previousParent instanceof SplitView splitParent) {
            splitParent.replaceChild(existingView, splitView);
        } else if (replacingWorkspaceRoot) {
            _workspaceView = splitView;
            _rootView.addSubview(splitView);
        } else {
            return false;
        }

        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setNeedsRedraw();
        }
        return true;
    }

    private boolean closeView(View view) {
        ensureLayoutState();
        if (view == null || view == _workspaceView) {
            return false;
        }
        if (view instanceof BufferView && getBufferLeafCount() <= 1) {
            return false;
        }
        if (!(view.getParent() instanceof SplitView splitParent)) {
            return false;
        }

        var sibling = splitParent.getSibling(view);
        var grandParent = splitParent.getParent();
        boolean replacingWorkspaceRoot = splitParent == _workspaceView;
        view.removeFromParent();
        sibling.removeFromParent();
        splitParent.removeFromParent();

        if (grandParent instanceof SplitView grandSplit) {
            grandSplit.replaceChild(splitParent, sibling);
        } else if (replacingWorkspaceRoot) {
            _workspaceView = sibling;
            _rootView.addSubview(sibling);
        } else {
            return false;
        }

        if (view == _panelView) {
            _panelView = null;
        }
        if (view instanceof BufferView bufferView) {
            unregisterBufferView(bufferView);
            if (_activeBufferView == bufferView) {
                _activeBufferView = findFirstBufferView();
                _bufferContext = getBufferContextFor(_activeBufferView);
            }
        }

        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setNeedsRedraw();
        }

        if (_activeView == view || _activeView == null) {
            var focusTarget = findFocusableView(sibling);
            if (!(focusTarget instanceof BufferView) && _activeBufferView != null) {
                _bufferContext = getBufferContextFor(_activeBufferView);
            }
            activateView(focusTarget);
        } else {
            if (_activeBufferView != null) {
                _bufferContext = getBufferContextFor(_activeBufferView);
            }
            activateView(_activeView);
        }
        return true;
    }

    private void replaceViewInLayout(View existingView, View replacementView) {
        ensureLayoutState();
        var previousParent = existingView.getParent();
        boolean replacingWorkspaceRoot = existingView == _workspaceView;
        existingView.removeFromParent();

        if (previousParent instanceof SplitView splitParent) {
            splitParent.replaceChild(existingView, replacementView);
        } else if (replacingWorkspaceRoot) {
            _workspaceView = replacementView;
            _rootView.addSubview(replacementView);
        } else {
            throw new IllegalStateException("View is not part of the workspace layout");
        }

        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setNeedsRedraw();
        }
    }

    private void applyLayout(Size size) {
        if (_rootView == null || size == null) {
            return;
        }
        _rootView.setBounds(Rect.create(0, 0, size.getWidth(), size.getHeight()));
        int menuHeight = MIN_TOP_MENU_HEIGHT;
        if (_keyMenuView != null) {
            menuHeight = _keyMenuView.preferredHeight(size.getWidth(), size.getHeight());
        }
        menuHeight = Math.min(menuHeight, size.getHeight());
        int footerRows = Math.min(2, Math.max(0, size.getHeight() - menuHeight));
        int modeLineHeight = footerRows == 2 ? 1 : 0;
        int commandHeight = footerRows >= 1 ? 1 : 0;
        int contentTop = menuHeight;
        int contentHeight = Math.max(0, size.getHeight() - menuHeight - modeLineHeight - commandHeight);
        if (_keyMenuView != null) {
            _keyMenuView.setBounds(Rect.create(0, 0, size.getWidth(), menuHeight));
        }
        if (_workspaceView != null) {
            _workspaceView.setBounds(Rect.create(0, contentTop, size.getWidth(), contentHeight));
        }
        if (_modeLineView != null) {
            _modeLineView.setBounds(Rect.create(0, contentTop + contentHeight, size.getWidth(), modeLineHeight));
        }
        if (_commandView != null) {
            _commandView.setBounds(Rect.create(0, contentTop + contentHeight + modeLineHeight, size.getWidth(),
                    commandHeight));
        }
        if (_commandMenuView != null) {
            _commandMenuView.syncBounds();
        }
        if (_mailNotificationView != null) {
            _mailNotificationView.syncBounds();
        }
    }

    private void registerBufferView(BufferContext bufferContext, BufferView bufferView) {
        ensureLayoutState();
        if (_bufferContextsByView.containsKey(bufferView)) {
            return;
        }
        _bufferContextsByView.put(bufferView, bufferContext);
        _bufferViewCounts.merge(bufferContext, 1, Integer::sum);
    }

    private void unregisterBufferView(BufferView bufferView) {
        ensureLayoutState();
        var bufferContext = _bufferContextsByView.remove(bufferView);
        if (bufferContext == null) {
            return;
        }
        Integer count = _bufferViewCounts.get(bufferContext);
        if (count == null || count <= 1) {
            _bufferViewCounts.remove(bufferContext);
            bufferContext.getBuffer().close();
            return;
        }
        _bufferViewCounts.put(bufferContext, count - 1);
    }

    private BufferContext getBufferContextFor(BufferView bufferView) {
        ensureLayoutState();
        if (bufferView == null) {
            return null;
        }
        return _bufferContextsByView.get(bufferView);
    }

    private BufferView getEditableBufferView() {
        ensureLayoutState();
        if (_activeBufferView != null) {
            return _activeBufferView;
        }
        return _bufferContext != null ? _bufferContext.getBufferView() : null;
    }

    private boolean revealBufferLocation(BufferContext bufferContext, int lineNumber, int columnNumber) {
        if (bufferContext == null) {
            return false;
        }
        int targetLine = Math.max(1, lineNumber);
        int targetColumn = Math.max(1, columnNumber);
        String text = bufferContext.getBuffer().getString();
        int index = 0;
        int currentLine = 1;
        while (index < text.length() && currentLine < targetLine) {
            if (text.charAt(index++) == '\n') {
                currentLine++;
            }
        }
        int currentColumn = 1;
        while (index < text.length() && currentColumn < targetColumn && text.charAt(index) != '\n') {
            index++;
            currentColumn++;
        }
        bufferContext.getBuffer().getCursor().setPosition(index);
        bufferContext.getBufferView().adaptViewToCursor();
        bufferContext.getBufferView().setNeedsRedraw();
        if (_modeLineView != null) {
            _modeLineView.setNeedsRedraw();
        }
        return true;
    }

    private View findFocusableView(View view) {
        if (view instanceof SplitView splitView) {
            return findFocusableView(splitView.getFirstView());
        }
        return view;
    }

    private boolean activateWorkspace(WorkspaceState workspace) {
        if (workspace == null || workspace._workspaceView == null) {
            return false;
        }
        if (_currentWorkspace == workspace) {
            moveWorkspaceToFront(workspace);
            if (_rootView != null && workspace._activeView != null) {
                _rootView.setFirstResponder(workspace._activeView);
                _rootView.setNeedsRedraw();
            }
            refreshChromeState();
            return true;
        }
        if (_currentWorkspace != null) {
            captureCurrentWorkspace();
            if (_workspaceView != null) {
                _workspaceView.removeFromParent();
            }
        }
        restoreWorkspace(workspace);
        _currentWorkspace = workspace;
        if (_workspaceView != null && _workspaceView.getParent() == null) {
            _rootView.addSubview(_workspaceView);
        }
        moveWorkspaceToFront(workspace);
        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_activeBufferView != null && _currentMode != null) {
            _activeBufferView.setFirstResponder(_currentMode);
        }
        if (_rootView != null && _activeView != null) {
            _rootView.setFirstResponder(_activeView);
            _rootView.setNeedsRedraw();
        }
        refreshChromeState();
        return true;
    }

    private WorkspaceState createBufferWorkspace(Path path) {
        var workspace = new WorkspaceState();
        workspace._kind = WorkspaceKind.BUFFER;
        BufferContext context = new BufferContext(Rect.create(0, 0, 0, 0), path);
        workspace._bufferContext = context;
        workspace._workspaceView = context.getBufferView();
        workspace._activeView = context.getBufferView();
        workspace._activeBufferView = context.getBufferView();
        workspace._bufferContextsByView = new IdentityHashMap<>();
        workspace._bufferContextsByView.put(context.getBufferView(), context);
        workspace._bufferViewCounts = new IdentityHashMap<>();
        workspace._bufferViewCounts.put(context, 1);
        initializeBufferWorkspaceModes(workspace);
        return workspace;
    }

    private WorkspaceState createViewWorkspace(View view, WorkspaceKind kind) {
        var workspace = new WorkspaceState();
        workspace._kind = kind;
        workspace._workspaceView = view;
        workspace._activeView = view;
        workspace._bufferContextsByView = new IdentityHashMap<>();
        workspace._bufferViewCounts = new IdentityHashMap<>();
        return workspace;
    }

    private boolean openBufferWorkspace(Path path) {
        WorkspaceState workspace = createBufferWorkspace(path);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private boolean openDirectoryWorkspace(Path directory) {
        WorkspaceState workspace = createViewWorkspace(new DirectoryBrowserView(Rect.create(0, 0, 0, 0), directory),
                WorkspaceKind.DIRECTORY);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private boolean openMailWorkspace(org.fisk.swim.mail.MailClient client) {
        WorkspaceState workspace = createViewWorkspace(new MailPanelView(Rect.create(0, 0, 0, 0), client),
                WorkspaceKind.MAIL);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private void initializeBufferWorkspaceModes(WorkspaceState workspace) {
        var previousBufferContext = _bufferContext;
        var previousActiveBufferView = _activeBufferView;
        var previousNormalMode = _normalMode;
        var previousInputMode = _inputMode;
        var previousVisualMode = _visualMode;
        var previousVisualLineMode = _visualLineMode;
        var previousVisualBlockMode = _visualBlockMode;
        var previousCurrentMode = _currentMode;
        try {
            _bufferContext = workspace._bufferContext;
            _activeBufferView = workspace._activeBufferView;
            workspace._normalMode = new NormalMode(this);
            workspace._inputMode = new InputMode(this);
            workspace._visualMode = new VisualMode(this);
            workspace._visualLineMode = new VisualLineMode(this);
            workspace._visualBlockMode = new VisualBlockMode(this);
            workspace._currentMode = workspace._normalMode;
        } finally {
            _bufferContext = previousBufferContext;
            _activeBufferView = previousActiveBufferView;
            _normalMode = previousNormalMode;
            _inputMode = previousInputMode;
            _visualMode = previousVisualMode;
            _visualLineMode = previousVisualLineMode;
            _visualBlockMode = previousVisualBlockMode;
            _currentMode = previousCurrentMode;
        }
    }

    private void rebuildModesForActiveBuffer(Mode previousMode) {
        ensureLayoutState();
        _normalMode = new NormalMode(this);
        _inputMode = new InputMode(this);
        _visualMode = new VisualMode(this);
        _visualLineMode = new VisualLineMode(this);
        _visualBlockMode = new VisualBlockMode(this);

        if (previousMode instanceof InputMode) {
            _currentMode = _inputMode;
        } else if (previousMode instanceof VisualLineMode) {
            _currentMode = _visualLineMode;
        } else if (previousMode instanceof VisualBlockMode) {
            _currentMode = _visualBlockMode;
        } else if (previousMode instanceof VisualMode) {
            _currentMode = _visualMode;
        } else {
            _currentMode = _normalMode;
        }

        if (_activeBufferView != null) {
            _activeBufferView.setFirstResponder(_currentMode);
        }
        refreshChromeState();
    }

    private WorkspaceState captureCurrentWorkspace() {
        WorkspaceState workspace = _currentWorkspace == null ? new WorkspaceState() : _currentWorkspace;
        workspace._workspaceView = _workspaceView;
        workspace._activeView = _activeView;
        if (workspace._kind == null) {
            workspace._kind = determineWorkspaceKind();
        }
        if (workspace._kind == WorkspaceKind.BUFFER) {
            workspace._activeBufferView = _activeBufferView;
            workspace._bufferContext = _bufferContext;
            workspace._bufferContextsByView = _bufferContextsByView;
            workspace._bufferViewCounts = _bufferViewCounts;
            workspace._normalMode = _normalMode;
            workspace._inputMode = _inputMode;
            workspace._visualMode = _visualMode;
            workspace._visualLineMode = _visualLineMode;
            workspace._visualBlockMode = _visualBlockMode;
            workspace._currentMode = _currentMode;
        }
        workspace._panelView = _panelView;
        return workspace;
    }

    private WorkspaceKind determineWorkspaceKind() {
        if (_workspaceView instanceof DirectoryBrowserView) {
            return WorkspaceKind.DIRECTORY;
        }
        if (_workspaceView instanceof MailPanelView) {
            return WorkspaceKind.MAIL;
        }
        return WorkspaceKind.BUFFER;
    }

    private void restoreWorkspace(WorkspaceState workspace) {
        _workspaceView = workspace._workspaceView;
        _activeView = workspace._activeView;
        if (workspace._kind == WorkspaceKind.BUFFER) {
            _activeBufferView = workspace._activeBufferView;
            _bufferContext = workspace._bufferContext;
            _bufferContextsByView = workspace._bufferContextsByView;
            _bufferViewCounts = workspace._bufferViewCounts;
            _normalMode = workspace._normalMode;
            _inputMode = workspace._inputMode;
            _visualMode = workspace._visualMode;
            _visualLineMode = workspace._visualLineMode;
            _visualBlockMode = workspace._visualBlockMode;
            _currentMode = workspace._currentMode;
        }
        _panelView = workspace._panelView;
    }

    private void moveWorkspaceToFront(WorkspaceState workspace) {
        _workspaceHistory.remove(workspace);
        _workspaceHistory.add(0, workspace);
    }

    private List<String> recentWindowLabels() {
        var labels = new ArrayList<String>();
        for (int i = 0; i < _workspaceHistory.size(); i++) {
            labels.add((i + 1) + ":" + workspaceLabel(_workspaceHistory.get(i)));
        }
        return labels;
    }

    private String workspaceLabel(WorkspaceState workspace) {
        if (workspace == null || workspace._workspaceView == null) {
            return "(none)";
        }
        return switch (workspace._kind) {
        case DIRECTORY -> workspace._workspaceView instanceof DirectoryBrowserView browser ? browser.getTitle() : "directory";
        case MAIL -> "mail";
        case BUFFER -> {
            Path path = workspace._bufferContext == null ? null : workspace._bufferContext.getBuffer().getPath();
            if (path == null) {
                yield "*scratch*";
            }
            Path root = org.fisk.swim.fileindex.ProjectPaths.getProjectRootPath(path);
            if (root != null) {
                yield root.relativize(path).toString();
            }
            yield path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
        };
    }

    private int getBufferLeafCount() {
        int count = 0;
        for (var view : getLeafViews()) {
            if (view instanceof BufferView) {
                count++;
            }
        }
        return count;
    }

    private BufferView findFirstBufferView() {
        for (var view : getLeafViews()) {
            if (view instanceof BufferView bufferView) {
                return bufferView;
            }
        }
        return null;
    }

    private List<View> getLeafViews() {
        var leaves = new java.util.ArrayList<View>();
        collectLeafViews(_workspaceView, leaves);
        return leaves;
    }

    private void collectLeafViews(View view, List<View> leaves) {
        if (view == null) {
            return;
        }
        if (view instanceof SplitView splitView) {
            collectLeafViews(splitView.getFirstView(), leaves);
            collectLeafViews(splitView.getSecondView(), leaves);
            return;
        }
        leaves.add(view);
    }

    private View findNeighbor(View sourceView, Direction direction) {
        Rect sourceBounds = absoluteBounds(sourceView);
        View bestView = null;
        int bestPrimaryDistance = Integer.MAX_VALUE;
        int bestSecondaryDistance = Integer.MAX_VALUE;

        for (var candidate : getLeafViews()) {
            if (candidate == sourceView) {
                continue;
            }
            Rect candidateBounds = absoluteBounds(candidate);
            if (!isCandidateInDirection(sourceBounds, candidateBounds, direction)) {
                continue;
            }

            int primaryDistance = primaryDistance(sourceBounds, candidateBounds, direction);
            int secondaryDistance = secondaryDistance(sourceBounds, candidateBounds, direction);
            if (primaryDistance < bestPrimaryDistance
                    || (primaryDistance == bestPrimaryDistance && secondaryDistance < bestSecondaryDistance)) {
                bestView = candidate;
                bestPrimaryDistance = primaryDistance;
                bestSecondaryDistance = secondaryDistance;
            }
        }
        return bestView;
    }

    private static Rect absoluteBounds(View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        View parent = view.getParent();
        while (parent != null) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
            parent = parent.getParent();
        }
        return Rect.create(x, y, view.getBounds().getSize().getWidth(), view.getBounds().getSize().getHeight());
    }

    private static boolean isCandidateInDirection(Rect source, Rect candidate, Direction direction) {
        int sourceLeft = source.getPoint().getX();
        int sourceRight = sourceLeft + source.getSize().getWidth();
        int sourceTop = source.getPoint().getY();
        int sourceBottom = sourceTop + source.getSize().getHeight();
        int candidateLeft = candidate.getPoint().getX();
        int candidateRight = candidateLeft + candidate.getSize().getWidth();
        int candidateTop = candidate.getPoint().getY();
        int candidateBottom = candidateTop + candidate.getSize().getHeight();

        return switch (direction) {
        case LEFT -> candidateRight <= sourceLeft && overlaps(candidateTop, candidateBottom, sourceTop, sourceBottom);
        case RIGHT -> candidateLeft >= sourceRight && overlaps(candidateTop, candidateBottom, sourceTop, sourceBottom);
        case UP -> candidateBottom <= sourceTop && overlaps(candidateLeft, candidateRight, sourceLeft, sourceRight);
        case DOWN -> candidateTop >= sourceBottom && overlaps(candidateLeft, candidateRight, sourceLeft, sourceRight);
        };
    }

    private static int primaryDistance(Rect source, Rect candidate, Direction direction) {
        int sourceLeft = source.getPoint().getX();
        int sourceRight = sourceLeft + source.getSize().getWidth();
        int sourceTop = source.getPoint().getY();
        int sourceBottom = sourceTop + source.getSize().getHeight();
        int candidateLeft = candidate.getPoint().getX();
        int candidateRight = candidateLeft + candidate.getSize().getWidth();
        int candidateTop = candidate.getPoint().getY();
        int candidateBottom = candidateTop + candidate.getSize().getHeight();

        return switch (direction) {
        case LEFT -> sourceLeft - candidateRight;
        case RIGHT -> candidateLeft - sourceRight;
        case UP -> sourceTop - candidateBottom;
        case DOWN -> candidateTop - sourceBottom;
        };
    }

    private static int secondaryDistance(Rect source, Rect candidate, Direction direction) {
        int sourceCenterX = source.getPoint().getX() + source.getSize().getWidth() / 2;
        int sourceCenterY = source.getPoint().getY() + source.getSize().getHeight() / 2;
        int candidateCenterX = candidate.getPoint().getX() + candidate.getSize().getWidth() / 2;
        int candidateCenterY = candidate.getPoint().getY() + candidate.getSize().getHeight() / 2;

        return switch (direction) {
        case LEFT, RIGHT -> Math.abs(candidateCenterY - sourceCenterY);
        case UP, DOWN -> Math.abs(candidateCenterX - sourceCenterX);
        };
    }

    private static boolean overlaps(int startA, int endA, int startB, int endB) {
        return Math.min(endA, endB) - Math.max(startA, startB) > 0;
    }
}
