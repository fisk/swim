package org.fisk.swim.mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.debug.DebuggerManager;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.FancyJumpResponder;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.fileindex.FileIndex;
import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimPluginKeyBindingDescriptor;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.nemo.NemoClient;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.BufferView;
import org.fisk.swim.ui.ProjectSearchUiSupport;
import org.fisk.swim.ui.Range;
import org.fisk.swim.ui.ShellPanelView;
import org.fisk.swim.todo.TodoUiSupport;
import org.fisk.swim.ui.Window;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.MotionResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.TextEventResponder;

public class NormalMode extends Mode {
    private FancyJumpResponder _fancyWordJump;
    private FancyJumpResponder _fancyCharacterJump;
    private Character _lastFindCharacter;
    private boolean _lastFindForward;
    private boolean _lastFindTill;
    
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
        _fancyWordJump = new FancyJumpResponder(bufferContext, "g w");
        _fancyCharacterJump = new FancyJumpResponder(bufferContext, "g c", FancyJumpResponder.TargetKind.CHARACTER);
        _rootResponder.addEventResponder(_fancyWordJump);
        _rootResponder.addEventResponder(_fancyCharacterJump);
        installPluginKeyBindings(window);
        installLeaderMoveBindings(window, leader);
        installLeaderWorkspaceBindings(window, leader);
        _rootResponder.addEventResponder("i", "Editing", "insert", () -> {
            allow("enter input mode");
            if (window.exitShellBrowseToPrompt()) {
                return;
            }
            if (window.exitNemoBrowseToPrompt()) {
                return;
            }
            window.beginRepeatRecording("i");
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("v", "Selection", "visual", allowed("enter visual mode", () -> { window.switchToMode(window.getVisualMode()); }));
        _rootResponder.addEventResponder("V", "Selection", "visual line", allowed("enter visual line mode", () -> { window.switchToMode(window.getVisualLineMode()); }));
        _rootResponder.addEventResponder("<CTRL>-v", "Selection", "visual block", allowed("enter visual block mode", () -> { window.switchToMode(window.getVisualBlockMode()); }));
        _rootResponder.addEventResponder("<CTRL>-w s", "Panes", "split below", "split", allowed("split buffer", () -> { window.splitActiveBufferVertically(); }));
        _rootResponder.addEventResponder("<CTRL>-w v", "Panes", "split right", "vsplit", allowed("split buffer", () -> { window.splitActiveBufferHorizontally(); }));
        _rootResponder.addEventResponder("<CTRL>-w h", "Panes", "focus left", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.LEFT), "No pane to the left"); }));
        _rootResponder.addEventResponder("<CTRL>-w j", "Panes", "focus down", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.DOWN), "No pane below"); }));
        _rootResponder.addEventResponder("<CTRL>-w k", "Panes", "focus up", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.UP), "No pane above"); }));
        _rootResponder.addEventResponder("<CTRL>-w l", "Panes", "focus right", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.RIGHT), "No pane to the right"); }));
        _rootResponder.addEventResponder("<CTRL>-w w", "Panes", "next pane", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusNextView(), "No other pane"); }));
        _rootResponder.addEventResponder("<CTRL>-w W", "Panes", "previous pane", "focus", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusPreviousView(), "No other pane"); }));
        _rootResponder.addEventResponder("<CTRL>-w q", "Panes", "close pane", "close", allowed("close buffer split", () -> { announceIfUnmoved(window.closeActiveView(), "Cannot close the last buffer view"); }));
        _rootResponder.addEventResponder("<CTRL>-w o", "Panes", "only pane", "only", allowed("close buffer splits", () -> { announceIfUnmoved(window.closeOtherViews(), "No other panes to close"); }));
        _rootResponder.addEventResponder(ctrlWMotion(">", "wider", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewWidth(4 * count), "No vertical split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("<", "narrower", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewWidth(-4 * count), "No vertical split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("+", "taller", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewHeight(2 * count), "No horizontal split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("-", "shorter", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewHeight(-2 * count), "No horizontal split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("=", "equalize", ignored ->
                { allow("resize buffer split"); announceIfUnmoved(window.equalizeSplits(), "No split to equalize"); }));
        _rootResponder.addEventResponder("<CTRL>-s", () -> {
            if (window.blockEditorDriveAction("<CTRL>-s", "sending mail or Slack messages is not allowed")) {
                return;
            }
            if (window.sendActiveMailCompose()) {
                return;
            }
            if (window.sendActiveSlackCompose()) {
                return;
            }
        });
        _rootResponder.addEventResponder("<CTRL>-g c w", "Shell", "new shell workspace", "shell", () -> { startShell(window, ShellTarget.WORKSPACE); });
        _rootResponder.addEventResponder("<CTRL>-g c v", "Shell", "shell in split right", "vshell", () -> { startShell(window, ShellTarget.VERTICAL_SPLIT); });
        _rootResponder.addEventResponder("<CTRL>-g c h", "Shell", "shell in split below", "hshell", () -> { startShell(window, ShellTarget.HORIZONTAL_SPLIT); });
        _rootResponder.addEventResponder(hinted(registerResponder((ignored, register) -> { allow("select register"); window.selectRegister(register); }),
                KeyBindingHint.of("\" <CHAR>", "Registers", "select register")));
        _rootResponder.addEventResponder(hinted(prefixCharacterResponder("g m", (ignored, mark) -> { allow("set mark"); window.setMark(mark); }),
                KeyBindingHint.of("g m <CHAR>", "Marks", "set mark")));
        _rootResponder.addEventResponder(hinted(markJumpResponder("'", true, window),
                KeyBindingHint.of("' <CHAR>", "Marks", "jump to line mark")));
        _rootResponder.addEventResponder(hinted(markJumpResponder("`", false, window),
                KeyBindingHint.of("` <CHAR>", "Marks", "jump to exact mark")));
        _rootResponder.addEventResponder(hinted(macroResponder(window),
                KeyBindingHint.of("q <CHAR>", "Macros", "record macro")));
        _rootResponder.addEventResponder("g n", "Multicursor", "add next cursor", allowed("multicursor", () -> { announceIfUnmoved(window.addNextCursorForCurrentWord(true), "No next match for multicursor"); }));
        _rootResponder.addEventResponder("g N", "Multicursor", "add previous cursor", allowed("multicursor", () -> { announceIfUnmoved(window.addNextCursorForCurrentWord(false), "No previous match for multicursor"); }));
        _rootResponder.addEventResponder("g C", "Multicursor", "clear extra cursors", allowed("multicursor", window::clearAdditionalCursors));
        _rootResponder.addEventResponder("g ]", "Diagnostics", "next warning or error", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(true, false); }));
        _rootResponder.addEventResponder("g [", "Diagnostics", "previous warning or error", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(false, false); }));
        _rootResponder.addEventResponder("g }", "Diagnostics", "next error", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(true, true); }));
        _rootResponder.addEventResponder("g {", "Diagnostics", "previous error", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(false, true); }));
        _rootResponder.addEventResponder("g x", "Diagnostics", "show diagnostics", allowed("diagnostic popup", () -> { window.showDiagnosticsForCurrentLine(true); }));
        _rootResponder.addEventResponder("g a", "Code", "suggested fixes", allowed("code actions", () -> { window.showCodeActionsForCurrentLine(); }));
        _rootResponder.addEventResponder(hinted(prefixCharacterResponder("@", (ignored, register) -> {
            if (window.blockEditorDriveAction("macro playback", "macros are outside the editor-control sandbox")) {
                return;
            }
            if (register == '@') {
                window.playLastMacro(1);
            } else {
                window.playMacro(register, 1);
            }
        }), KeyBindingHint.of("@ <CHAR>", "Macros", "play macro")));
        _rootResponder.addEventResponder(new MotionResponder(".", "Macros", "repeat last edit", count -> { allow("repeat edit"); window.repeatLastEdit(count); }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-o", "Navigation", "jump back", count -> { allow("jump navigation"); repeat(count, window::jumpBack); }));
        _rootResponder.addEventResponder(new MotionResponder("<TAB>", "Navigation", "jump forward", count -> { allow("jump navigation"); repeat(count, window::jumpForward); }));
        _rootResponder.addEventResponder(hinted(foldCreateResponder(window),
                KeyBindingHint.of("z f", "Folds", "fold motion"),
                KeyBindingHint.of("z F", "Folds", "fold lines")));
        _rootResponder.addEventResponder("z a", "Folds", "toggle fold", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().toggleFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z A", "Folds", "toggle recursive", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().toggleFoldRecursivelyAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z c", "Folds", "close fold", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().closeFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z o", "Folds", "open fold", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().openFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z v", "Folds", "open at cursor", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().openFoldRecursivelyAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z C", "Folds", "close recursive", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().closeFoldRecursivelyAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z O", "Folds", "open recursive", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().openFoldRecursivelyAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z d", "Folds", "delete fold", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().deleteFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z D", "Folds", "delete recursive", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().deleteFoldRecursivelyAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z E", "Folds", "delete all folds", allowed("fold", () -> { window.getBufferContext().getBuffer().deleteAllFolds(); }));
        _rootResponder.addEventResponder(new MotionResponder("z j", "Folds", "next fold", count -> { allow("fold navigation"); announceIfUnmoved(goFoldStart(window, true, count), "No next fold"); }));
        _rootResponder.addEventResponder(new MotionResponder("z k", "Folds", "previous fold", count -> { allow("fold navigation"); announceIfUnmoved(goFoldStart(window, false, count), "No previous fold"); }));
        _rootResponder.addEventResponder("z M", "Folds", "close all", allowed("fold", () -> { window.getBufferContext().getBuffer().closeAllFolds(); }));
        _rootResponder.addEventResponder("z R", "Folds", "open all", allowed("fold", () -> { window.getBufferContext().getBuffer().openAllFolds(); }));
        _rootResponder.addEventResponder("z t", "Viewport", "cursor line to top", allowed("scroll buffer",
                () -> { window.getBufferContext().getBufferView().alignCursorLine(BufferView.ViewportAnchor.TOP); }));
        _rootResponder.addEventResponder("z z", "Viewport", "cursor line to middle", allowed("scroll buffer",
                () -> { window.getBufferContext().getBufferView().alignCursorLine(BufferView.ViewportAnchor.MIDDLE); }));
        _rootResponder.addEventResponder("z b", "Viewport", "cursor line to bottom", allowed("scroll buffer",
                () -> { window.getBufferContext().getBufferView().alignCursorLine(BufferView.ViewportAnchor.BOTTOM); }));
        _rootResponder.addEventResponder("u", "Editing", "undo", allowed("undo", () -> { window.getBufferContext().getBuffer().undo(); }));
        _rootResponder.addEventResponder("<CTRL>-r", "Editing", "redo", allowed("redo", () -> {window.getBufferContext().getBuffer().redo(); }));
        _rootResponder.addEventResponder("d i w", "Editing", "inner word", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("d i w");
            activeBuffer.deleteInnerWord();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        installTextObjectResponder(window, "d i (", "(", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a (", "(", true, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d i [", "[", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a [", "[", true, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d i {", "{", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a {", "{", true, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d i \"", "\"", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a \"", "\"", true, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d i '", "'", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a '", "'", true, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d i p", "p", false, TextObjectOperator.DELETE);
        installTextObjectResponder(window, "d a p", "p", true, TextObjectOperator.DELETE);
        _rootResponder.addEventResponder("d w", "Editing", "word", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("d w");
            activeBuffer.deleteWord();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("d d", "Editing", "line", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("d d");
            activeBuffer.deleteLine();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("x", "Editing", "delete character", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("x");
            activeBuffer.removeAt();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("c i w", "Editing", "inner word", () -> {
            allow("buffer edit");
            window.beginRepeatRecording("c i w");
            window.getBufferContext().getBuffer().deleteInnerWord();
            window.switchToMode(window.getInputMode());
        });
        installTextObjectResponder(window, "c i (", "(", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a (", "(", true, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c i [", "[", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a [", "[", true, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c i {", "{", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a {", "{", true, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c i \"", "\"", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a \"", "\"", true, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c i '", "'", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a '", "'", true, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c i p", "p", false, TextObjectOperator.CHANGE);
        installTextObjectResponder(window, "c a p", "p", true, TextObjectOperator.CHANGE);
        _rootResponder.addEventResponder("c w", "Editing", "word", () -> {
            allow("buffer edit");
            window.beginRepeatRecording("c w");
            window.getBufferContext().getBuffer().deleteWord();
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("a", "Editing", "append", () -> {
            allow("enter input mode");
            window.beginRepeatRecording("a");
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goRight();
        });
        _rootResponder.addEventResponder("A", "Editing", "append line end", () -> {
            allow("enter input mode");
            window.beginRepeatRecording("A");
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goEndOfLine();
        });
        _rootResponder.addEventResponder("o", "Editing", "open below", () -> {
            allow("buffer edit");
            var cursor = window.getBufferContext().getBuffer().getCursor();
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("o");
            cursor.goEndOfLine();
            window.switchToMode(window.getInputMode());
            activeBuffer.insert("\n");
        });
        _rootResponder.addEventResponder("O", "Editing", "open above", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            cursor.goStartOfLine();
            cursor.goBack();
            boolean isFirst = cursor.getPosition() == 0;
            window.beginRepeatRecording("O");
            window.switchToMode(window.getInputMode());
            activeBuffer.insert("\n");
            if (isFirst) {
                cursor.goBack();
            }
        });
        _rootResponder.addEventResponder("p", "Editing", "paste after", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            var value = Copy.getInstance().getValue(window.consumeSelectedRegister());
            window.beginRepeatRecording("p");
            if (value.isBlock()) {
                activeBuffer.insertBlock(value.blockLines(), true);
                activeBuffer.getUndoLog().commit();
                window.commitRepeatRecording();
                return;
            }
            if (value.isLine()) {
                cursor.goEndOfLine();
                cursor.goForward();
                activeBuffer.insert(value.text());
                cursor.goBack();
            } else {
                cursor.goForward();
                activeBuffer.insert(value.text());
            }
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("P", "Editing", "paste before", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            var value = Copy.getInstance().getValue(window.consumeSelectedRegister());
            window.beginRepeatRecording("P");
            if (value.isBlock()) {
                activeBuffer.insertBlock(value.blockLines(), false);
                activeBuffer.getUndoLog().commit();
                window.commitRepeatRecording();
                return;
            }
            if (value.isLine()) {
                cursor.goStartOfLine();
                activeBuffer.insert(value.text());
            } else {
                activeBuffer.insert(value.text());
            }
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("D", "Editing", "delete to line end", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            int start = activeBuffer.getCursor().getPosition();
            int end = activeBuffer.getLineEndPosition(start, false);
            window.beginRepeatRecording("D");
            activeBuffer.deleteRange(start, end, false, window.consumeSelectedRegister());
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("C", "Editing", "change to line end", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            int start = activeBuffer.getCursor().getPosition();
            int end = activeBuffer.getLineEndPosition(start, false);
            window.beginRepeatRecording("C");
            activeBuffer.changeRange(start, end, false, window.consumeSelectedRegister());
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("Y", "Editing", "yank line", () -> {
            allow("yank");
            var activeBuffer = window.getBufferContext().getBuffer();
            var range = activeBuffer.lineRangeForCount(1);
            activeBuffer.yankRange(range.getStart(), range.getEnd(), true, window.consumeSelectedRegister());
        });
        _rootResponder.addEventResponder("J", "Editing", "join lines", () -> {
            allow("buffer edit");
            window.beginRepeatRecording("J");
            if (window.getBufferContext().getBuffer().joinLines(1)) {
                window.getBufferContext().getBuffer().getUndoLog().commit();
            }
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("S", "Editing", "substitute line", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var range = activeBuffer.lineRangeForCount(1);
            window.beginRepeatRecording("S");
            activeBuffer.changeRange(range.getStart(), range.getEnd(), true, window.consumeSelectedRegister());
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder(hinted(replaceCharacterResponder(window),
                KeyBindingHint.of("r <CHAR>", "Editing", "replace character")));
        _rootResponder.addEventResponder("R", "Editing", "replace mode", () -> {
            allow("enter replace mode");
            window.beginRepeatRecording("R");
            window.switchToMode(window.getReplaceMode());
        });
        _rootResponder.addEventResponder("y y", "Editing", "yank line", () -> {
            allow("yank");
            var text = window.getBufferContext().getBuffer().getCurrentLineText();
            Copy.getInstance().setYank(text, true /* isLine */, window.consumeSelectedRegister());
        });
        installTextObjectResponder(window, "y i (", "(", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a (", "(", true, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y i [", "[", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a [", "[", true, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y i {", "{", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a {", "{", true, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y i \"", "\"", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a \"", "\"", true, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y i '", "'", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a '", "'", true, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y i p", "p", false, TextObjectOperator.YANK);
        installTextObjectResponder(window, "y a p", "p", true, TextObjectOperator.YANK);
        _rootResponder.addEventResponder("B", "Debug", "toggle breakpoint", () -> {
            if (window.blockEditorDriveAction("debug breakpoint", "debugger actions are outside the editor-control sandbox")) {
                return;
            }
            try {
                DebuggerManager.toggleBreakpointAtCursor();
            } catch (Exception e) {
                window.getCommandView().setMessage(e.getMessage() == null ? "Failed to toggle breakpoint" : e.getMessage());
            }
        });
        _rootResponder.addEventResponder(":", "Workspace", "command line", () -> {
            window.getCommandView().activate(":");
        });
        _rootResponder.addEventResponder("!", "Tools", "Nemo", "nemo", () -> {
            if (window.blockEditorDriveAction("Nemo chat", "opening Nemo from editor control is not allowed")) {
                return;
            }
            NemoClient.getInstance().run(window.getBufferContext(), "");
        });
        _rootResponder.addEventResponder(">", "Tools", "shell panel", () -> {
            if (window.blockEditorDriveAction("shell panel", "opening shell input through drive_editor is not allowed")) {
                return;
            }
            if (!window.showShellPanel()) {
                window.getCommandView().setMessage("Failed to start shell");
            }
        });
        _rootResponder.addEventResponder("*", "Search", "search current word forward", () -> {
            allow("search current word");
            var word = window.getBufferContext().getBuffer().getInnerWord();
            if (word != null && !word.equals("")) {
                window.getCommandView().activate("/");
                window.getCommandView().runSearch(word);
                window.getCommandView().deactivate();
            }
        });
        _rootResponder.addEventResponder("#", "Search", "search current word backward", () -> {
            allow("search current word");
            var word = window.getBufferContext().getBuffer().getInnerWord();
            if (word != null && !word.equals("")) {
                window.getCommandView().activate("?");
                window.getCommandView().runSearch(word);
                window.getCommandView().deactivate();
            }
        });
        _rootResponder.addEventResponder("/", "Search", "forward search", () -> {
            window.getCommandView().activate("/");
        });
        _rootResponder.addEventResponder("?", "Search", "backward search", () -> {
            window.getCommandView().activate("?");
        });
        _rootResponder.addEventResponder("n", "Search", "next match", () -> {
            allow("search next");
            window.getCommandView().searchNext();
        });
        _rootResponder.addEventResponder("N", "Search", "previous match", () -> {
            allow("search previous");
            window.getCommandView().searchPrevious();
        });
        _rootResponder.addEventResponder("~", "Editing", "toggle case", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            int start = activeBuffer.getCursor().getPosition();
            int end = Math.min(activeBuffer.getLength(), start + 1);
            activeBuffer.transformRange(start, end, false, NormalMode::toggleCase);
            activeBuffer.getCursor().goRight();
            activeBuffer.getUndoLog().commit();
        });
        _rootResponder.addEventResponder("s", "Editing", "substitute character", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            int start = activeBuffer.getCursor().getPosition();
            int end = Math.min(activeBuffer.getLineEndPosition(start, false), start + 1);
            window.beginRepeatRecording("s");
            activeBuffer.changeRange(start, end, false, window.consumeSelectedRegister());
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder(hinted(characterFindResponder(window),
                KeyBindingHint.of("f <CHAR>", "Navigation", "next matching character"),
                KeyBindingHint.of("F <CHAR>", "Navigation", "previous matching character"),
                KeyBindingHint.of("t <CHAR>", "Navigation", "before next matching character"),
                KeyBindingHint.of("T <CHAR>", "Navigation", "after previous matching character"),
                KeyBindingHint.of(";", "Navigation", "repeat character find"),
                KeyBindingHint.of(",", "Navigation", "reverse character find")));
        _rootResponder.addEventResponder(hinted(operatorResponder(window),
                KeyBindingHint.of("d", "Editing", "delete operator"),
                KeyBindingHint.of("c", "Editing", "change operator"),
                KeyBindingHint.of("y", "Editing", "yank operator"),
                KeyBindingHint.of(">", "Editing", "indent operator"),
                KeyBindingHint.of("<", "Editing", "outdent operator"),
                KeyBindingHint.of("=", "Editing", "format operator"),
                KeyBindingHint.of("g U", "Editing", "uppercase operator"),
                KeyBindingHint.of("g u", "Editing", "lowercase operator"),
                KeyBindingHint.of("g ~", "Editing", "toggle-case operator")));
        _rootResponder.addEventResponder(hinted(prefixCharacterResponder("m", (ignored, mark) -> {
            allow("set mark");
            window.setMark(mark);
        }), KeyBindingHint.of("m <CHAR>", "Marks", "set mark")));
    }

    private void installLeaderMoveBindings(Window window, String leader) {
        _rootResponder.addEventResponder(new MotionResponder(leader + " h",
                count -> indentCurrentLines(window, -1, count)));
        _rootResponder.addEventResponder(new MotionResponder(leader + " j",
                count -> moveCurrentLines(window, 1, count)));
        _rootResponder.addEventResponder(new MotionResponder(leader + " k",
                count -> moveCurrentLines(window, -1, count)));
        _rootResponder.addEventResponder(new MotionResponder(leader + " l",
                count -> indentCurrentLines(window, 1, count)));
        _rootResponder.addKeyBindingHint(leader + " h", "Editing", "outdent line");
        _rootResponder.addKeyBindingHint(leader + " j", "Editing", "move line down");
        _rootResponder.addKeyBindingHint(leader + " k", "Editing", "move line up");
        _rootResponder.addKeyBindingHint(leader + " l", "Editing", "indent line");
    }

    private void installLeaderWorkspaceBindings(Window window, String leader) {
        _rootResponder.addEventResponder(leader + " f", "Workspace", "project files", () -> {
            allow("project file list");
            if (window.isShowingList()) {
                window.hideList();
            } else {
                window.showList(FileIndex.createFileList(), "Project Files");
            }
        });
        _rootResponder.addEventResponder(leader + " /", "Search", "project grep", "grep", () -> {
            allow("project search panel");
            ProjectSearchUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder(leader + " t", "Workspace", "Todo", "todo", () -> {
            if (window.blockEditorDriveAction("Todo workspace", "Todo is outside the editor-control sandbox")) {
                return;
            }
            TodoUiSupport.toggle(window);
        });
    }

    private void installPluginKeyBindings(Window window) {
        List<SwimPluginKeyBindingDescriptor> bindings = SwimPluginKeyBindingRegistry.listBindings();
        if (bindings.isEmpty()) {
            return;
        }
        var layer = _rootResponder.addLayer();
        for (SwimPluginKeyBindingDescriptor binding : bindings) {
            layer.addEventResponder(new PluginKeyBindingResponder(window, binding));
        }
    }

    private static final class PluginKeyBindingResponder implements EventResponder, KeyBindingHintProvider {
        private final Window _window;
        private final SwimPluginKeyBindingDescriptor _binding;
        private final TextEventResponder _responder;

        private PluginKeyBindingResponder(Window window, SwimPluginKeyBindingDescriptor binding) {
            _window = window;
            _binding = binding;
            _responder = new TextEventResponder(binding.key(), this::run,
                    KeyBindingHint.of(binding.key(), binding.group(), binding.summary(), binding.commandName()));
        }

        @Override
        public Response processEvent(KeyStrokes events) {
            if (!isAvailable()) {
                return Response.NO;
            }
            return _responder.processEvent(events);
        }

        @Override
        public void respond() {
            _responder.respond();
        }

        @Override
        public List<KeyBindingHint> keyBindingHints() {
            return isAvailable() ? _responder.keyBindingHints() : List.of();
        }

        private boolean isAvailable() {
            try {
                return _binding.isAvailable();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private void run() {
            var context = _window.getBufferContext();
            var path = context == null || context.getBuffer() == null ? null : context.getBuffer().getPath();
            SwimRuntime.loadPlugin(_binding.pluginId(), path);
            if (_binding.hasAction()) {
                _binding.action().run();
            }
            if (!_binding.command().isBlank()) {
                _window.getCommandView().execute(_binding.command());
            }
        }
    }

    private void moveCurrentLines(Window window, int delta, int lineCount) {
        allow("buffer edit");
        var buffer = window.getBufferContext().getBuffer();
        int startLine = buffer.getLineIndexAt(buffer.getCursor().getPosition());
        int endLine = Math.min(buffer.getLineCount() - 1, startLine + Math.max(1, lineCount) - 1);
        var result = buffer.moveLineRangeBy(startLine, endLine, delta);
        if (result == null) {
            window.getCommandView().setMessage(delta > 0 ? "Cannot move lines down" : "Cannot move lines up");
            return;
        }
        buffer.getUndoLog().commit();
    }

    private void indentCurrentLines(Window window, int levels, int lineCount) {
        allow("buffer edit");
        var buffer = window.getBufferContext().getBuffer();
        var range = buffer.lineRangeForCount(Math.max(1, lineCount));
        buffer.indentLines(range.getStart(), range.getEnd(), levels);
        buffer.getUndoLog().commit();
    }

    private EventResponder replaceCharacterResponder(Window window) {
        return new EventResponder() {
            private int _count;
            private Character _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                _count = 1;
                _character = null;
                var sequence = keySequence(events);
                CountParse count = parseCount(sequence, 0);
                int index = count.index();
                if (index >= sequence.size() || !isCharacter(sequence.get(index), 'r')) {
                    return Response.NO;
                }
                _count = count.count();
                if (sequence.size() == index + 1) {
                    return Response.MAYBE;
                }
                var character = sequence.get(index + 1);
                if (character.getKeyType() != KeyType.Character || character.isCtrlDown() || character.isAltDown()) {
                    return Response.NO;
                }
                _character = character.getCharacter();
                return sequence.size() == index + 2 ? Response.YES : Response.NO;
            }

            @Override
            public void respond() {
                if (_character == null) {
                    return;
                }
                window.allowEditorDriveAction("replace character");
                window.beginRepeatRecording("r");
                if (window.getBufferContext().getBuffer().replaceAtCursor(_character, _count)) {
                    window.getBufferContext().getBuffer().getUndoLog().commit();
                }
                window.commitRepeatRecording();
            }
        };
    }

    private EventResponder characterFindResponder(Window window) {
        return new EventResponder() {
            private int _count;
            private Character _character;
            private boolean _forward;
            private boolean _till;
            private boolean _repeat;
            private boolean _reverseRepeat;

            @Override
            public Response processEvent(KeyStrokes events) {
                _count = 1;
                _character = null;
                _repeat = false;
                _reverseRepeat = false;
                var sequence = keySequence(events);
                CountParse count = parseCount(sequence, 0);
                int index = count.index();
                if (index >= sequence.size()) {
                    return Response.NO;
                }
                var stroke = sequence.get(index);
                if (isCharacter(stroke, ';') || isCharacter(stroke, ',')) {
                    if (_lastFindCharacter == null) {
                        return Response.NO;
                    }
                    _count = count.count();
                    _character = _lastFindCharacter;
                    _forward = _lastFindForward;
                    _till = _lastFindTill;
                    _repeat = true;
                    _reverseRepeat = isCharacter(stroke, ',');
                    return sequence.size() == index + 1 ? Response.YES : Response.NO;
                }
                if (!isCharacter(stroke, 'f') && !isCharacter(stroke, 'F')
                        && !isCharacter(stroke, 't') && !isCharacter(stroke, 'T')) {
                    return Response.NO;
                }
                _count = count.count();
                _forward = isCharacter(stroke, 'f') || isCharacter(stroke, 't');
                _till = isCharacter(stroke, 't') || isCharacter(stroke, 'T');
                if (sequence.size() == index + 1) {
                    return Response.MAYBE;
                }
                var character = sequence.get(index + 1);
                if (character.getKeyType() != KeyType.Character || character.isCtrlDown() || character.isAltDown()) {
                    return Response.NO;
                }
                _character = character.getCharacter();
                return sequence.size() == index + 2 ? Response.YES : Response.NO;
            }

            @Override
            public void respond() {
                if (_character == null) {
                    return;
                }
                boolean forward = _reverseRepeat ? !_forward : _forward;
                window.allowEditorDriveAction("find motion");
                var buffer = window.getBufferContext().getBuffer();
                int position = buffer.findCharacterOnLine(buffer.getCursor().getPosition(), _character, forward, _till, _count);
                if (position >= 0) {
                    buffer.getCursor().setPosition(position);
                    if (!_repeat) {
                        _lastFindCharacter = _character;
                        _lastFindForward = _forward;
                        _lastFindTill = _till;
                    }
                }
            }
        };
    }

    private EventResponder foldCreateResponder(Window window) {
        return new EventResponder() {
            private Range _range;

            @Override
            public Response processEvent(KeyStrokes events) {
                _range = null;
                var sequence = keySequence(events);
                CountParse count = parseCount(sequence, 0);
                int index = count.index();
                if (index >= sequence.size() || !isCharacter(sequence.get(index), 'z')) {
                    return Response.NO;
                }
                if (sequence.size() == index + 1) {
                    return Response.MAYBE;
                }
                var command = sequence.get(index + 1);
                if (isCharacter(command, 'F')) {
                    if (sequence.size() != index + 2) {
                        return Response.NO;
                    }
                    _range = window.getBufferContext().getBuffer().lineRangeForCount(count.count());
                    return Response.YES;
                }
                if (!isCharacter(command, 'f')) {
                    return Response.NO;
                }
                if (sequence.size() == index + 2) {
                    return Response.MAYBE;
                }
                var motion = parseOperatorMotion(window, sequence, index + 2, count.count(), null);
                if (motion.status() != ParseStatus.YES) {
                    return motion.status().response();
                }
                if (sequence.size() != motion.index()) {
                    return Response.NO;
                }
                _range = motion.range();
                return Response.YES;
            }

            @Override
            public void respond() {
                if (_range == null) {
                    return;
                }
                window.allowEditorDriveAction("fold");
                if (!window.getBufferContext().getBuffer().createFold(_range.getStart(), _range.getEnd())) {
                    window.getCommandView().setMessage("Cannot create fold for that range");
                }
            }
        };
    }

    private EventResponder operatorResponder(Window window) {
        return new EventResponder() {
            private OperatorExecution _execution;

            @Override
            public Response processEvent(KeyStrokes events) {
                _execution = null;
                var sequence = keySequence(events);
                CountParse operatorCount = parseCount(sequence, 0);
                OperatorParse operator = parseOperator(sequence, operatorCount.index());
                if (operator.status() != ParseStatus.YES) {
                    return operator.status().response();
                }
                if (sequence.size() == operator.index()) {
                    return Response.MAYBE;
                }
                var motion = parseOperatorMotion(window, sequence, operator.index(), operatorCount.count(), operator.operator());
                if (motion.status() != ParseStatus.YES) {
                    return motion.status().response();
                }
                if (sequence.size() != motion.index()) {
                    return Response.NO;
                }
                _execution = new OperatorExecution(operator.operator(), motion.range(), motion.lineWise(),
                        sequence.stream().map(RecordedKey::fromKeyStroke).toList());
                return Response.YES;
            }

            @Override
            public void respond() {
                if (_execution == null) {
                    return;
                }
                applyOperator(window, _execution);
            }
        };
    }

    private void applyOperator(Window window, OperatorExecution execution) {
        var buffer = window.getBufferContext().getBuffer();
        var range = execution.range();
        Character register = window.consumeSelectedRegister();
        switch (execution.operator()) {
        case DELETE -> {
            window.allowEditorDriveAction("buffer edit");
            window.beginRepeatRecording(execution.keys());
            buffer.deleteRange(range.getStart(), range.getEnd(), execution.lineWise(), register);
            buffer.getUndoLog().commit();
            window.commitRepeatRecording();
        }
        case CHANGE -> {
            window.allowEditorDriveAction("buffer edit");
            window.beginRepeatRecording(execution.keys());
            buffer.changeRange(range.getStart(), range.getEnd(), execution.lineWise(), register);
            window.switchToMode(window.getInputMode());
        }
        case YANK -> {
            window.allowEditorDriveAction("yank");
            buffer.yankRange(range.getStart(), range.getEnd(), execution.lineWise(), register);
        }
        case INDENT -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.indentLines(range.getStart(), range.getEnd(), 1);
            buffer.getUndoLog().commit();
        }
        case OUTDENT -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.indentLines(range.getStart(), range.getEnd(), -1);
            buffer.getUndoLog().commit();
        }
        case FORMAT -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.autoIndentLines(range.getStart(), range.getEnd());
            buffer.getUndoLog().commit();
        }
        case UPPER -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.transformRange(range.getStart(), range.getEnd(), execution.lineWise(), text -> text.toUpperCase(Locale.ROOT));
            buffer.getUndoLog().commit();
        }
        case LOWER -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.transformRange(range.getStart(), range.getEnd(), execution.lineWise(), text -> text.toLowerCase(Locale.ROOT));
            buffer.getUndoLog().commit();
        }
        case TOGGLE_CASE -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.transformRange(range.getStart(), range.getEnd(), execution.lineWise(), NormalMode::toggleCase);
            buffer.getUndoLog().commit();
        }
        }
    }

    private OperatorMotionParse parseOperatorMotion(Window window, List<KeyStroke> sequence, int index,
            int operatorCount, Operator operator) {
        CountParse motionCount = parseCount(sequence, index);
        int count = Math.max(1, operatorCount) * Math.max(1, motionCount.count());
        int motionIndex = motionCount.index();
        if (motionIndex >= sequence.size()) {
            return OperatorMotionParse.maybe();
        }
        var buffer = window.getBufferContext().getBuffer();
        int start = buffer.getCursor().getPosition();
        KeyStroke first = sequence.get(motionIndex);

        if (operator != null && isRepeatedOperator(operator, first, sequence, motionIndex)) {
            var range = buffer.lineRangeForCount(count);
            return OperatorMotionParse.yes(motionIndex + repeatedOperatorLength(operator, sequence, motionIndex), range, true);
        }

        if (isCharacter(first, 'i') || isCharacter(first, 'a')) {
            if (sequence.size() == motionIndex + 1) {
                return OperatorMotionParse.maybe();
            }
            var object = sequence.get(motionIndex + 1);
            if (object.getKeyType() != KeyType.Character || object.isCtrlDown() || object.isAltDown()) {
                return OperatorMotionParse.no();
            }
            var range = buffer.textObjectRange(Character.toString(object.getCharacter()), isCharacter(first, 'a'));
            if (range == null || range.getLength() <= 0) {
                return OperatorMotionParse.no();
            }
            return OperatorMotionParse.yes(motionIndex + 2, range, false);
        }

        if (isCharacter(first, 'g')) {
            if (sequence.size() == motionIndex + 1) {
                return OperatorMotionParse.maybe();
            }
            var second = sequence.get(motionIndex + 1);
            if (isCharacter(second, 'g')) {
                var range = buffer.lineRangeForPositions(start, 0);
                return OperatorMotionParse.yes(motionIndex + 2, range, true);
            }
            return OperatorMotionParse.no();
        }

        if (isCharacter(first, '/') || isCharacter(first, '?')) {
            SearchMotion search = parseSearchMotion(sequence, motionIndex);
            if (search.status() != ParseStatus.YES) {
                return OperatorMotionParse.status(search.status());
            }
            int target = findSearchTarget(buffer.getString(), start, search.pattern(), isCharacter(first, '/'));
            if (target < 0) {
                return OperatorMotionParse.no();
            }
            return OperatorMotionParse.yes(search.index(), Range.create(start, target), false);
        }

        if (isCharacter(first, ';') || isCharacter(first, ',')) {
            if (_lastFindCharacter == null) {
                return OperatorMotionParse.no();
            }
            boolean forward = isCharacter(first, ',') ? !_lastFindForward : _lastFindForward;
            int found = buffer.findCharacterOnLine(start, _lastFindCharacter, forward, _lastFindTill, count);
            if (found < 0) {
                return OperatorMotionParse.no();
            }
            int end = forward ? found + 1 : found;
            return OperatorMotionParse.yes(motionIndex + 1,
                    Range.create(Math.min(start, found), Math.max(start, end)), false);
        }

        if (isCharacter(first, 'f') || isCharacter(first, 'F') || isCharacter(first, 't') || isCharacter(first, 'T')) {
            if (sequence.size() == motionIndex + 1) {
                return OperatorMotionParse.maybe();
            }
            var character = sequence.get(motionIndex + 1);
            if (character.getKeyType() != KeyType.Character || character.isCtrlDown() || character.isAltDown()) {
                return OperatorMotionParse.no();
            }
            boolean forward = isCharacter(first, 'f') || isCharacter(first, 't');
            boolean till = isCharacter(first, 't') || isCharacter(first, 'T');
            int found = buffer.findCharacterOnLine(start, character.getCharacter(), forward, till, count);
            if (found < 0) {
                return OperatorMotionParse.no();
            }
            _lastFindCharacter = character.getCharacter();
            _lastFindForward = forward;
            _lastFindTill = till;
            int begin = found;
            int end = forward ? found + 1 : found;
            return OperatorMotionParse.yes(motionIndex + 2,
                    Range.create(Math.min(start, begin), Math.max(start, end)), false);
        }

        MotionTarget target = motionTarget(buffer, first, start, count);
        if (target == null) {
            return OperatorMotionParse.no();
        }
        return OperatorMotionParse.yes(motionIndex + target.consumed(), target.range(), target.lineWise());
    }

    private MotionTarget motionTarget(org.fisk.swim.text.Buffer buffer, KeyStroke stroke, int start, int count) {
        int target;
        if (isCharacter(stroke, 'h')) {
            return MotionTarget.character(Range.create(Math.max(0, start - count), start), 1);
        }
        if (isCharacter(stroke, 'l')) {
            return MotionTarget.character(Range.create(start, Math.min(buffer.getLength(), start + count)), 1);
        }
        if (isCharacter(stroke, 'j')) {
            int line = Math.min(buffer.getLineCount() - 1, buffer.getLineIndexAt(start) + count);
            return MotionTarget.line(buffer.lineRangeForPositions(start, buffer.getLineStartByIndex(line)), 1);
        }
        if (isCharacter(stroke, 'k')) {
            int line = Math.max(0, buffer.getLineIndexAt(start) - count);
            return MotionTarget.line(buffer.lineRangeForPositions(start, buffer.getLineStartByIndex(line)), 1);
        }
        if (isCharacter(stroke, '0')) {
            return MotionTarget.character(Range.create(buffer.getLineStartPosition(start), start), 1);
        }
        if (isCharacter(stroke, '^') || isCharacter(stroke, '_')) {
            int lineStart = buffer.getLineStartPosition(start);
            int lineEnd = buffer.getLineEndPosition(start, false);
            target = lineStart;
            while (target < lineEnd && (buffer.getCharacter(target).equals(" ") || buffer.getCharacter(target).equals("\t"))) {
                target++;
            }
            return MotionTarget.character(Range.create(Math.min(start, target), Math.max(start, target)), 1);
        }
        if (isCharacter(stroke, '$')) {
            return MotionTarget.character(Range.create(start, buffer.getLineEndPosition(start, false)), 1);
        }
        if (isCharacter(stroke, 'w') || isCharacter(stroke, 'W')) {
            target = buffer.nextWordPosition(start, count, isCharacter(stroke, 'W'));
            return MotionTarget.character(Range.create(start, target), 1);
        }
        if (isCharacter(stroke, 'e') || isCharacter(stroke, 'E')) {
            target = buffer.wordEndPosition(start, count, isCharacter(stroke, 'E'));
            return MotionTarget.character(Range.create(start, Math.min(buffer.getLength(), target + 1)), 1);
        }
        if (isCharacter(stroke, 'b') || isCharacter(stroke, 'B')) {
            target = buffer.previousWordPosition(start, count, isCharacter(stroke, 'B'));
            return MotionTarget.character(Range.create(target, start), 1);
        }
        if (isCharacter(stroke, '}')) {
            target = buffer.paragraphForwardPosition(start, count);
            return MotionTarget.character(Range.create(start, target), 1);
        }
        if (isCharacter(stroke, '{')) {
            target = buffer.paragraphBackwardPosition(start, count);
            return MotionTarget.character(Range.create(target, start), 1);
        }
        if (isCharacter(stroke, ')')) {
            target = buffer.sentenceForwardPosition(start, count);
            return MotionTarget.character(Range.create(start, target), 1);
        }
        if (isCharacter(stroke, '(')) {
            target = buffer.sentenceBackwardPosition(start, count);
            return MotionTarget.character(Range.create(target, start), 1);
        }
        if (isCharacter(stroke, '%')) {
            target = buffer.matchingBracketPosition(start);
            if (target < 0) {
                return null;
            }
            return MotionTarget.character(Range.create(Math.min(start, target), Math.min(buffer.getLength(), Math.max(start, target) + 1)), 1);
        }
        if (isCharacter(stroke, 'G')) {
            int line = count <= 1 ? buffer.getLineCount() - 1 : Math.min(buffer.getLineCount() - 1, count - 1);
            return MotionTarget.line(buffer.lineRangeForPositions(start, buffer.getLineStartByIndex(line)), 1);
        }
        if (isCharacter(stroke, 'f') || isCharacter(stroke, 'F') || isCharacter(stroke, 't') || isCharacter(stroke, 'T')) {
            return null;
        }
        return null;
    }

    private SearchMotion parseSearchMotion(List<KeyStroke> sequence, int index) {
        var query = new StringBuilder();
        for (int i = index + 1; i < sequence.size(); i++) {
            var stroke = sequence.get(i);
            if (stroke.getKeyType() == KeyType.Enter) {
                try {
                    return new SearchMotion(ParseStatus.YES, i + 1, Pattern.compile(query.toString()));
                } catch (PatternSyntaxException e) {
                    return new SearchMotion(ParseStatus.NO, i + 1, null);
                }
            }
            if (stroke.getKeyType() != KeyType.Character || stroke.isCtrlDown() || stroke.isAltDown()) {
                return new SearchMotion(ParseStatus.NO, i + 1, null);
            }
            query.append(stroke.getCharacter());
        }
        return new SearchMotion(ParseStatus.MAYBE, sequence.size(), null);
    }

    private static int findSearchTarget(String text, int start, Pattern pattern, boolean forward) {
        var matcher = pattern.matcher(text);
        if (forward) {
            return matcher.find(Math.min(text.length(), start + 1)) ? matcher.start() : -1;
        }
        int last = -1;
        while (matcher.find()) {
            if (matcher.start() < start) {
                last = matcher.start();
            } else {
                break;
            }
        }
        return last;
    }

    private OperatorParse parseOperator(List<KeyStroke> sequence, int index) {
        if (index >= sequence.size()) {
            return new OperatorParse(ParseStatus.NO, index, null);
        }
        var first = sequence.get(index);
        if (isCharacter(first, 'd')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.DELETE);
        }
        if (isCharacter(first, 'c')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.CHANGE);
        }
        if (isCharacter(first, 'y')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.YANK);
        }
        if (isCharacter(first, '>')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.INDENT);
        }
        if (isCharacter(first, '<')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.OUTDENT);
        }
        if (isCharacter(first, '=')) {
            return new OperatorParse(ParseStatus.YES, index + 1, Operator.FORMAT);
        }
        if (isCharacter(first, 'g')) {
            if (sequence.size() == index + 1) {
                return new OperatorParse(ParseStatus.MAYBE, index, null);
            }
            var second = sequence.get(index + 1);
            if (isCharacter(second, 'U')) {
                return new OperatorParse(ParseStatus.YES, index + 2, Operator.UPPER);
            }
            if (isCharacter(second, 'u')) {
                return new OperatorParse(ParseStatus.YES, index + 2, Operator.LOWER);
            }
            if (isCharacter(second, '~')) {
                return new OperatorParse(ParseStatus.YES, index + 2, Operator.TOGGLE_CASE);
            }
        }
        return new OperatorParse(ParseStatus.NO, index, null);
    }

    private static boolean isRepeatedOperator(Operator operator, KeyStroke first, List<KeyStroke> sequence, int index) {
        return switch (operator) {
        case DELETE -> isCharacter(first, 'd');
        case CHANGE -> isCharacter(first, 'c');
        case YANK -> isCharacter(first, 'y');
        case INDENT -> isCharacter(first, '>');
        case OUTDENT -> isCharacter(first, '<');
        case FORMAT -> isCharacter(first, '=');
        case UPPER -> sequence.size() > index + 1 && isCharacter(first, 'g') && isCharacter(sequence.get(index + 1), 'U')
                || isCharacter(first, 'U');
        case LOWER -> sequence.size() > index + 1 && isCharacter(first, 'g') && isCharacter(sequence.get(index + 1), 'u')
                || isCharacter(first, 'u');
        case TOGGLE_CASE -> sequence.size() > index + 1 && isCharacter(first, 'g') && isCharacter(sequence.get(index + 1), '~')
                || isCharacter(first, '~');
        };
    }

    private static int repeatedOperatorLength(Operator operator, List<KeyStroke> sequence, int index) {
        return switch (operator) {
        case UPPER, LOWER, TOGGLE_CASE -> index < sequence.size() && isCharacter(sequence.get(index), 'g') ? 2 : 1;
        default -> 1;
        };
    }

    private static CountParse parseCount(List<KeyStroke> sequence, int index) {
        int current = index;
        int count = 0;
        if (current < sequence.size() && isDigit(sequence.get(current), '1', '9')) {
            while (current < sequence.size() && isDigit(sequence.get(current), '0', '9')) {
                count = count * 10 + sequence.get(current).getCharacter() - '0';
                current++;
            }
        }
        return new CountParse(count == 0 ? 1 : count, current);
    }

    private static List<KeyStroke> keySequence(KeyStrokes events) {
        var sequence = new ArrayList<KeyStroke>();
        for (var keyStroke : events) {
            sequence.add(keyStroke);
        }
        return sequence;
    }

    private static boolean isCharacter(KeyStroke stroke, char character) {
        return stroke != null
                && stroke.getKeyType() == KeyType.Character
                && !stroke.isCtrlDown()
                && !stroke.isAltDown()
                && stroke.getCharacter() == character;
    }

    private static boolean isDigit(KeyStroke stroke, char first, char last) {
        return stroke != null
                && stroke.getKeyType() == KeyType.Character
                && !stroke.isCtrlDown()
                && !stroke.isAltDown()
                && stroke.getCharacter() >= first
                && stroke.getCharacter() <= last;
    }

    private static String toggleCase(String text) {
        var result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isUpperCase(character)) {
                result.append(Character.toLowerCase(character));
            } else if (Character.isLowerCase(character)) {
                result.append(Character.toUpperCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private enum Operator {
        DELETE,
        CHANGE,
        YANK,
        INDENT,
        OUTDENT,
        FORMAT,
        UPPER,
        LOWER,
        TOGGLE_CASE
    }

    private enum ParseStatus {
        YES,
        MAYBE,
        NO;

        Response response() {
            return switch (this) {
            case YES -> Response.YES;
            case MAYBE -> Response.MAYBE;
            case NO -> Response.NO;
            };
        }
    }

    private record CountParse(int count, int index) {
    }

    private record OperatorParse(ParseStatus status, int index, Operator operator) {
    }

    private record OperatorExecution(Operator operator, Range range, boolean lineWise, List<RecordedKey> keys) {
    }

    private record OperatorMotionParse(ParseStatus status, int index, Range range, boolean lineWise) {
        static OperatorMotionParse yes(int index, Range range, boolean lineWise) {
            return new OperatorMotionParse(ParseStatus.YES, index, range, lineWise);
        }

        static OperatorMotionParse maybe() {
            return new OperatorMotionParse(ParseStatus.MAYBE, -1, null, false);
        }

        static OperatorMotionParse no() {
            return new OperatorMotionParse(ParseStatus.NO, -1, null, false);
        }

        static OperatorMotionParse status(ParseStatus status) {
            return new OperatorMotionParse(status, -1, null, false);
        }
    }

    private record MotionTarget(Range range, boolean lineWise, int consumed) {
        static MotionTarget character(Range range, int consumed) {
            return new MotionTarget(range, false, consumed);
        }

        static MotionTarget line(Range range, int consumed) {
            return new MotionTarget(range, true, consumed);
        }
    }

    private record SearchMotion(ParseStatus status, int index, Pattern pattern) {
    }

    private static boolean goFoldStart(Window window, boolean forward, int count) {
        var buffer = window.getBufferContext().getBuffer();
        int position = buffer.getCursor().getPosition();
        int target = forward
                ? buffer.nextFoldStartPosition(position, count)
                : buffer.previousFoldStartPosition(position, count);
        if (target < 0) {
            return false;
        }
        buffer.getCursor().setPosition(target);
        return true;
    }

    private void announceIfUnmoved(boolean changed, String message) {
        if (!changed) {
            _window.getCommandView().setMessage(message);
        }
    }

    private static void repeat(int count, java.util.function.BooleanSupplier action) {
        for (int i = 0; i < count; i++) {
            if (!action.getAsBoolean()) {
                break;
            }
        }
    }

    private static EventResponder prefixCharacterResponder(String prefix, BiConsumer<Integer, Character> responder) {
        return new EventResponder() {
            private int _count;
            private Character _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                _count = 1;
                _character = null;
                var sequence = new java.util.ArrayList<com.googlecode.lanterna.input.KeyStroke>();
                for (var keyStroke : events) {
                    sequence.add(keyStroke);
                }
                if (sequence.isEmpty()) {
                    return Response.NO;
                }
                int index = 0;
                StringBuilder digits = new StringBuilder();
                while (index < sequence.size()) {
                    var stroke = sequence.get(index);
                    if (stroke.getKeyType() != com.googlecode.lanterna.input.KeyType.Character
                            || !Character.isDigit(stroke.getCharacter())) {
                        break;
                    }
                    digits.append(stroke.getCharacter());
                    index++;
                }
                var prefixKeys = prefix.split(" ");
                int availablePrefixKeys = Math.min(prefixKeys.length, sequence.size() - index);
                for (int i = 0; i < availablePrefixKeys; i++) {
                    var expected = new TextEventResponder(prefixKeys[i], () -> {
                    });
                    var slice = new java.util.ArrayList<com.googlecode.lanterna.input.KeyStroke>();
                    slice.add(sequence.get(index + i));
                    if (expected.processEvent(new KeyStrokes(slice)) != Response.YES) {
                        return Response.NO;
                    }
                }
                if (sequence.size() < index + prefixKeys.length) {
                    return Response.MAYBE;
                }
                int argIndex = index + prefixKeys.length;
                if (sequence.size() == argIndex) {
                    return Response.MAYBE;
                }
                var argument = sequence.get(argIndex);
                if (argument.getKeyType() != com.googlecode.lanterna.input.KeyType.Character || argument.isCtrlDown()
                        || argument.isAltDown()) {
                    return Response.NO;
                }
                if (digits.length() > 0) {
                    _count = Integer.parseInt(digits.toString());
                }
                _character = argument.getCharacter();
                return sequence.size() == argIndex + 1 ? Response.YES : Response.NO;
            }

            @Override
            public void respond() {
                if (_character != null) {
                    responder.accept(_count, _character);
                }
            }
        };
    }

    private static EventResponder registerResponder(BiConsumer<Integer, Character> responder) {
        return prefixCharacterResponder("\"", responder);
    }

    private static EventResponder markJumpResponder(String prefix, boolean lineWise, Window window) {
        return prefixCharacterResponder(prefix, (ignored, mark) -> {
            window.allowEditorDriveAction("mark jump");
            window.jumpToMark(mark, lineWise);
        });
    }

    private static EventResponder macroResponder(Window window) {
        return new EventResponder() {
            private Character _register;
            private boolean _stop;

            @Override
            public Response processEvent(KeyStrokes events) {
                _register = null;
                _stop = false;
                var sequence = new java.util.ArrayList<com.googlecode.lanterna.input.KeyStroke>();
                for (var keyStroke : events) {
                    sequence.add(keyStroke);
                }
                if (sequence.isEmpty()) {
                    return Response.NO;
                }
                var first = sequence.getFirst();
                if (first.getKeyType() != com.googlecode.lanterna.input.KeyType.Character || first.getCharacter() != 'q'
                        || first.isCtrlDown() || first.isAltDown()) {
                    return Response.NO;
                }
                if (window.isRecordingMacro()) {
                    if (sequence.size() == 1) {
                        _stop = true;
                        return Response.YES;
                    }
                    return Response.NO;
                }
                if (sequence.size() == 1) {
                    return Response.MAYBE;
                }
                var second = sequence.get(1);
                if (second.getKeyType() != com.googlecode.lanterna.input.KeyType.Character || second.isCtrlDown()
                        || second.isAltDown()) {
                    return Response.NO;
                }
                _register = second.getCharacter();
                return sequence.size() == 2 ? Response.YES : Response.NO;
            }

            @Override
            public void respond() {
                if (window.blockEditorDriveAction("macro", "macros are outside the editor-control sandbox")) {
                    return;
                }
                if (_stop) {
                    window.stopMacroRecording();
                } else if (_register != null) {
                    window.startMacroRecording(_register);
                }
            }
        };
    }

    private static EventResponder ctrlWMotion(String motion, String summary, MotionResponder.Responder responder) {
        EventResponder delegate = new EventResponder() {
            private final TextEventResponder _prefix = new TextEventResponder("<CTRL>-w", () -> {
            });
            private final MotionResponder _motion = new MotionResponder(motion, responder);
            private boolean _handled;

            @Override
            public Response processEvent(KeyStrokes events) {
                _handled = false;
                var prefixResponse = _prefix.processEvent(events);
                if (prefixResponse != Response.YES) {
                    return prefixResponse;
                }
                if (events.consumed()) {
                    return Response.MAYBE;
                }
                var motionResponse = _motion.processEvent(events);
                _handled = motionResponse == Response.YES;
                return motionResponse;
            }

            @Override
            public void respond() {
                if (_handled) {
                    _motion.respond();
                }
            }
        };
        return hinted(delegate, KeyBindingHint.of("<CTRL>-w " + motion, "Panes", summary));
    }

    private static EventResponder hinted(EventResponder delegate, KeyBindingHint... hints) {
        return new HintedResponder(delegate, List.of(hints));
    }

    private record HintedResponder(EventResponder delegate, List<KeyBindingHint> hints)
            implements EventResponder, KeyBindingHintProvider {
        @Override
        public Response processEvent(KeyStrokes events) {
            return delegate.processEvent(events);
        }

        @Override
        public void respond() {
            delegate.respond();
        }

        @Override
        public List<KeyBindingHint> keyBindingHints() {
            return hints;
        }
    }

    private enum ShellTarget {
        WORKSPACE,
        VERTICAL_SPLIT,
        HORIZONTAL_SPLIT
    }

    private enum TextObjectOperator {
        DELETE,
        CHANGE,
        YANK
    }

    private void startShell(Window window, ShellTarget target) {
        if (window.blockEditorDriveAction("shell workspace", "opening shell input through drive_editor is not allowed")) {
            return;
        }
        boolean opened = switch (target) {
        case WORKSPACE -> window.showShellWorkspace();
        case VERTICAL_SPLIT -> window.showShellSplitHorizontally();
        case HORIZONTAL_SPLIT -> window.showShellSplitVertically();
        };
        if (!opened) {
            window.getCommandView().setMessage("Failed to start shell workspace");
        }
    }

    @Override
    public void activate() {
        _window.getBufferContext().getBuffer().getUndoLog().commit();
    }
    
    @Override
    public AttributedString decorate(Glyph glyph, AttributedString character) {
        character = _fancyWordJump.decorate(glyph, character);
        character = _fancyCharacterJump.decorate(glyph, character);
        return character;
    }

    private void installTextObjectResponder(Window window, String pattern, String object, boolean around, TextObjectOperator operator) {
        _rootResponder.addEventResponder(pattern, "Editing", textObjectSummary(object, around),
                () -> applyTextObject(window, pattern, object, around, operator));
    }

    private static String textObjectSummary(String object, boolean around) {
        String name = switch (object) {
        case "(" -> "parentheses";
        case "[" -> "brackets";
        case "{" -> "braces";
        case "\"" -> "double quotes";
        case "'" -> "single quotes";
        case "p" -> "paragraph";
        default -> object;
        };
        return (around ? "around " : "inside ") + name;
    }

    private void applyTextObject(Window window, String pattern, String object, boolean around, TextObjectOperator operator) {
        var buffer = window.getBufferContext().getBuffer();
        var range = buffer.textObjectRange(object, around);
        if (range == null || range.getLength() <= 0) {
            window.getCommandView().setMessage("Text object not found");
            return;
        }
        switch (operator) {
        case DELETE -> {
            window.allowEditorDriveAction("buffer edit");
            window.beginRepeatRecording(pattern);
            buffer.deleteRange(range.getStart(), range.getEnd(), false, window.consumeSelectedRegister());
            buffer.getUndoLog().commit();
            window.commitRepeatRecording();
        }
        case CHANGE -> {
            window.allowEditorDriveAction("buffer edit");
            window.beginRepeatRecording(pattern);
            buffer.changeRange(range.getStart(), range.getEnd(), false, window.consumeSelectedRegister());
            window.switchToMode(window.getInputMode());
        }
        case YANK -> {
            window.allowEditorDriveAction("yank");
            Copy.getInstance().setYank(
                    buffer.getSubstring(range.getStart(), range.getEnd()),
                    false,
                    window.consumeSelectedRegister());
        }
        }
    }
}
