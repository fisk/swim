package org.fisk.swim.mode;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.event.FancyJumpResponder;
import org.fisk.swim.fileindex.FileIndex;
import org.fisk.swim.lsp.java.JavaLspPluginSupport;
import org.fisk.swim.mail.MailUiSupport;
import org.fisk.swim.nemo.NemoClient;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.PluginPanelView;
import org.fisk.swim.ui.ShellPanelView;
import org.fisk.swim.ui.Window;

public class NormalMode extends Mode {
    private static final String TREE_VIEW_PLUGIN_ID = "swim-tree-view";
    private FancyJumpResponder _fancyJump;
    
    public NormalMode(Window window) {
        super("NORMAL", window);
        setupBasicResponders();
        setupNavigationResponders();
    }

    private void setupBasicResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        String leader = "<SPACE>";
        _fancyJump = new FancyJumpResponder(bufferContext, 'w');
        _rootResponder.addEventResponder(_fancyJump);
        JavaLspPluginSupport.installNormalModeBindings(this, window, leader);
        _rootResponder.addEventResponder("i", () -> { window.switchToMode(window.getInputMode()); });
        _rootResponder.addEventResponder("v", () -> { window.switchToMode(window.getVisualMode()); });
        _rootResponder.addEventResponder("V", () -> { window.switchToMode(window.getVisualLineMode()); });
        _rootResponder.addEventResponder("<CTRL>-v", () -> { window.switchToMode(window.getVisualBlockMode()); });
        _rootResponder.addEventResponder("<CTRL>-w s", () -> { window.splitActiveBufferVertically(); });
        _rootResponder.addEventResponder("<CTRL>-w v", () -> { window.splitActiveBufferHorizontally(); });
        _rootResponder.addEventResponder("<CTRL>-w h", () -> { announceIfUnmoved(window.focusView(Window.Direction.LEFT), "No pane to the left"); });
        _rootResponder.addEventResponder("<CTRL>-w j", () -> { announceIfUnmoved(window.focusView(Window.Direction.DOWN), "No pane below"); });
        _rootResponder.addEventResponder("<CTRL>-w k", () -> { announceIfUnmoved(window.focusView(Window.Direction.UP), "No pane above"); });
        _rootResponder.addEventResponder("<CTRL>-w l", () -> { announceIfUnmoved(window.focusView(Window.Direction.RIGHT), "No pane to the right"); });
        _rootResponder.addEventResponder("<CTRL>-w w", () -> { announceIfUnmoved(window.focusNextView(), "No other pane"); });
        _rootResponder.addEventResponder("<CTRL>-w W", () -> { announceIfUnmoved(window.focusPreviousView(), "No other pane"); });
        _rootResponder.addEventResponder("<CTRL>-w q", () -> { announceIfUnmoved(window.closeActiveView(), "Cannot close the last buffer view"); });
        _rootResponder.addEventResponder("<CTRL>-w o", () -> { announceIfUnmoved(window.closeOtherViews(), "No other panes to close"); });
        _rootResponder.addEventResponder("u", () -> { window.getBufferContext().getBuffer().undo(); });
        _rootResponder.addEventResponder("<CTRL>-r", () -> {window.getBufferContext().getBuffer().redo(); });
        _rootResponder.addEventResponder("d i w", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            activeBuffer.deleteInnerWord();
            activeBuffer.getUndoLog().commit();
        });
        _rootResponder.addEventResponder("d w", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            activeBuffer.deleteWord();
            activeBuffer.getUndoLog().commit();
        });
        _rootResponder.addEventResponder("d d", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            activeBuffer.deleteLine();
            activeBuffer.getUndoLog().commit();
        });
        _rootResponder.addEventResponder("x", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            activeBuffer.removeAt();
            activeBuffer.getUndoLog().commit();
        });
        _rootResponder.addEventResponder("c i w", () -> {
            window.getBufferContext().getBuffer().deleteInnerWord();
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("c w", () -> {
            window.getBufferContext().getBuffer().deleteWord();
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("a", () -> {
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goRight();
        });
        _rootResponder.addEventResponder("A", () -> {
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goEndOfLine();
        });
        _rootResponder.addEventResponder("o", () -> {
            var cursor = window.getBufferContext().getBuffer().getCursor();
            var activeBuffer = window.getBufferContext().getBuffer();
            cursor.goEndOfLine();
            window.switchToMode(window.getInputMode());
            activeBuffer.insert("\n");
        });
        _rootResponder.addEventResponder("O", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            cursor.goStartOfLine();
            cursor.goBack();
            boolean isFirst = cursor.getPosition() == 0;
            window.switchToMode(window.getInputMode());
            activeBuffer.insert("\n");
            if (isFirst) {
                cursor.goBack();
            }
        });
        _rootResponder.addEventResponder("p", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            if (Copy.getInstance().isLine()) {
                cursor.goEndOfLine();
                cursor.goForward();
                activeBuffer.insert(Copy.getInstance().getText());
                cursor.goBack();
            } else {
                cursor.goForward();
                activeBuffer.insert(Copy.getInstance().getText());
            }
        });
        _rootResponder.addEventResponder("P", () -> {
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            if (Copy.getInstance().isLine()) {
                cursor.goStartOfLine();
                activeBuffer.insert(Copy.getInstance().getText());
            } else {
                activeBuffer.insert(Copy.getInstance().getText());
            }
        });
        _rootResponder.addEventResponder("y y", () -> {
            var text = window.getBufferContext().getBuffer().getCurrentLineText();
            Copy.getInstance().setText(text, true /* isLine */);
        });
        _rootResponder.addEventResponder("m", () -> {
            if (window.isShowingList()) {
                window.hideList();
            } else {
                window.showList(FileIndex.createFileList(), "Project Files");
            }
        });
        _rootResponder.addEventResponder("e", () -> {
            MailUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder("t", () -> {
            toggleTreeView();
        });
        _rootResponder.addEventResponder(":", () -> {
            window.getCommandView().activate(":");
        });
        _rootResponder.addEventResponder("!", () -> {
            NemoClient.getInstance().run(window.getBufferContext(), "");
        });
        _rootResponder.addEventResponder(">", () -> {
            try {
                var shellView = ShellPanelView.createDefault(window, org.fisk.swim.ui.Rect.create(0, 0, 0, 0));
                window.showPanel(shellView);
            } catch (java.io.IOException e) {
                window.getCommandView().setMessage("Failed to start shell: " + e.getMessage());
            }
        });
        _rootResponder.addEventResponder("*", () -> {
            var word = window.getBufferContext().getBuffer().getInnerWord();
            if (word != null && !word.equals("")) {
                window.getCommandView().activate("/");
                window.getCommandView().runSearch(word);
                window.getCommandView().deactivate();
            }
        });
        _rootResponder.addEventResponder("#", () -> {
            var word = window.getBufferContext().getBuffer().getInnerWord();
            if (word != null && !word.equals("")) {
                window.getCommandView().activate("?");
                window.getCommandView().runSearch(word);
                window.getCommandView().deactivate();
            }
        });
        _rootResponder.addEventResponder("/", () -> {
            window.getCommandView().activate("/");
        });
        _rootResponder.addEventResponder("?", () -> {
            window.getCommandView().activate("?");
        });
        _rootResponder.addEventResponder("n", () -> {
            window.getCommandView().searchNext();
        });
        _rootResponder.addEventResponder("N", () -> {
            window.getCommandView().searchPrevious();
        });
        _rootResponder.addEventResponder("<ESC>", () -> {
            NemoClient.getInstance().run(window.getBufferContext(), "");
        });
    }

    private void announceIfUnmoved(boolean changed, String message) {
        if (!changed) {
            _window.getCommandView().setMessage(message);
        }
    }

    private void toggleTreeView() {
        if (_window.getPanelView() instanceof PluginPanelView panelView
                && TREE_VIEW_PLUGIN_ID.equals(panelView.getPluginId())) {
            _window.hidePanel();
            return;
        }
        if (_window.isShowingPanel()) {
            _window.hidePanel();
        }
        SwimRuntime.loadPlugin(TREE_VIEW_PLUGIN_ID);
        var panel = SwimRuntime.getPanel(TREE_VIEW_PLUGIN_ID);
        if (panel == null) {
            _window.getCommandView().setMessage("Tree view plugin unavailable");
            return;
        }
        panel.syncToCurrentPath(_window.getBufferContext().getBuffer().getPath());
        if (!_window.showSidePanel(new PluginPanelView(org.fisk.swim.ui.Rect.create(0, 0, 0, 0), TREE_VIEW_PLUGIN_ID, panel),
                true, 0.28)) {
            _window.getCommandView().setMessage("Unable to open tree view");
        }
    }

    @Override
    public void activate() {
        _window.getBufferContext().getBuffer().getUndoLog().commit();
    }
    
    @Override
    public AttributedString decorate(Glyph glyph, AttributedString character) {
        character = _fancyJump.decorate(glyph, character);
        return character;
    }
}
