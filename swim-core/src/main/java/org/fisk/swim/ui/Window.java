package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.config.EditorConfig;
import org.fisk.swim.config.EditorConfigStore;
import org.fisk.swim.config.EditorPaths;
import org.fisk.swim.config.EditorSession;
import org.fisk.swim.config.NormalModeRemap;
import org.fisk.swim.config.SessionLayoutNode;
import org.fisk.swim.config.SessionWorkspace;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.KeyStrokeEvent;
import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.fileindex.ProjectSearch;
import org.fisk.swim.lsp.DiagnosticActionProvider;
import org.fisk.swim.lsp.DiagnosticEntry;
import org.fisk.swim.lsp.DiagnosticService;
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
import org.fisk.swim.todo.TodoStore;
import org.fisk.swim.todo.TodoUiSupport;
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
        MAIL,
        SLACK,
        PLUGIN,
        SHELL,
        TODO
    }

    private static final class WorkspaceState {
        private WorkspaceKind _kind;
        private View _workspaceView;
        private View _activeView;
        private BufferView _activeBufferView;
        private BufferContext _bufferContext;
        private ShellPanelView _shellView;
        private BufferContext _shellBrowseContext;
        private IdentityHashMap<BufferView, BufferContext> _bufferContextsByView;
        private IdentityHashMap<BufferContext, Integer> _bufferViewCounts;
        private NormalMode _normalMode;
        private InputMode _inputMode;
        private VisualMode _visualMode;
        private VisualLineMode _visualLineMode;
        private VisualBlockMode _visualBlockMode;
        private Mode _currentMode;
        private View _panelView;
        private String _pluginId;
    }

    public record OpenBufferEntry(Path path, String label) {
    }

    private record CompiledRemap(List<RecordedKey> lhs, List<RecordedKey> rhs, String lhsText) {
    }

    private record BufferLayoutResult(
            View root,
            IdentityHashMap<BufferView, BufferContext> contextsByView,
            IdentityHashMap<BufferContext, Integer> counts,
            BufferView activeView,
            BufferContext activeContext) {
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
    private DiagnosticPopupView _diagnosticPopupView;
    private CodeActionPopupView _codeActionPopupView;
    private TodoQuickCaptureView _todoQuickCaptureView;
    private EventResponder _todoQuickCaptureReturnResponder;
    private boolean _hoverDiagnosticsVisible;
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
    private ShellPanelView _savedPanelShell;
    private List<WorkspaceState> _workspaceHistory = new ArrayList<>();
    private WorkspaceState _currentWorkspace;
    private EditorState _editorState;
    private final EditorPaths _editorPaths;
    private final EditorConfig _editorConfig;
    private final List<CompiledRemap> _normalModeRemaps;
    private boolean _replayingRemap;
    private SearchLocationList _quickfixList = SearchLocationList.empty("Quickfix");
    private SearchLocationList _locationList = SearchLocationList.empty("Location");

    public static Window getInstance() {
        return _instance;
    }

    public static void createInstance(Path path) {
        _instance = new Window(path);
    }

    public Window(Path path) {
        boolean restoreOnReload = Boolean.getBoolean("swim.session.restore_on_reload");
        _editorPaths = EditorPaths.fromUserHome();
        _editorConfig = EditorConfigStore.load(_editorPaths);
        _normalModeRemaps = compileRemaps(_editorConfig.normalModeRemaps());
        applyConfiguredOptions(_editorConfig);
        setupSplashScreen();
        Path initialPath = restoreOnReload ? null : path != null && path.toFile().isDirectory() ? null : path;
        setupViews(initialPath);
        setupBindings();
        setupModes();
        initializeWorkspaceHistory();
        boolean restored = false;
        if (restoreOnReload) {
            restored = restoreLastSession();
        }
        if (!restored && path != null && path.toFile().isDirectory()) {
            showDirectoryBrowser(path);
        }
        if (!restored && restoreOnReload && path != null && !path.toFile().isDirectory()) {
            setBufferPath(path);
        }
        runStartupCommands();
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
        if (_editorState == null) {
            _editorState = new EditorState();
        }
    }

    private void setupModes() {
        rebuildModesForActiveBuffer(null);
    }

    public boolean setBufferPath(Path path) {
        hideTransientDiagnostics();
        if (path != null && path.toFile().isDirectory()) {
            return showDirectoryBrowser(path);
        }
        if (_currentWorkspace != null
                && _currentWorkspace._kind == WorkspaceKind.BUFFER
                && _activeView instanceof DirectoryBrowserView directoryBrowserView) {
            return replaceDirectoryBrowserFrameWithBuffer(directoryBrowserView, path);
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
                && _currentWorkspace._kind == WorkspaceKind.BUFFER
                && _activeView instanceof DirectoryBrowserView browserView) {
            browserView.setDirectory(directory);
            activateView(browserView);
            return true;
        }
        if (canEmbedDirectoryBrowserInCurrentWorkspace()) {
            return replaceActiveBufferFrameWithDirectoryBrowser(directory);
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
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.MAIL) {
                return activateWorkspace(workspace);
            }
        }
        return openMailWorkspace(client);
    }

    public boolean showSlackWorkspace(org.fisk.swim.slack.SlackClient client) {
        if (client == null) {
            return false;
        }
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.SLACK) {
            moveWorkspaceToFront(_currentWorkspace);
            activateView(_workspaceView);
            return true;
        }
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.SLACK) {
                return activateWorkspace(workspace);
            }
        }
        return openSlackWorkspace(client);
    }

    public boolean showTodoWorkspace(TodoStore store) {
        if (store == null) {
            return false;
        }
        ensureLayoutState();
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.TODO) {
            moveWorkspaceToFront(_currentWorkspace);
            activateView(_workspaceView);
            return true;
        }
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.TODO) {
                return activateWorkspace(workspace);
            }
        }
        return openTodoWorkspace(store);
    }

    public boolean showTodoQuickCapture(TodoStore store) {
        if (_rootView == null || store == null) {
            return false;
        }
        ensureLayoutState();
        if (isShowingTodoQuickCapture()) {
            return false;
        }
        _todoQuickCaptureReturnResponder = _rootView.getFirstResponder();
        _todoQuickCaptureView = new TodoQuickCaptureView(Rect.create(0, 0, 0, 0));
        _todoQuickCaptureView.setOnCancel(() -> hideTodoQuickCapture(false));
        _todoQuickCaptureView.setOnSubmit(title -> {
            try {
                store.createInboxItem(title);
                refreshTodoWorkspaceViews();
                if (_commandView != null) {
                    _commandView.setMessage("Added to Todo Inbox");
                }
                hideTodoQuickCapture(true);
            } catch (RuntimeException e) {
                if (_commandView != null) {
                    _commandView.setMessage(e.getMessage() == null ? "Unable to add Todo item" : e.getMessage());
                }
                hideTodoQuickCapture(false);
            }
        });
        _rootView.addSubview(_todoQuickCaptureView);
        _todoQuickCaptureView.syncBounds();
        _rootView.setFirstResponder(_todoQuickCaptureView);
        refreshChromeState();
        _rootView.setNeedsRedraw();
        return true;
    }

    public boolean sendActiveMailCompose() {
        MailPanelView panel = findMailPanelView(_workspaceView);
        if (panel == null || !panel.isComposeActive()) {
            return false;
        }
        panel.triggerSend();
        return true;
    }

    public boolean sendActiveSlackCompose() {
        SlackPanelView panel = findSlackPanelView(_workspaceView);
        if (panel == null || !panel.isComposeActive()) {
            return false;
        }
        panel.triggerSend();
        return true;
    }

    public boolean showPluginWorkspace(String pluginId, SwimPanel panel) {
        if (pluginId == null || panel == null) {
            return false;
        }
        ensureLayoutState();
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.PLUGIN && pluginId.equals(workspace._pluginId)) {
                return activateWorkspace(workspace);
            }
        }
        return openPluginWorkspace(pluginId, panel);
    }

    public boolean showShellWorkspace() {
        return openShellWorkspace();
    }

    public boolean showShellSplitHorizontally() {
        return openShellSplit(SplitView.Orientation.HORIZONTAL);
    }

    public boolean showShellSplitVertically() {
        return openShellSplit(SplitView.Orientation.VERTICAL);
    }

    public boolean enterShellBrowse(ShellPanelView shellView) {
        ensureLayoutState();
        if (_currentWorkspace == null || _currentWorkspace._kind != WorkspaceKind.SHELL || shellView == null
                || _currentWorkspace._shellView != shellView) {
            return false;
        }
        var browse = createShellBrowseContext(shellView);
        _currentWorkspace._shellBrowseContext = browse;
        _currentWorkspace._workspaceView = browse.getBufferView();
        _currentWorkspace._activeView = browse.getBufferView();
        _currentWorkspace._activeBufferView = browse.getBufferView();
        _currentWorkspace._bufferContext = browse;
        _currentWorkspace._bufferContextsByView = new IdentityHashMap<>();
        _currentWorkspace._bufferContextsByView.put(browse.getBufferView(), browse);
        _currentWorkspace._bufferViewCounts = new IdentityHashMap<>();
        _currentWorkspace._bufferViewCounts.put(browse, 1);
        initializeBufferWorkspaceModes(_currentWorkspace);
        installWorkspaceBufferModes(_currentWorkspace);

        detachWorkspaceView(_currentWorkspace);
        _workspaceView = browse.getBufferView();
        _activeView = browse.getBufferView();
        _activeBufferView = browse.getBufferView();
        _bufferContext = browse;
        if (_workspaceView.getParent() == null) {
            attachWorkspaceView();
        }
        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_currentMode != null) {
            _activeBufferView.setFirstResponder(_currentMode);
        }
        if (_rootView != null) {
            _rootView.setFirstResponder(_activeView);
            _rootView.setNeedsRedraw();
        }
        refreshChromeState();
        return true;
    }

    public boolean exitShellBrowseToPrompt() {
        ensureLayoutState();
        if (_currentWorkspace == null || _currentWorkspace._kind != WorkspaceKind.SHELL || _currentWorkspace._shellView == null) {
            return false;
        }
        if (_workspaceView == _currentWorkspace._shellView) {
            return true;
        }
        if (_workspaceView != null) {
            _workspaceView.removeFromParent();
        }
        _workspaceView = _currentWorkspace._shellView;
        _activeView = _currentWorkspace._shellView;
        _currentWorkspace._workspaceView = _workspaceView;
        _currentWorkspace._activeView = _activeView;
        if (_workspaceView.getParent() == null) {
            attachWorkspaceView();
        }
        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setFirstResponder(_activeView);
            _rootView.setNeedsRedraw();
        }
        refreshChromeState();
        return true;
    }

    public boolean showShellPanel() {
        ensureLayoutState();
        if (_panelView instanceof ShellPanelView) {
            return true;
        }
        if (_panelView != null) {
            return false;
        }
        ShellPanelView shellView = _savedPanelShell;
        if (shellView != null && !shellViewIsAlive(shellView)) {
            _savedPanelShell = null;
            shellView = null;
        }
        if (shellView == null) {
            try {
                shellView = ShellPanelView.createDefault(this, Rect.create(0, 0, 0, 0));
                ShellPanelView callbackShellView = shellView;
                shellView.setOnExit(() -> closeShellView(callbackShellView));
                _savedPanelShell = shellView;
            } catch (IOException e) {
                if (_commandView != null) {
                    _commandView.setMessage("Failed to start shell: " + e.getMessage());
                }
                return false;
            }
        }
        return showPanel(shellView);
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

    public boolean resizeActiveViewWidth(int deltaColumns) {
        ensureLayoutState();
        return _workspaceView instanceof SplitView splitRoot
                && splitRoot.resizeLeaf(getActiveView(), SplitView.Orientation.HORIZONTAL, deltaColumns);
    }

    public boolean resizeActiveViewHeight(int deltaRows) {
        ensureLayoutState();
        return _workspaceView instanceof SplitView splitRoot
                && splitRoot.resizeLeaf(getActiveView(), SplitView.Orientation.VERTICAL, deltaRows);
    }

    public boolean equalizeSplits() {
        ensureLayoutState();
        return _workspaceView instanceof SplitView splitRoot && splitRoot.equalize();
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
        attachWorkspaceView();
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
        if (view != _activeView) {
            hideTransientDiagnostics();
        }
        View previousActiveView = _activeView;
        if (previousActiveView instanceof ShellPanelView previousShell && previousActiveView != view) {
            previousShell.sendFocusChanged(false);
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
        if (view instanceof ShellPanelView shellPanelView) {
            shellPanelView.sendFocusChanged(true);
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

    public EditorState getEditorState() {
        ensureLayoutState();
        return _editorState;
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

    public String modeNameForDisplay() {
        ensureLayoutState();
        if (_activeView instanceof ShellPanelView shellPanelView) {
            return shellPanelView.modeName();
        }
        return _currentMode == null ? "NORMAL" : _currentMode.getName();
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

    public boolean isShowingSlackWorkspace() {
        return _currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.SLACK;
    }

    public boolean isShowingTodoWorkspace() {
        return _currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.TODO;
    }

    public boolean isShowingTodoQuickCapture() {
        return _todoQuickCaptureView != null && _todoQuickCaptureView.getParent() != null;
    }

    public void hideTodoQuickCapture() {
        hideTodoQuickCapture(false);
    }

    private void hideTodoQuickCapture(boolean submitted) {
        TodoQuickCaptureView view = _todoQuickCaptureView;
        if (view != null) {
            view.removeFromParent();
            _todoQuickCaptureView = null;
        }
        if (_rootView != null) {
            if (_rootView.getFirstResponder() == view) {
                _rootView.setFirstResponder(todoQuickCaptureRestoreTarget());
            }
            _rootView.setNeedsRedraw();
        }
        if (!submitted && _commandView != null) {
            _commandView.setMessage(null);
        }
        _todoQuickCaptureReturnResponder = null;
        refreshChromeState();
    }

    boolean usesFrameModeLines() {
        ensureLayoutState();
        return _workspaceView instanceof SplitView;
    }

    public boolean closeCurrentWorkspaceWindow() {
        if (_currentWorkspace == null || _currentWorkspace._kind == WorkspaceKind.BUFFER) {
            return false;
        }
        var closing = _currentWorkspace;
        _workspaceHistory.remove(closing);
        closeWorkspaceViews(closing);
        if (_workspaceHistory.isEmpty()) {
            _currentWorkspace = null;
            return openBufferWorkspace(null);
        }
        _currentWorkspace = null;
        return activateWorkspace(_workspaceHistory.getFirst());
    }

    public boolean hideCurrentWorkspaceWindow() {
        if (_currentWorkspace == null || _currentWorkspace._kind == WorkspaceKind.BUFFER) {
            return false;
        }
        if (_workspaceHistory.size() <= 1) {
            return openBufferWorkspace(null);
        }
        return activateWorkspace(_workspaceHistory.get(1));
    }

    public boolean closeShellView(ShellPanelView shellView) {
        if (shellView == null) {
            return false;
        }
        if (_panelView == shellView) {
            closePanelShellSession();
            return true;
        }
        if (_savedPanelShell == shellView) {
            _savedPanelShell = null;
            shellView.removeFromParent();
            return true;
        }
        if (_workspaceView instanceof SplitView splitRoot && splitRoot.containsLeaf(shellView)) {
            return closeView(shellView);
        }
        WorkspaceState target = null;
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.SHELL
                    && (workspace._workspaceView == shellView || workspace._shellView == shellView)) {
                target = workspace;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        if (target == _currentWorkspace) {
            return closeCurrentWorkspaceWindow();
        }
        _workspaceHistory.remove(target);
        closeWorkspaceViews(target);
        return true;
    }

    public void closePanelShellSession() {
        if (!(_panelView instanceof ShellPanelView shellView)) {
            return;
        }
        _savedPanelShell = null;
        if (shellView.getParent() == _rootView) {
            _panelView = null;
            shellView.closeForPanel();
            shellView.removeFromParent();
            if (_activeBufferView != null) {
                activateView(_activeBufferView);
            } else if (_workspaceView != null) {
                activateView(findFocusableView(_workspaceView));
            }
            return;
        }
        closeView(shellView);
    }

    public void returnToEditor() {
        ensureLayoutState();
        if (_panelView != null && _activeBufferView != null) {
            activateView(_activeBufferView);
            return;
        }
        for (var workspace : _workspaceHistory) {
            if (workspace._kind == WorkspaceKind.BUFFER) {
                activateWorkspace(workspace);
                return;
            }
        }
        openBufferWorkspace(null);
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

    public Character consumeSelectedRegister() {
        return getEditorState().consumeSelectedRegister();
    }

    public void selectRegister(char register) {
        getEditorState().selectRegister(register);
        if (_commandView != null) {
            _commandView.setMessage("Using register " + Character.toLowerCase(register));
        }
    }

    public boolean startMacroRecording(char register) {
        boolean started = getEditorState().startMacroRecording(register);
        if (_commandView != null) {
            _commandView.setMessage(started
                    ? "Recording macro @" + Character.toLowerCase(register)
                    : "Unable to start macro recording");
        }
        return started;
    }

    public boolean stopMacroRecording() {
        boolean stopped = getEditorState().stopMacroRecording();
        if (_commandView != null && stopped) {
            _commandView.setMessage("Stopped macro recording");
        }
        return stopped;
    }

    public boolean isRecordingMacro() {
        return getEditorState().isRecordingMacro();
    }

    public boolean playMacro(char register, int count) {
        boolean played = getEditorState().playMacro(register, count);
        if (_commandView != null && !played) {
            _commandView.setMessage("Macro register is empty: " + Character.toLowerCase(register));
        }
        return played;
    }

    public boolean playLastMacro(int count) {
        boolean played = getEditorState().playLastMacro(count);
        if (_commandView != null && !played) {
            _commandView.setMessage("No macro available");
        }
        return played;
    }

    public void beginRepeatRecording(List<org.fisk.swim.event.RecordedKey> initialKeys) {
        getEditorState().beginRepeatRecording(initialKeys);
    }

    public void beginRepeatRecording(String notation) {
        beginRepeatRecording(org.fisk.swim.event.RecordedKey.parseSequence(notation));
    }

    public void skipObservedRepeatKeys(int count) {
        getEditorState().skipObservedRepeatKeys(count);
    }

    public void appendRepeatKey(org.fisk.swim.event.RecordedKey key) {
        getEditorState().appendRepeatKey(key);
    }

    public void commitRepeatRecording() {
        getEditorState().commitRepeatRecording();
    }

    public void cancelRepeatRecording() {
        getEditorState().cancelRepeatRecording();
    }

    public boolean repeatLastEdit(int count) {
        boolean repeated = getEditorState().repeatLastEdit(count);
        if (_commandView != null && !repeated) {
            _commandView.setMessage("Nothing to repeat");
        }
        return repeated;
    }

    public void setMark(char mark) {
        var context = getBufferContext();
        if (context == null) {
            return;
        }
        getEditorState().setMark(mark, context.getBuffer().getPath(), context.getBuffer().getCursor().getPosition());
        if (_commandView != null) {
            _commandView.setMessage("Set mark " + Character.toLowerCase(mark));
        }
    }

    public boolean jumpToMark(char mark, boolean lineWise) {
        EditorLocation location = getEditorState().getMark(mark);
        if (location == null) {
            if (_commandView != null) {
                _commandView.setMessage("No such mark: " + Character.toLowerCase(mark));
            }
            return false;
        }
        return performJumpTo(location, lineWise);
    }

    public boolean jumpBack() {
        EditorLocation location = getEditorState().jumpBack();
        if (location == null) {
            if (_commandView != null) {
                _commandView.setMessage("Jump list is at the oldest entry");
            }
            return false;
        }
        return jumpToExistingLocation(location, false);
    }

    public boolean jumpForward() {
        EditorLocation location = getEditorState().jumpForward();
        if (location == null) {
            if (_commandView != null) {
                _commandView.setMessage("Jump list is at the newest entry");
            }
            return false;
        }
        return jumpToExistingLocation(location, false);
    }

    public String registersSummary() {
        var lines = new ArrayList<String>();
        for (var entry : org.fisk.swim.copy.Copy.getInstance().registerSnapshot().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            lines.add("\"" + entry.getKey() + " " + (entry.getValue().isLine() ? "line " : "char ")
                    + entry.getValue().text().replace("\n", "\\n"));
        }
        for (var entry : org.fisk.swim.copy.Copy.getInstance().macroSnapshot().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String macro = entry.getValue().stream()
                    .map(org.fisk.swim.event.RecordedKey::notation)
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
            lines.add("@" + entry.getKey() + " " + macro);
        }
        return lines.isEmpty() ? "No registers are set" : String.join("\n", lines);
    }

    public String marksSummary() {
        var lines = new ArrayList<String>();
        for (var entry : getEditorState().markSnapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            lines.add(entry.getKey() + " " + entry.getValue().display());
        }
        return lines.isEmpty() ? "No marks are set" : String.join("\n", lines);
    }

    public String jumpsSummary() {
        var jumps = getEditorState().jumpSnapshot();
        if (jumps.isEmpty()) {
            return "Jump list is empty";
        }
        var lines = new ArrayList<String>();
        int current = getEditorState().jumpIndex();
        for (int i = 0; i < jumps.size(); i++) {
            lines.add((i == current ? ">" : " ") + " " + (i + 1) + " " + jumps.get(i).display());
        }
        return String.join("\n", lines);
    }

    public List<OpenBufferEntry> openBuffers() {
        ensureLayoutState();
        var ordered = new LinkedHashMap<String, OpenBufferEntry>();
        addBufferEntry(ordered, _bufferContext);
        if (_workspaceHistory != null) {
            for (var workspace : _workspaceHistory) {
                addBufferEntry(ordered, workspace == null ? null : workspace._bufferContext);
            }
        }
        if (_bufferContextsByView != null) {
            for (var context : _bufferContextsByView.values()) {
                addBufferEntry(ordered, context);
            }
        }
        return List.copyOf(ordered.values());
    }

    public void showBufferList() {
        var items = new ArrayList<ListView.ListItem>();
        for (OpenBufferEntry entry : openBuffers()) {
            items.add(new ListView.ListItem() {
                @Override
                public void onClick() {
                    if (entry.path() != null) {
                        setBufferPath(entry.path());
                    }
                }

                @Override
                public String displayString() {
                    return entry.label();
                }
            });
        }
        showList(items, "Buffers");
    }

    public boolean switchBufferByIndex(int oneBasedIndex) {
        var buffers = openBuffers();
        if (oneBasedIndex <= 0 || oneBasedIndex > buffers.size()) {
            return false;
        }
        var target = buffers.get(oneBasedIndex - 1);
        return target.path() != null && setBufferPath(target.path());
    }

    public boolean switchBufferByToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            return switchBufferByIndex(Integer.parseInt(token));
        } catch (NumberFormatException e) {
        }
        String normalized = token.trim();
        for (OpenBufferEntry entry : openBuffers()) {
            if (entry.path() == null) {
                continue;
            }
            if (entry.label().equals(normalized)
                    || entry.label().endsWith(normalized)
                    || entry.path().toString().endsWith(normalized)) {
                return setBufferPath(entry.path());
            }
        }
        return false;
    }

    public boolean switchNextBuffer() {
        return switchRelativeBuffer(1);
    }

    public boolean switchPreviousBuffer() {
        return switchRelativeBuffer(-1);
    }

    public void setQuickfixResults(String title, List<ProjectSearch.Match> matches) {
        _quickfixList = new SearchLocationList(title == null || title.isBlank() ? "Quickfix" : title, matches, 0);
    }

    public void setLocationResults(String title, List<ProjectSearch.Match> matches) {
        _locationList = new SearchLocationList(title == null || title.isBlank() ? "Location" : title, matches, 0);
    }

    public String quickfixSummary() {
        return locationSummary(_quickfixList);
    }

    public String locationSummary() {
        return locationSummary(_locationList);
    }

    public boolean openQuickfixList() {
        return openLocationList(_quickfixList);
    }

    public boolean openLocationList() {
        return openLocationList(_locationList);
    }

    public boolean closeLocationLists() {
        if (_panelView instanceof ListView || _panelView instanceof TextPanelView) {
            hidePanel();
            return true;
        }
        return false;
    }

    public boolean nextQuickfix() {
        return advanceLocation(true, true);
    }

    public boolean previousQuickfix() {
        return advanceLocation(true, false);
    }

    public boolean nextLocation() {
        return advanceLocation(false, true);
    }

    public boolean previousLocation() {
        return advanceLocation(false, false);
    }

    public boolean addNextCursorForCurrentWord(boolean forward) {
        if (_bufferContext == null) {
            return false;
        }
        String word = _bufferContext.getBuffer().getInnerWord();
        if (word == null || word.isBlank()) {
            return false;
        }
        boolean added = _bufferContext.getBuffer().addNextCursorForLiteral(word, forward);
        if (added) {
            _bufferContext.getBufferView().setNeedsRedraw();
            if (_commandView != null) {
                _commandView.setMessage("Added cursor for " + word);
            }
        }
        return added;
    }

    public boolean createCursorsForLiteral(String text) {
        if (_bufferContext == null || text == null || text.isBlank()) {
            return false;
        }
        var buffer = _bufferContext.getBuffer();
        var matches = buffer.findLiteralMatches(text);
        if (matches.isEmpty()) {
            return false;
        }
        buffer.clearCursors();
        for (int i = 0; i < matches.size(); i++) {
            if (i == 0) {
                buffer.getCursor().setPosition(matches.get(i));
            } else {
                var cursor = new Cursor(_bufferContext);
                cursor.setPosition(matches.get(i));
                buffer.addCursor(cursor);
            }
        }
        _bufferContext.getBufferView().setNeedsRedraw();
        return true;
    }

    public void clearAdditionalCursors() {
        if (_bufferContext != null) {
            _bufferContext.getBuffer().clearCursors();
            _bufferContext.getBufferView().setNeedsRedraw();
        }
    }

    public void rememberCurrentLocationForJump() {
        var context = getBufferContext();
        if (context == null) {
            return;
        }
        getEditorState().recordJump(new EditorLocation(
                context.getBuffer().getPath(),
                context.getBuffer().getCursor().getPosition()));
    }

    public void rememberNewLocationForJump() {
        rememberCurrentLocationForJump();
    }

    public boolean performJump(Runnable runnable) {
        rememberCurrentLocationForJump();
        runnable.run();
        rememberNewLocationForJump();
        return true;
    }

    private boolean performJumpTo(EditorLocation location, boolean lineWise) {
        rememberCurrentLocationForJump();
        if (!jumpToExistingLocation(location, lineWise)) {
            return false;
        }
        rememberNewLocationForJump();
        return true;
    }

    private boolean jumpToExistingLocation(EditorLocation location, boolean lineWise) {
        if (location == null) {
            return false;
        }
        Path currentPath = getBufferContext() == null ? null : getBufferContext().getBuffer().getPath();
        if (!java.util.Objects.equals(currentPath, location.path())) {
            if (location.path() == null || !setBufferPath(location.path())) {
                return false;
            }
        }
        var context = getBufferContext();
        if (context == null) {
            return false;
        }
        context.getBuffer().getCursor().setPosition(location.position());
        if (lineWise) {
            context.getBuffer().getCursor().goStartOfLine();
        }
        activateView(context.getBufferView());
        return true;
    }

    private boolean switchRelativeBuffer(int delta) {
        var buffers = openBuffers();
        if (buffers.size() <= 1 || _bufferContext == null) {
            return false;
        }
        Path currentPath = _bufferContext.getBuffer().getPath();
        int currentIndex = -1;
        for (int i = 0; i < buffers.size(); i++) {
            if (java.util.Objects.equals(currentPath, buffers.get(i).path())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        for (int step = 1; step <= buffers.size(); step++) {
            var candidate = buffers.get(Math.floorMod(currentIndex + (delta * step), buffers.size()));
            if (candidate.path() != null) {
                return setBufferPath(candidate.path());
            }
        }
        return false;
    }

    private void addBufferEntry(Map<String, OpenBufferEntry> ordered, BufferContext context) {
        if (context == null || context.getBuffer() == null || context.getBuffer().getPath() == null) {
            return;
        }
        Path path = context.getBuffer().getPath();
        String key = path.toAbsolutePath().normalize().toString();
        ordered.putIfAbsent(key, new OpenBufferEntry(path, bufferLabel(path)));
    }

    private static String bufferLabel(Path path) {
        if (path == null) {
            return "*scratch*";
        }
        Path root = org.fisk.swim.fileindex.ProjectPaths.getProjectRootPath(path);
        if (root != null) {
            return root.relativize(path).toString();
        }
        return path.toString();
    }

    private boolean openLocationList(SearchLocationList list) {
        if (list == null || list.matches().isEmpty()) {
            return false;
        }
        var items = new ArrayList<ListView.ListItem>();
        for (int i = 0; i < list.matches().size(); i++) {
            int index = i;
            ProjectSearch.Match match = list.matches().get(i);
            items.add(new ListView.ListItem() {
                @Override
                public void onClick() {
                    openBufferLocation(match.path(), match.lineNumber(), match.columnNumber());
                    if (list == _quickfixList) {
                        _quickfixList = new SearchLocationList(list.title(), list.matches(), index);
                    } else if (list == _locationList) {
                        _locationList = new SearchLocationList(list.title(), list.matches(), index);
                    }
                }

                @Override
                public String displayString() {
                    return match.displayString();
                }
            });
        }
        showList(items, list.title());
        return true;
    }

    private boolean advanceLocation(boolean quickfix, boolean forward) {
        SearchLocationList list = quickfix ? _quickfixList : _locationList;
        if (list == null || list.matches().isEmpty()) {
            return false;
        }
        int delta = forward ? 1 : -1;
        int nextIndex = Math.max(0, Math.min(list.selection() + delta, list.matches().size() - 1));
        if (nextIndex == list.selection()) {
            return false;
        }
        ProjectSearch.Match match = list.matches().get(nextIndex);
        boolean opened = openBufferLocation(match.path(), match.lineNumber(), match.columnNumber());
        if (!opened) {
            return false;
        }
        SearchLocationList updated = new SearchLocationList(list.title(), list.matches(), nextIndex);
        if (quickfix) {
            _quickfixList = updated;
        } else {
            _locationList = updated;
        }
        return true;
    }

    private static String locationSummary(SearchLocationList list) {
        if (list == null || list.matches().isEmpty()) {
            return "Location list is empty";
        }
        var lines = new ArrayList<String>();
        for (int i = 0; i < list.matches().size(); i++) {
            ProjectSearch.Match match = list.matches().get(i);
            lines.add((i == list.selection() ? ">" : " ") + " " + match.displayString());
        }
        return String.join("\n", lines);
    }

    private static List<CompiledRemap> compileRemaps(List<NormalModeRemap> remaps) {
        var compiled = new ArrayList<CompiledRemap>();
        if (remaps == null) {
            return compiled;
        }
        for (var remap : remaps) {
            try {
                var lhs = RecordedKey.parseSequence(remap.lhs());
                var rhs = RecordedKey.parseSequence(remap.rhs());
                if (!lhs.isEmpty() && !rhs.isEmpty()) {
                    compiled.add(new CompiledRemap(List.copyOf(lhs), List.copyOf(rhs), remap.lhs()));
                }
            } catch (IllegalArgumentException e) {
            }
        }
        return List.copyOf(compiled);
    }

    private static void applyConfiguredOptions(EditorConfig config) {
        if (config == null || config.options().isEmpty()) {
            return;
        }
        for (var entry : config.options().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (key.startsWith("indent.")) {
                String property = "swim." + key;
                System.setProperty(property, value);
                continue;
            }
            System.setProperty(key, value);
        }
    }

    private void runStartupCommands() {
        if (_editorConfig == null || _editorConfig.startupCommands().isEmpty() || _commandView == null) {
            return;
        }
        for (String command : _editorConfig.startupCommands()) {
            if (command == null || command.isBlank()) {
                continue;
            }
            _commandView.execute(command);
        }
    }

    private boolean restoreLastSession() {
        EditorSession session = EditorConfigStore.loadSession(_editorPaths);
        if (!session.workspaces().isEmpty()) {
            restoreSessionWorkspaces(session);
            return !_workspaceHistory.isEmpty();
        }
        if (session.openBuffers().isEmpty()) {
            return false;
        }
        String active = session.activeBuffer();
        if (active != null && !active.isBlank()) {
            setBufferPath(Path.of(active));
        } else {
            setBufferPath(Path.of(session.openBuffers().getFirst()));
        }
        for (String buffer : session.openBuffers()) {
            if (buffer == null || buffer.isBlank()) {
                continue;
            }
            Path path = Path.of(buffer);
            if (_bufferContext != null && java.util.Objects.equals(_bufferContext.getBuffer().getPath(), path)) {
                continue;
            }
            openBufferWorkspace(path);
        }
        if (active != null && !active.isBlank()) {
            setBufferPath(Path.of(active));
        }
        return !session.openBuffers().isEmpty();
    }

    private EditorSession createSession() {
        if (_currentWorkspace != null) {
            captureCurrentWorkspace();
        }
        var buffers = openBuffers().stream()
                .map(OpenBufferEntry::path)
                .filter(path -> path != null)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
        String active = _bufferContext == null || _bufferContext.getBuffer().getPath() == null
                ? null
                : _bufferContext.getBuffer().getPath().toAbsolutePath().normalize().toString();
        var workspaces = new ArrayList<SessionWorkspace>();
        for (var workspace : _workspaceHistory) {
            SessionWorkspace saved = snapshotWorkspace(workspace);
            if (saved != null) {
                workspaces.add(saved);
            }
        }
        int activeWorkspaceIndex = _currentWorkspace == null ? 0 : Math.max(0, _workspaceHistory.indexOf(_currentWorkspace));
        return new EditorSession(buffers, active, workspaces, activeWorkspaceIndex);
    }

    private SessionWorkspace snapshotWorkspace(WorkspaceState workspace) {
        if (workspace == null || workspace._workspaceView == null) {
            return null;
        }
        return switch (workspace._kind) {
        case BUFFER -> {
            String activePath = workspace._bufferContext == null || workspace._bufferContext.getBuffer().getPath() == null
                    ? null
                    : workspace._bufferContext.getBuffer().getPath().toAbsolutePath().normalize().toString();
            SessionLayoutNode layout = snapshotLayout(workspace._workspaceView);
            yield new SessionWorkspace("BUFFER", activePath, activePath, layout);
        }
        case DIRECTORY -> {
            String path = workspace._workspaceView instanceof DirectoryBrowserView browser
                    ? browser.getDirectory().toAbsolutePath().normalize().toString()
                    : null;
            yield path == null ? null : new SessionWorkspace("DIRECTORY", path, path, null);
        }
        default -> null;
        };
    }

    private SessionLayoutNode snapshotLayout(View view) {
        if (view instanceof SplitView splitView) {
            return toSessionLayout(splitView.snapshot(leaf -> {
                if (leaf instanceof BufferView bufferView) {
                    Path path = getBufferContextFor(bufferView).getBuffer().getPath();
                    return path == null ? null : path.toAbsolutePath().normalize().toString();
                }
                return null;
            }));
        }
        if (view instanceof BufferView bufferView) {
            Path path = getBufferContextFor(bufferView).getBuffer().getPath();
            return path == null ? null : new SessionLayoutNode(null, 0.0, null, null, path.toAbsolutePath().normalize().toString());
        }
        return null;
    }

    private static SessionLayoutNode toSessionLayout(SplitView.SessionNode node) {
        if (node == null) {
            return null;
        }
        return new SessionLayoutNode(
                node.orientation(),
                node.ratio(),
                toSessionLayout(node.first()),
                toSessionLayout(node.second()),
                node.leafId());
    }

    private void restoreSessionWorkspaces(EditorSession session) {
        _workspaceHistory.clear();
        for (SessionWorkspace workspace : session.workspaces()) {
            WorkspaceState restored = restoreWorkspace(workspace);
            if (restored != null) {
                _workspaceHistory.add(restored);
            }
        }
        if (_workspaceHistory.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(session.activeWorkspaceIndex(), _workspaceHistory.size() - 1));
        activateWorkspace(_workspaceHistory.get(index));
    }

    private WorkspaceState restoreWorkspace(SessionWorkspace workspace) {
        if (workspace == null || workspace.kind() == null) {
            return null;
        }
        return switch (workspace.kind()) {
        case "BUFFER" -> restoreBufferWorkspace(workspace);
        case "DIRECTORY" -> workspace.path() == null ? null
                : createViewWorkspace(new DirectoryBrowserView(Rect.create(0, 0, 0, 0), Path.of(workspace.path())),
                        WorkspaceKind.DIRECTORY);
        default -> null;
        };
    }

    private WorkspaceState restoreBufferWorkspace(SessionWorkspace workspace) {
        if (workspace.layout() == null) {
            return workspace.path() == null ? null : createBufferWorkspace(Path.of(workspace.path()));
        }
        BufferLayoutResult result = restoreBufferLayout(workspace.layout(), workspace.activePath());
        if (result == null) {
            return workspace.path() == null ? null : createBufferWorkspace(Path.of(workspace.path()));
        }
        var restored = new WorkspaceState();
        restored._kind = WorkspaceKind.BUFFER;
        restored._workspaceView = result.root();
        restored._activeView = result.activeView() == null ? result.root() : result.activeView();
        restored._activeBufferView = result.activeView();
        restored._bufferContext = result.activeContext();
        restored._bufferContextsByView = result.contextsByView();
        restored._bufferViewCounts = result.counts();
        initializeBufferWorkspaceModes(restored);
        return restored;
    }

    private BufferLayoutResult restoreBufferLayout(SessionLayoutNode node, String activePath) {
        if (node == null) {
            return null;
        }
        if (node.path() != null) {
            BufferContext context = new BufferContext(Rect.create(0, 0, 0, 0), Path.of(node.path()));
            var contexts = new IdentityHashMap<BufferView, BufferContext>();
            contexts.put(context.getBufferView(), context);
            var counts = new IdentityHashMap<BufferContext, Integer>();
            counts.put(context, 1);
            boolean active = activePath != null && activePath.equals(node.path());
            return new BufferLayoutResult(context.getBufferView(), contexts, counts,
                    active ? context.getBufferView() : null,
                    active ? context : null);
        }
        BufferLayoutResult first = restoreBufferLayout(node.first(), activePath);
        BufferLayoutResult second = restoreBufferLayout(node.second(), activePath);
        if (first == null || second == null) {
            return first != null ? first : second;
        }
        SplitView split = new SplitView(Rect.create(0, 0, 0, 0),
                SplitView.Orientation.valueOf(node.orientation()),
                first.root(),
                second.root(),
                node.ratio());
        var contexts = new IdentityHashMap<BufferView, BufferContext>();
        contexts.putAll(first.contextsByView());
        contexts.putAll(second.contextsByView());
        var counts = new IdentityHashMap<BufferContext, Integer>();
        counts.putAll(first.counts());
        counts.putAll(second.counts());
        return new BufferLayoutResult(split, contexts, counts,
                first.activeView() != null ? first.activeView() : second.activeView(),
                first.activeContext() != null ? first.activeContext() : second.activeContext());
    }

    public ModeLineView getModeLineView() {
        return _modeLineView;
    }

    public void dispose() {
        ensureLayoutState();
        if (_editorConfig != null && Boolean.getBoolean("swim.session.restore_on_reload")) {
            saveSessionForReload();
        }
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
        TodoUiSupport.shutdownInstance();
        _instance = null;
    }

    public void saveSessionForReload() {
        ensureLayoutState();
        if (_editorPaths != null) {
            EditorConfigStore.saveSession(_editorPaths, createSession());
        }
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
            _keyMenuView.setModeName(modeNameForDisplay());
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
            } else if (responder instanceof ShellPanelView shellPanelView && shellPanelView.isCommandInputActive()) {
                _commandMenuView.setState(shellPanelView.getCommandMenuState());
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

    private void attachWorkspaceView() {
        if (_rootView == null || _workspaceView == null) {
            return;
        }
        if (_workspaceView.getParent() == _rootView) {
            _workspaceView.removeFromParent();
        }
        int index = (_keyMenuView != null && _keyMenuView.getParent() == _rootView) ? 1 : 0;
        _rootView.insertSubview(index, _workspaceView);
    }

    private void attachOverlayPanel(View panelView) {
        if (_rootView == null || panelView == null) {
            return;
        }
        if (panelView.getParent() == _rootView) {
            panelView.removeFromParent();
        }
        int index = rootSubviews().size();
        if (_modeLineView != null && _modeLineView.getParent() == _rootView) {
            index = rootSubviews().indexOf(_modeLineView);
        }
        _rootView.insertSubview(index, panelView);
    }

    private boolean isOverlayPanel(View panelView) {
        return panelView instanceof ChatPanelView || panelView instanceof ShellPanelView;
    }

    @SuppressWarnings("unchecked")
    private List<View> rootSubviews() {
        try {
            var field = View.class.getDeclaredField("_subviews");
            field.setAccessible(true);
            return (List<View>) field.get(_rootView);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private EventResponder todoQuickCaptureRestoreTarget() {
        if (isRestorableRootResponder(_todoQuickCaptureReturnResponder)) {
            return _todoQuickCaptureReturnResponder;
        }
        if (isRestorableRootResponder(_activeView)) {
            return _activeView;
        }
        if (isRestorableRootResponder(_activeBufferView)) {
            return _activeBufferView;
        }
        View focusable = findFocusableView(_workspaceView);
        return isRestorableRootResponder(focusable) ? focusable : null;
    }

    private boolean isRestorableRootResponder(EventResponder responder) {
        if (responder == null || responder == _todoQuickCaptureView) {
            return false;
        }
        if (responder instanceof View view) {
            return view == _rootView || view.getParent() != null;
        }
        return true;
    }

    private void refreshTodoWorkspaceViews() {
        var refreshed = new HashSet<TodoWorkspaceView>();
        refreshTodoWorkspaceView(_workspaceView, refreshed);
        for (var workspace : _workspaceHistory) {
            if (workspace != null) {
                refreshTodoWorkspaceView(workspace._workspaceView, refreshed);
            }
        }
    }

    private void refreshTodoWorkspaceView(View view, HashSet<TodoWorkspaceView> refreshed) {
        if (view instanceof TodoWorkspaceView todoWorkspaceView && refreshed.add(todoWorkspaceView)) {
            todoWorkspaceView.refreshFromStore();
            return;
        }
        if (view instanceof SplitView splitView) {
            for (View leaf : splitView.leafViews()) {
                refreshTodoWorkspaceView(leaf, refreshed);
            }
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
        if (responder instanceof DiagnosticPopupView) {
            return KeyMenuView.FocusContext.LIST_PANEL;
        }
        if (responder instanceof CodeActionPopupView) {
            return KeyMenuView.FocusContext.LIST_PANEL;
        }
        if (responder instanceof TodoQuickCaptureView) {
            return KeyMenuView.FocusContext.PANEL;
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
        if (responder instanceof ShellPanelView) {
            return KeyMenuView.FocusContext.SHELL;
        }
        if (responder instanceof TodoWorkspaceView) {
            return KeyMenuView.FocusContext.PANEL;
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
        if (responder instanceof DiagnosticPopupView popupView) {
            return popupView.getTitle();
        }
        if (responder instanceof CodeActionPopupView popupView) {
            return popupView.getTitle();
        }
        if (responder instanceof TodoQuickCaptureView captureView) {
            return captureView.getTitle();
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
        if (responder instanceof TodoWorkspaceView todoWorkspaceView) {
            return todoWorkspaceView.getTitle();
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
        if (_size == null || !_size.equals(size) || keyMenuNeedsRelayout(size)) {
            _log.debug("Relayout");
            applyLayout(size);
        } else {
            _log.debug("Relayout not needed due to same size");
        }
        AttributedString.clearRenderedClickRanges();
        _rootView.update(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows()), forced);
        _size = size;
        if (_activeView instanceof BufferView bufferView) {
            bufferView.adaptViewToCursor();
        }
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
        if (isOverlayPanel(panelView)) {
            _panelView = panelView;
            attachOverlayPanel(panelView);
            applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
            activateView(panelView);
            return true;
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
        if (_panelView instanceof ChatPanelView chatPanelView) {
            _panelView = null;
            chatPanelView.removeFromParent();
            if (_activeBufferView != null) {
                activateView(_activeBufferView);
            } else if (_workspaceView != null) {
                activateView(findFocusableView(_workspaceView));
            }
            return;
        }
        if (_panelView instanceof ShellPanelView shellView && shellView.getParent() == _rootView) {
            _panelView = null;
            if (_savedPanelShell == shellView) {
                shellView.detachFromParentPreservingSession();
            } else {
                shellView.closeForPanel();
                shellView.removeFromParent();
            }
            if (_activeBufferView != null) {
                activateView(_activeBufferView);
            } else if (_workspaceView != null) {
                activateView(findFocusableView(_workspaceView));
            }
            return;
        }
        if (_panelView instanceof ShellPanelView shellView && _savedPanelShell == shellView) {
            hideReusableShellPanel(shellView);
            return;
        }
        closeView(_panelView);
    }

    public void focusActiveBuffer() {
        if (_activeBufferView != null) {
            activateView(_activeBufferView);
        }
    }

    public boolean showDiagnosticsForCurrentLine(boolean takeFocus) {
        var context = getBufferContext();
        if (context == null || _rootView == null) {
            return false;
        }
        int logicalLine = context.getBuffer().getCursor().getLogicalLine().getY();
        var diagnostics = DiagnosticService.getInstance().diagnosticsForLine(context, logicalLine);
        if (diagnostics.isEmpty()) {
            hideDiagnosticPopup();
            if (takeFocus && _commandView != null) {
                _commandView.setMessage("No diagnostics on current line");
            }
            return false;
        }
        showDiagnosticPopup(diagnostics, currentCursorAnchor(), takeFocus, false);
        return true;
    }

    public void updateHoveredDiagnostics(BufferContext context, int logicalLine, Point anchor) {
        if (context == null || _rootView == null || context != getBufferContext()) {
            hideHoverDiagnostics();
            return;
        }
        var diagnostics = DiagnosticService.getInstance().diagnosticsForLine(context, logicalLine);
        if (diagnostics.isEmpty()) {
            hideHoverDiagnostics();
            return;
        }
        showDiagnosticPopup(diagnostics, anchor, false, true);
    }

    public void hideHoverDiagnostics() {
        if (_hoverDiagnosticsVisible) {
            hideDiagnosticPopup();
        }
    }

    public boolean showCodeActionsForCurrentLine() {
        var context = getBufferContext();
        if (context == null || _rootView == null) {
            return false;
        }
        int logicalLine = context.getBuffer().getCursor().getLogicalLine().getY();
        return showCodeActionsForLine(context, logicalLine, currentCursorAnchor());
    }

    private boolean showCodeActionsForDiagnosticPopup() {
        var selected = _diagnosticPopupView == null ? null : _diagnosticPopupView.getSelectedEntry();
        if (selected == null) {
            return showCodeActionsForCurrentLine();
        }
        var context = getBufferContext();
        if (context == null || _rootView == null) {
            return false;
        }
        return showCodeActionsForLine(context, selected.startLine(), currentCursorAnchor());
    }

    private boolean showCodeActionsForLine(BufferContext context, int logicalLine, Point anchor) {
        var lineDiagnostics = DiagnosticService.getInstance().diagnosticsForLine(context, logicalLine);
        if (!(context.getBuffer().getLanguageMode() instanceof DiagnosticActionProvider provider)) {
            if (_commandView != null) {
                _commandView.setMessage("No code actions for this buffer");
            }
            return false;
        }
        var actions = provider.diagnosticActions(context, logicalLine, lineDiagnostics);
        if (actions.isEmpty()) {
            if (_commandView != null) {
                _commandView.setMessage("No code actions on current line");
            }
            hideCodeActionPopup();
            return false;
        }
        hideDiagnosticPopup();
        if (_codeActionPopupView == null || _codeActionPopupView.getParent() == null) {
            _codeActionPopupView = new CodeActionPopupView(Rect.create(0, 0, 0, 0));
            _codeActionPopupView.setOnClose(this::hideCodeActionPopup);
            _rootView.addSubview(_codeActionPopupView);
        }
        _codeActionPopupView.configure(actions, anchor, "Code Actions");
        _rootView.setFirstResponder(_codeActionPopupView);
        refreshChromeState();
        _rootView.setNeedsRedraw();
        return true;
    }

    public boolean navigateDiagnostic(boolean forward, boolean errorsOnly) {
        var context = getBufferContext();
        if (context == null) {
            return false;
        }
        Path path = context.getBuffer().getPath();
        if (path == null) {
            return false;
        }
        var cursor = context.getBuffer().getCursor();
        var target = DiagnosticService.getInstance().findNext(
                path,
                cursor.getLogicalLine().getY(),
                cursor.getX(),
                forward,
                errorsOnly);
        if (target == null || target.path() == null) {
            if (_commandView != null) {
                _commandView.setMessage(errorsOnly ? "No project errors" : "No project diagnostics");
            }
            return false;
        }
        return performJump(() -> openBufferLocation(
                target.path(),
                target.startLine() + 1,
                target.startCharacter() + 1));
    }

    public void hideDiagnosticPopup() {
        _hoverDiagnosticsVisible = false;
        if (_diagnosticPopupView != null) {
            _diagnosticPopupView.removeFromParent();
            _diagnosticPopupView = null;
        }
        if (_rootView != null) {
            if (_activeView != null && _rootView.getFirstResponder() instanceof DiagnosticPopupView) {
                _rootView.setFirstResponder(_activeView);
            }
            _rootView.setNeedsRedraw();
        }
        refreshChromeState();
    }

    public void hideCodeActionPopup() {
        if (_codeActionPopupView != null) {
            _codeActionPopupView.removeFromParent();
            _codeActionPopupView = null;
        }
        if (_rootView != null) {
            if (_activeView != null && _rootView.getFirstResponder() instanceof CodeActionPopupView) {
                _rootView.setFirstResponder(_activeView);
            }
            _rootView.setNeedsRedraw();
        }
        refreshChromeState();
    }

    public boolean openBufferLocation(Path path, int lineNumber, int columnNumber) {
        if (!setBufferPath(path)) {
            return false;
        }
        return revealBufferLocation(getBufferContext(), lineNumber, columnNumber);
    }

    private void showDiagnosticPopup(List<DiagnosticEntry> diagnostics, Point anchor, boolean takeFocus, boolean hover) {
        if (_rootView == null) {
            return;
        }
        hideCodeActionPopup();
        if (_diagnosticPopupView == null || _diagnosticPopupView.getParent() == null) {
            _diagnosticPopupView = new DiagnosticPopupView(Rect.create(0, 0, 0, 0));
            _diagnosticPopupView.setOnClose(this::hideDiagnosticPopup);
            _diagnosticPopupView.setOnActions(this::showCodeActionsForDiagnosticPopup);
            _rootView.addSubview(_diagnosticPopupView);
        }
        _hoverDiagnosticsVisible = hover;
        _diagnosticPopupView.configure(diagnostics, anchor, hover ? "Line Diagnostics" : "Diagnostics", takeFocus);
        if (takeFocus) {
            _rootView.setFirstResponder(_diagnosticPopupView);
            refreshChromeState();
        }
        _rootView.setNeedsRedraw();
    }

    private Point currentCursorAnchor() {
        var cursor = getBufferContext() == null ? null : getBufferContext().getBuffer().getCursor();
        if (cursor == null) {
            return Point.create(0, 0);
        }
        return Point.create(cursor.getXOnScreen(), cursor.getYOnScreen());
    }

    private void hideTransientDiagnostics() {
        hideDiagnosticPopup();
        hideCodeActionPopup();
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
        attachWorkspaceView();

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
        eventThread.addKeyStrokeObserver(keyStroke -> {
            if (_editorState != null) {
                _editorState.observeKeyStroke(keyStroke);
            }
        });
        var responders = eventThread.getResponder();
        var prefixLayer = responders.addLayer();
        var quickCaptureLayer = responders.addLayer();
        quickCaptureLayer.addEventResponder("<CTRL>-t", () -> TodoUiSupport.quickCapture(Window.this));
        prefixLayer.addEventResponder(new EventResponder() {
            private CompiledRemap _matched;

            @Override
            public Response processEvent(KeyStrokes events) {
                _matched = null;
                if (_replayingRemap || _normalModeRemaps.isEmpty()
                        || _rootView == null
                        || _rootView.getFirstResponder() != _activeBufferView
                        || _currentMode != _normalMode) {
                    return Response.NO;
                }
                var sequence = new ArrayList<com.googlecode.lanterna.input.KeyStroke>();
                for (var keyStroke : events) {
                    sequence.add(keyStroke);
                }
                if (sequence.isEmpty()) {
                    return Response.NO;
                }
                boolean maybe = false;
                for (var remap : _normalModeRemaps) {
                    if (sequence.size() > remap.lhs().size()) {
                        continue;
                    }
                    boolean matches = true;
                    for (int i = 0; i < sequence.size(); i++) {
                        if (!RecordedKey.fromKeyStroke(sequence.get(i)).equals(remap.lhs().get(i))) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) {
                        continue;
                    }
                    if (sequence.size() == remap.lhs().size()) {
                        _matched = remap;
                        return Response.YES;
                    }
                    maybe = true;
                }
                return maybe ? Response.MAYBE : Response.NO;
            }

            @Override
            public void respond() {
                if (_matched == null) {
                    return;
                }
                _replayingRemap = true;
                var eventThread = EventThread.getInstance();
                for (var key : _matched.rhs()) {
                    eventThread.enqueue(new KeyStrokeEvent(key.toKeyStroke()));
                }
                eventThread.enqueue(new RunnableEvent(() -> _replayingRemap = false));
            }
        });
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
        prefixLayer.addEventResponder(new EventResponder() {
            private Integer _windowIndex;

            @Override
            public Response processEvent(KeyStrokes events) {
                _windowIndex = null;
                if (_rootView == null || _rootView.getFirstResponder() != _activeBufferView || _currentMode != _normalMode) {
                    return Response.NO;
                }
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
                    return Response.MAYBE;
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
        var currentView = getActiveView();
        if (currentView == null) {
            return null;
        }
        var nextBufferContext = createSplitBufferContext();
        var nextBufferView = nextBufferContext.getBufferView();
        registerBufferView(nextBufferContext, nextBufferView);
        if (!splitView(currentView, nextBufferView, orientation, 0.5)) {
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
        if (_workspaceView instanceof SplitView splitRoot && splitRoot.containsLeaf(existingView)) {
            if (!splitRoot.split(existingView, newView, orientation, ratio, existingFirst)) {
                return false;
            }
        } else if (existingView == _workspaceView) {
            var bounds = existingView.getBounds();
            existingView.removeFromParent();
            var splitView = existingFirst
                    ? new SplitView(bounds, orientation, existingView, newView, ratio)
                    : new SplitView(bounds, orientation, newView, existingView, ratio);
            _workspaceView = splitView;
            attachWorkspaceView();
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
        if (!(_workspaceView instanceof SplitView splitRoot) || !splitRoot.containsLeaf(view)) {
            return false;
        }
        var focusFallback = splitRoot.removeLeaf(view);

        if (view == _panelView) {
            _panelView = null;
        }
        if (view instanceof BufferView bufferView) {
            unregisterBufferView(bufferView);
            if (_activeBufferView == bufferView) {
                _activeBufferView = splitRoot.isSingleLeaf() ? null : findFirstBufferView();
                _bufferContext = getBufferContextFor(_activeBufferView);
            }
        }

        if (splitRoot.isSingleLeaf()) {
            var remaining = splitRoot.detachSingleLeaf();
            splitRoot.removeFromParent();
            _workspaceView = remaining;
            attachWorkspaceView();
        }

        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setNeedsRedraw();
        }

        if (_activeView == view || _activeView == null) {
            var focusTarget = focusFallback != null ? focusFallback : findFocusableView(_workspaceView);
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

    private void hideReusableShellPanel(ShellPanelView shellView) {
        if (!(_workspaceView instanceof SplitView splitRoot) || !splitRoot.containsLeaf(shellView)) {
            return;
        }
        shellView.detachFromParentPreservingSession();
        var focusFallback = splitRoot.removeLeaf(shellView);

        _panelView = null;
        if (splitRoot.isSingleLeaf()) {
            var remaining = splitRoot.detachSingleLeaf();
            splitRoot.removeFromParent();
            _workspaceView = remaining;
            attachWorkspaceView();
        }
        applyLayout(_size != null ? _size : _rootView.getBounds().getSize());
        if (_rootView != null) {
            _rootView.setNeedsRedraw();
        }
        if (_activeView == shellView || _activeView == null) {
            var focusTarget = focusFallback != null ? focusFallback : findFocusableView(_workspaceView);
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
    }

    private void replaceViewInLayout(View existingView, View replacementView) {
        ensureLayoutState();
        if (_workspaceView instanceof SplitView splitRoot && splitRoot.containsLeaf(existingView)) {
            if (!splitRoot.replaceLeaf(existingView, replacementView)) {
                throw new IllegalStateException("View is not part of the workspace layout");
            }
        } else if (existingView == _workspaceView) {
            existingView.removeFromParent();
            _workspaceView = replacementView;
            attachWorkspaceView();
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
        if (isOverlayPanel(_panelView) && _panelView != null && _panelView.getParent() == _rootView) {
            double overlayRatio = _panelView instanceof ChatPanelView ? 0.70 : (1.0 / 3.0);
            int overlayHeight = Math.max(1, (int) Math.ceil(contentHeight * overlayRatio));
            _panelView.setBounds(Rect.create(0, contentTop + contentHeight - overlayHeight, size.getWidth(), overlayHeight));
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
        if (_todoQuickCaptureView != null) {
            _todoQuickCaptureView.syncBounds();
        }
    }

    private boolean keyMenuNeedsRelayout(Size size) {
        if (_keyMenuView == null || size == null) {
            return false;
        }
        return _keyMenuView.getBounds().getSize().getHeight() != _keyMenuView.preferredHeight(size.getWidth(), size.getHeight());
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

    private boolean canEmbedDirectoryBrowserInCurrentWorkspace() {
        ensureLayoutState();
        return _currentWorkspace != null
                && _currentWorkspace._kind == WorkspaceKind.BUFFER
                && _activeView instanceof BufferView activeBufferView
                && getBufferLeafCount() > 1
                && getBufferContextFor(activeBufferView) != null;
    }

    private boolean replaceActiveBufferFrameWithDirectoryBrowser(Path directory) {
        ensureLayoutState();
        if (!(getActiveView() instanceof BufferView activeBufferView)) {
            return false;
        }
        var activeBufferContext = getBufferContextFor(activeBufferView);
        if (activeBufferContext == null) {
            return false;
        }
        var browserView = new DirectoryBrowserView(activeBufferView.getBounds(), directory);
        replaceViewInLayout(activeBufferView, browserView);
        unregisterBufferView(activeBufferView);
        _activeBufferView = findFirstBufferView();
        _bufferContext = getBufferContextFor(_activeBufferView);
        _activeView = browserView;
        activateView(browserView);
        return true;
    }

    private boolean replaceDirectoryBrowserFrameWithBuffer(DirectoryBrowserView browserView, Path path) {
        ensureLayoutState();
        BufferContext nextBufferContext;
        try {
            nextBufferContext = new BufferContext(browserView.getBounds(), path);
        } catch (Throwable e) {
            _log.error("Failed to open buffer " + path, e);
            return false;
        }
        ClangdLspPluginSupport.ensureStartedForProject(path);
        var nextBufferView = nextBufferContext.getBufferView();
        registerBufferView(nextBufferContext, nextBufferView);
        replaceViewInLayout(browserView, nextBufferView);
        _bufferContext = nextBufferContext;
        _activeBufferView = nextBufferView;
        _activeView = nextBufferView;
        setupModes();
        activateView(nextBufferView);
        return true;
    }

    private BufferContext createSplitBufferContext() {
        ensureLayoutState();
        var source = firstBufferContext();
        if (source == null) {
            return new BufferContext(Rect.create(0, 0, 0, 0), null);
        }
        return copyBufferContext(source);
    }

    private BufferContext firstBufferContext() {
        var firstBufferView = findFirstBufferView();
        if (firstBufferView != null) {
            return getBufferContextFor(firstBufferView);
        }
        return _bufferContext;
    }

    private static BufferContext copyBufferContext(BufferContext source) {
        var sourceBuffer = source.getBuffer();
        var copy = new BufferContext(Rect.create(0, 0, 0, 0), sourceBuffer.getPath());
        var copyBuffer = copy.getBuffer();
        copyBuffer.setReadOnly(false);
        if (copyBuffer.getLength() > 0) {
            copyBuffer.rawRemove(0, copyBuffer.getLength());
        }
        String sourceText = sourceBuffer.getString();
        if (!sourceText.isEmpty()) {
            copyBuffer.rawInsert(0, sourceText);
        }
        copyBuffer.getCursor().setPosition(Math.min(sourceBuffer.getCursor().getPosition(), copyBuffer.getLength()));
        copyBuffer.setReadOnly(sourceBuffer.isReadOnly());
        copy.getTextLayout().calculate();
        copy.getBufferView().adaptViewToCursor();
        return copy;
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
            return splitView.firstLeaf();
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
            detachWorkspaceView(_currentWorkspace);
        }
        restoreWorkspace(workspace);
        _currentWorkspace = workspace;
        if (_workspaceView != null && _workspaceView.getParent() == null) {
            attachWorkspaceView();
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
        workspace._shellView = view instanceof ShellPanelView shellPanelView ? shellPanelView : null;
        workspace._bufferContextsByView = new IdentityHashMap<>();
        workspace._bufferViewCounts = new IdentityHashMap<>();
        return workspace;
    }

    private WorkspaceState createPluginWorkspace(String pluginId, SwimPanel panel) {
        var workspace = createViewWorkspace(new PluginPanelView(Rect.create(0, 0, 0, 0), pluginId, panel, true),
                WorkspaceKind.PLUGIN);
        workspace._pluginId = pluginId;
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
        WorkspaceState workspace = createMailWorkspace(client);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private boolean openSlackWorkspace(org.fisk.swim.slack.SlackClient client) {
        WorkspaceState workspace = createSlackWorkspace(client);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private boolean openTodoWorkspace(org.fisk.swim.todo.TodoStore store) {
        WorkspaceState workspace = createViewWorkspace(new TodoWorkspaceView(Rect.create(0, 0, 0, 0), store),
                WorkspaceKind.TODO);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private WorkspaceState createMailWorkspace(org.fisk.swim.mail.MailClient client) {
        var workspace = new WorkspaceState();
        workspace._kind = WorkspaceKind.MAIL;
        BufferContext messageContext = createMailMessageContext(Rect.create(0, 0, 0, 0));
        MailPanelView mailView = new MailPanelView(Rect.create(0, 0, 0, 0), client);
        mailView.attachMessageBuffer(messageContext);
        var split = new SplitView(Rect.create(0, 0, 0, 0), SplitView.Orientation.VERTICAL,
                mailView, messageContext.getBufferView(), 0.48);
        workspace._workspaceView = split;
        workspace._activeView = mailView;
        workspace._activeBufferView = messageContext.getBufferView();
        workspace._bufferContext = messageContext;
        workspace._bufferContextsByView = new IdentityHashMap<>();
        workspace._bufferContextsByView.put(messageContext.getBufferView(), messageContext);
        workspace._bufferViewCounts = new IdentityHashMap<>();
        workspace._bufferViewCounts.put(messageContext, 1);
        initializeBufferWorkspaceModes(workspace);
        return workspace;
    }

    private WorkspaceState createSlackWorkspace(org.fisk.swim.slack.SlackClient client) {
        var workspace = new WorkspaceState();
        workspace._kind = WorkspaceKind.SLACK;
        BufferContext messageContext = createSlackMessageContext(Rect.create(0, 0, 0, 0));
        SlackPanelView slackView = new SlackPanelView(Rect.create(0, 0, 0, 0), client);
        slackView.attachMessageBuffer(messageContext);
        var split = new SplitView(Rect.create(0, 0, 0, 0), SplitView.Orientation.VERTICAL,
                slackView, messageContext.getBufferView(), 0.45);
        workspace._workspaceView = split;
        workspace._activeView = slackView;
        workspace._activeBufferView = messageContext.getBufferView();
        workspace._bufferContext = messageContext;
        workspace._bufferContextsByView = new IdentityHashMap<>();
        workspace._bufferContextsByView.put(messageContext.getBufferView(), messageContext);
        workspace._bufferViewCounts = new IdentityHashMap<>();
        workspace._bufferViewCounts.put(messageContext, 1);
        initializeBufferWorkspaceModes(workspace);
        return workspace;
    }

    private boolean openPluginWorkspace(String pluginId, SwimPanel panel) {
        WorkspaceState workspace = createPluginWorkspace(pluginId, panel);
        _workspaceHistory.add(0, workspace);
        return activateWorkspace(workspace);
    }

    private boolean openShellWorkspace() {
        try {
            var shellView = ShellPanelView.createWorkspace(this);
            shellView.setOnExit(() -> closeShellView(shellView));
            WorkspaceState workspace = createViewWorkspace(shellView, WorkspaceKind.SHELL);
            _workspaceHistory.add(0, workspace);
            return activateWorkspace(workspace);
        } catch (IOException e) {
            if (_commandView != null) {
                _commandView.setMessage("Failed to start shell: " + e.getMessage());
            }
            return false;
        }
    }

    private boolean openShellSplit(SplitView.Orientation orientation) {
        try {
            var shellView = ShellPanelView.createWorkspace(this);
            shellView.setOnExit(() -> closeShellView(shellView));
            boolean opened = orientation == SplitView.Orientation.HORIZONTAL
                    ? splitActiveViewHorizontally(shellView)
                    : splitActiveViewVertically(shellView);
            if (!opened) {
                shellView.closeForPanel();
                shellView.removeFromParent();
                if (_commandView != null) {
                    _commandView.setMessage("Failed to split shell workspace");
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            if (_commandView != null) {
                _commandView.setMessage("Failed to start shell workspace: " + e.getMessage());
            }
            return false;
        }
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
        if (_bufferContext != null && _activeBufferView != null) {
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
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.MAIL) {
            return WorkspaceKind.MAIL;
        }
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.SLACK) {
            return WorkspaceKind.SLACK;
        }
        if (_currentWorkspace != null && _currentWorkspace._kind == WorkspaceKind.TODO) {
            return WorkspaceKind.TODO;
        }
        if (_workspaceView instanceof MailPanelView) {
            return WorkspaceKind.MAIL;
        }
        if (_workspaceView instanceof SlackPanelView) {
            return WorkspaceKind.SLACK;
        }
        if (_workspaceView instanceof TodoWorkspaceView) {
            return WorkspaceKind.TODO;
        }
        if (_workspaceView instanceof ShellPanelView) {
            return WorkspaceKind.SHELL;
        }
        if (_workspaceView instanceof PluginPanelView) {
            return WorkspaceKind.PLUGIN;
        }
        return WorkspaceKind.BUFFER;
    }

    private void restoreWorkspace(WorkspaceState workspace) {
        _workspaceView = workspace._workspaceView;
        _activeView = workspace._activeView;
        if (workspace._bufferContext != null && workspace._activeBufferView != null) {
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

    private void installWorkspaceBufferModes(WorkspaceState workspace) {
        if (workspace == null) {
            return;
        }
        _normalMode = workspace._normalMode;
        _inputMode = workspace._inputMode;
        _visualMode = workspace._visualMode;
        _visualLineMode = workspace._visualLineMode;
        _visualBlockMode = workspace._visualBlockMode;
        _currentMode = workspace._currentMode;
    }

    private void detachWorkspaceView(WorkspaceState workspace) {
        if (workspace == null || workspace._workspaceView == null) {
            return;
        }
        if (workspace._kind == WorkspaceKind.SHELL && workspace._workspaceView == workspace._shellView
                && workspace._shellView != null) {
            workspace._shellView.detachFromParentPreservingSession();
            return;
        }
        workspace._workspaceView.removeFromParent();
    }

    private void closeWorkspaceViews(WorkspaceState workspace) {
        if (workspace == null) {
            return;
        }
        if (workspace._workspaceView != null) {
            workspace._workspaceView.removeFromParent();
        }
        if (workspace._kind == WorkspaceKind.SHELL && workspace._shellView != null
                && workspace._shellView != workspace._workspaceView) {
            workspace._shellView.removeFromParent();
        }
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
        case SLACK -> "slack";
        case SHELL -> workspace._workspaceView instanceof ShellPanelView shellPanelView ? shellPanelView.getTitle() : "shell";
        case TODO -> "todo";
        case PLUGIN -> workspace._workspaceView instanceof PluginPanelView pluginPanelView ? pluginPanelView.getTitle()
                : workspace._pluginId == null ? "plugin" : workspace._pluginId;
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

    private static boolean shellViewIsAlive(ShellPanelView shellView) {
        try {
            var field = ShellPanelView.class.getDeclaredField("_process");
            field.setAccessible(true);
            Process process = (Process) field.get(shellView);
            return process != null && process.isAlive();
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static BufferContext createShellBrowseContext(ShellPanelView shellView) {
        var bounds = shellView.getBounds();
        var context = new BufferContext(Rect.create(0, 0, bounds.getSize().getWidth(), bounds.getSize().getHeight()), null);
        String text = shellView.buildBrowseText();
        if (!text.isEmpty()) {
            context.getBuffer().rawInsert(0, text);
        }
        context.getBuffer().setReadOnly(true);
        context.getTextLayout().calculate();
        context.getBuffer().getCursor().setPosition(context.getBuffer().getLength());
        context.getBufferView().adaptViewToCursor();
        return context;
    }

    private static BufferContext createMailMessageContext(Rect bounds) {
        var context = new BufferContext(bounds, null);
        context.getBuffer().setReadOnly(true);
        context.getTextLayout().calculate();
        return context;
    }

    private static BufferContext createSlackMessageContext(Rect bounds) {
        var context = new BufferContext(bounds, null);
        context.getBuffer().setReadOnly(true);
        context.getTextLayout().calculate();
        return context;
    }

    private static MailPanelView findMailPanelView(View view) {
        if (view instanceof MailPanelView mailPanelView) {
            return mailPanelView;
        }
        if (view instanceof SplitView splitView) {
            for (var leaf : splitView.leafViews()) {
                if (leaf instanceof MailPanelView mailPanelView) {
                    return mailPanelView;
                }
            }
        }
        return null;
    }

    private static SlackPanelView findSlackPanelView(View view) {
        if (view instanceof SlackPanelView slackPanelView) {
            return slackPanelView;
        }
        if (view instanceof SplitView splitView) {
            for (var leaf : splitView.leafViews()) {
                if (leaf instanceof SlackPanelView slackPanelView) {
                    return slackPanelView;
                }
            }
        }
        return null;
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
            leaves.addAll(splitView.leafViews());
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
