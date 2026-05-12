package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.fisk.swim.api.SwimPluginContext;

public final class TreeViewPluginSession implements AutoCloseable {
    private static final TreeViewInputBindings DEFAULT_INPUT_BINDINGS = TreeViewInputBindings.defaults();

    private final SwimPluginContext _context;
    private final Path _projectRoot;
    private final TreeViewController _controller;
    private final TreeViewPanel _panel;
    private Path _lastSyncedPath;
    private boolean _manualNavigationActive;

    public TreeViewPluginSession(SwimPluginContext context) throws IOException {
        _context = Objects.requireNonNull(context);
        _projectRoot = TreeViewProjectRoot.resolve(context.getInitialPath(), context.getCurrentPath());
        _controller = new TreeViewController(_projectRoot);
        Path fileName = _projectRoot.getFileName();
        String title = fileName == null ? _projectRoot.toString() : fileName.toString();
        _panel = new TreeViewPanel(_controller, new TreeViewRenderer(), "Tree: " + title);
        syncToCurrentPath();
    }

    public SwimPluginContext getContext() {
        return _context;
    }

    public Path getProjectRoot() {
        return _projectRoot;
    }

    public TreeViewController getController() {
        return _controller;
    }

    public TreeViewPanel getPanel() {
        return _panel;
    }

    public void setFileOpener(TreeViewFileOpener fileOpener) {
        _controller.setFileOpener(fileOpener);
    }

    public void refresh() throws IOException {
        _panel.refresh();
        syncToCurrentPath();
    }

    public TreeViewCommandResult handleCommand(TreeViewCommand command) throws IOException {
        TreeViewCommandResult result = _panel.handleCommand(command);
        updateSyncTracking(command, result);
        return result;
    }

    public TreeViewCommandResult handleInput(String input) throws IOException {
        Objects.requireNonNull(input);
        var command = DEFAULT_INPUT_BINDINGS.lookup(input);
        if (command.isEmpty()) {
            return TreeViewCommandResult.unhandled();
        }
        return handleCommand(command.get());
    }

    public TreeViewInteractionResult interact(TreeViewCommand command, int width, int height) throws IOException {
        TreeViewCommandResult result = handleCommand(command);
        return new TreeViewInteractionResult(result, snapshot(width, height));
    }

    public TreeViewInteractionResult interact(String input, int width, int height) throws IOException {
        TreeViewCommandResult result = handleInput(input);
        return new TreeViewInteractionResult(result, snapshot(width, height));
    }

    public TreeViewHandledInteractionResult interact(
            TreeViewCommand command,
            int width,
            int height,
            TreeViewActionHandler actionHandler) throws IOException {
        TreeViewInteractionResult interactionResult = interact(command, width, height);
        return new TreeViewHandledInteractionResult(
            interactionResult,
            dispatchAction(interactionResult.commandResult().action(), actionHandler));
    }

    public TreeViewHandledInteractionResult interact(
            String input,
            int width,
            int height,
            TreeViewActionHandler actionHandler) throws IOException {
        TreeViewInteractionResult interactionResult = interact(input, width, height);
        return new TreeViewHandledInteractionResult(
            interactionResult,
            dispatchAction(interactionResult.commandResult().action(), actionHandler));
    }

    public TreeViewRenderSnapshot snapshot(int width, int height) {
        return _panel.snapshot(width, height);
    }

    public TreeViewInputBindings getInputBindings() {
        return DEFAULT_INPUT_BINDINGS;
    }

    public boolean syncToCurrentPath() {
        return syncToPath(_context.getCurrentPath());
    }

    public boolean syncToPath(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(_projectRoot)) {
            return false;
        }
        if (_manualNavigationActive && normalized.equals(_lastSyncedPath)) {
            return false;
        }
        Path selectedPath = _controller.getSelectedPath();
        boolean changed = _panel.selectPath(normalized);
        _lastSyncedPath = normalized;
        _manualNavigationActive = false;
        return changed && !normalized.equals(selectedPath);
    }

    private TreeViewActionHandlerResult dispatchAction(TreeViewAction action, TreeViewActionHandler actionHandler) {
        Objects.requireNonNull(actionHandler);
        if (action == null || action.type() == TreeViewActionType.NONE) {
            return TreeViewActionHandlerResult.ignored();
        }
        TreeViewActionHandlerResult result = actionHandler.handle(action);
        return result == null ? TreeViewActionHandlerResult.ignored() : result;
    }

    private void updateSyncTracking(TreeViewCommand command, TreeViewCommandResult result) {
        if (!result.handled()) {
            return;
        }
        switch (command) {
            case MOVE_UP, MOVE_DOWN, EXPAND, COLLAPSE -> _manualNavigationActive = true;
            case ACTIVATE -> {
                if (result.action().type() == TreeViewActionType.OPEN_FILE) {
                    _lastSyncedPath = result.action().path();
                    _manualNavigationActive = false;
                } else {
                    _manualNavigationActive = true;
                }
            }
            case REFRESH -> {
            }
        }
    }

    @Override
    public void close() {
    }
}
