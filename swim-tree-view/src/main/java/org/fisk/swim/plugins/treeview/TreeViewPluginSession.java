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
        return _panel.handleCommand(command);
    }

    public TreeViewCommandResult handleInput(String input) throws IOException {
        return _panel.handleInput(input, DEFAULT_INPUT_BINDINGS);
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
        return _panel.selectPath(normalized);
    }

    private TreeViewActionHandlerResult dispatchAction(TreeViewAction action, TreeViewActionHandler actionHandler) {
        Objects.requireNonNull(actionHandler);
        if (action == null || action.type() == TreeViewActionType.NONE) {
            return TreeViewActionHandlerResult.ignored();
        }
        TreeViewActionHandlerResult result = actionHandler.handle(action);
        return result == null ? TreeViewActionHandlerResult.ignored() : result;
    }

    @Override
    public void close() {
    }
}
