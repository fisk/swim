package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
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

    public static Window getInstance() {
        return _instance;
    }

    public static void createInstance(Path path) {
        _instance = new Window(path);
    }

    public Window(Path path) {
        setupSplashScreen();
        setupViews(path);
        setupBindings();
        setupModes();
    }

    private void ensureLayoutState() {
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
    }

    private void setupModes() {
        rebuildModesForActiveBuffer(null);
    }

    public boolean setBufferPath(Path path) {
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
        if (_keyMenuView != null) {
            EventResponder responder = _rootView == null ? null : _rootView.getFirstResponder();
            _keyMenuView.setModeName(_currentMode == null ? "NORMAL" : _currentMode.getName());
            _keyMenuView.setBufferFocused(_activeBufferView != null && responder == _activeBufferView);
            _keyMenuView.setFocusContext(focusContextFor(responder));
            _keyMenuView.setContextLabel(contextLabelFor(responder));
            _keyMenuView.setCommandState(_commandView == null || !_commandView.isActive() ? null : _commandView.getPrompt(),
                    _commandView == null ? "" : _commandView.getCommandText());
            _keyMenuView.setChatPending(responder instanceof ChatPanelView chatPanelView && chatPanelView.isPending());
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

    public void showPanel(View panelView) {
        double ratio = panelView instanceof ChatPanelView ? 0.30 : 2.0 / 3.0;
        showPanel(panelView, SplitView.Orientation.VERTICAL, ratio, false);
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

        _rootView.setFirstResponder(_workspaceView);
        _size = _rootView.getBounds().getSize();
        applyLayout(_size);
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

    private View findFocusableView(View view) {
        if (view instanceof SplitView splitView) {
            return findFocusableView(splitView.getFirstView());
        }
        return view;
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
