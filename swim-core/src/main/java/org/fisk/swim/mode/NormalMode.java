package org.fisk.swim.mode;

import java.util.function.BiConsumer;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.debug.DebuggerManager;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.FancyJumpResponder;
import org.fisk.swim.fileindex.FileIndex;
import org.fisk.swim.lsp.cpp.ClangdLspPluginSupport;
import org.fisk.swim.lsp.java.JavaLspPluginSupport;
import org.fisk.swim.mail.MailUiSupport;
import org.fisk.swim.nemo.NemoClient;
import org.fisk.swim.slack.SlackUiSupport;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.ProjectSearchUiSupport;
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
        JavaLspPluginSupport.installNormalModeBindings(this, window, leader);
        ClangdLspPluginSupport.installNormalModeBindings(this, window);
        _rootResponder.addEventResponder("i", () -> {
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
        _rootResponder.addEventResponder("v", allowed("enter visual mode", () -> { window.switchToMode(window.getVisualMode()); }));
        _rootResponder.addEventResponder("V", allowed("enter visual line mode", () -> { window.switchToMode(window.getVisualLineMode()); }));
        _rootResponder.addEventResponder("<CTRL>-v", allowed("enter visual block mode", () -> { window.switchToMode(window.getVisualBlockMode()); }));
        _rootResponder.addEventResponder("<CTRL>-w s", allowed("split buffer", () -> { window.splitActiveBufferVertically(); }));
        _rootResponder.addEventResponder("<CTRL>-w v", allowed("split buffer", () -> { window.splitActiveBufferHorizontally(); }));
        _rootResponder.addEventResponder("<CTRL>-w h", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.LEFT), "No pane to the left"); }));
        _rootResponder.addEventResponder("<CTRL>-w j", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.DOWN), "No pane below"); }));
        _rootResponder.addEventResponder("<CTRL>-w k", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.UP), "No pane above"); }));
        _rootResponder.addEventResponder("<CTRL>-w l", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusView(Window.Direction.RIGHT), "No pane to the right"); }));
        _rootResponder.addEventResponder("<CTRL>-w w", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusNextView(), "No other pane"); }));
        _rootResponder.addEventResponder("<CTRL>-w W", allowed("focus buffer split", () -> { announceIfUnmoved(window.focusPreviousView(), "No other pane"); }));
        _rootResponder.addEventResponder("<CTRL>-w q", allowed("close buffer split", () -> { announceIfUnmoved(window.closeActiveView(), "Cannot close the last buffer view"); }));
        _rootResponder.addEventResponder("<CTRL>-w o", allowed("close buffer splits", () -> { announceIfUnmoved(window.closeOtherViews(), "No other panes to close"); }));
        _rootResponder.addEventResponder(ctrlWMotion(">", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewWidth(4 * count), "No vertical split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("<", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewWidth(-4 * count), "No vertical split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("+", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewHeight(2 * count), "No horizontal split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("-", count ->
                { allow("resize buffer split"); announceIfUnmoved(window.resizeActiveViewHeight(-2 * count), "No horizontal split to resize"); }));
        _rootResponder.addEventResponder(ctrlWMotion("=", ignored ->
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
        _rootResponder.addEventResponder("<CTRL>-g c w", () -> { startShell(window, ShellTarget.WORKSPACE); });
        _rootResponder.addEventResponder("<CTRL>-g c v", () -> { startShell(window, ShellTarget.VERTICAL_SPLIT); });
        _rootResponder.addEventResponder("<CTRL>-g c h", () -> { startShell(window, ShellTarget.HORIZONTAL_SPLIT); });
        _rootResponder.addEventResponder(registerResponder((ignored, register) -> { allow("select register"); window.selectRegister(register); }));
        _rootResponder.addEventResponder(prefixCharacterResponder("g m", (ignored, mark) -> { allow("set mark"); window.setMark(mark); }));
        _rootResponder.addEventResponder(markJumpResponder("'", true, window));
        _rootResponder.addEventResponder(markJumpResponder("`", false, window));
        _rootResponder.addEventResponder(macroResponder(window));
        _rootResponder.addEventResponder("g n", allowed("multicursor", () -> { announceIfUnmoved(window.addNextCursorForCurrentWord(true), "No next match for multicursor"); }));
        _rootResponder.addEventResponder("g N", allowed("multicursor", () -> { announceIfUnmoved(window.addNextCursorForCurrentWord(false), "No previous match for multicursor"); }));
        _rootResponder.addEventResponder("g C", allowed("multicursor", window::clearAdditionalCursors));
        _rootResponder.addEventResponder("g ]", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(true, false); }));
        _rootResponder.addEventResponder("g [", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(false, false); }));
        _rootResponder.addEventResponder("g }", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(true, true); }));
        _rootResponder.addEventResponder("g {", allowed("diagnostic navigation", () -> { window.navigateDiagnostic(false, true); }));
        _rootResponder.addEventResponder("g x", allowed("diagnostic popup", () -> { window.showDiagnosticsForCurrentLine(true); }));
        _rootResponder.addEventResponder("g a", allowed("code actions", () -> { window.showCodeActionsForCurrentLine(); }));
        _rootResponder.addEventResponder(prefixCharacterResponder("@", (ignored, register) -> {
            if (window.blockEditorDriveAction("macro playback", "macros are outside the editor-control sandbox")) {
                return;
            }
            if (register == '@') {
                window.playLastMacro(1);
            } else {
                window.playMacro(register, 1);
            }
        }));
        _rootResponder.addEventResponder(new MotionResponder(".", count -> { allow("repeat edit"); window.repeatLastEdit(count); }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-o", count -> { allow("jump navigation"); repeat(count, window::jumpBack); }));
        _rootResponder.addEventResponder(new MotionResponder("<TAB>", count -> { allow("jump navigation"); repeat(count, window::jumpForward); }));
        _rootResponder.addEventResponder("z a", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().toggleFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z c", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().closeFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z o", allowed("fold", () -> { announceIfUnmoved(window.getBufferContext().getBuffer().openFoldAt(
                window.getBufferContext().getBuffer().getCursor().getPosition()), "No fold at cursor"); }));
        _rootResponder.addEventResponder("z M", allowed("fold", () -> { window.getBufferContext().getBuffer().closeAllFolds(); }));
        _rootResponder.addEventResponder("z R", allowed("fold", () -> { window.getBufferContext().getBuffer().openAllFolds(); }));
        _rootResponder.addEventResponder("u", allowed("undo", () -> { window.getBufferContext().getBuffer().undo(); }));
        _rootResponder.addEventResponder("<CTRL>-r", allowed("redo", () -> {window.getBufferContext().getBuffer().redo(); }));
        _rootResponder.addEventResponder("d i w", () -> {
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
        _rootResponder.addEventResponder("d w", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("d w");
            activeBuffer.deleteWord();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("d d", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("d d");
            activeBuffer.deleteLine();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("x", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("x");
            activeBuffer.removeAt();
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("c i w", () -> {
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
        _rootResponder.addEventResponder("c w", () -> {
            allow("buffer edit");
            window.beginRepeatRecording("c w");
            window.getBufferContext().getBuffer().deleteWord();
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("a", () -> {
            allow("enter input mode");
            window.beginRepeatRecording("a");
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goRight();
        });
        _rootResponder.addEventResponder("A", () -> {
            allow("enter input mode");
            window.beginRepeatRecording("A");
            window.switchToMode(window.getInputMode());
            window.getBufferContext().getBuffer().getCursor().goEndOfLine();
        });
        _rootResponder.addEventResponder("o", () -> {
            allow("buffer edit");
            var cursor = window.getBufferContext().getBuffer().getCursor();
            var activeBuffer = window.getBufferContext().getBuffer();
            window.beginRepeatRecording("o");
            cursor.goEndOfLine();
            window.switchToMode(window.getInputMode());
            activeBuffer.insert("\n");
        });
        _rootResponder.addEventResponder("O", () -> {
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
        _rootResponder.addEventResponder("p", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            var value = Copy.getInstance().getValue(window.consumeSelectedRegister());
            window.beginRepeatRecording("p");
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
        _rootResponder.addEventResponder("P", () -> {
            allow("buffer edit");
            var activeBuffer = window.getBufferContext().getBuffer();
            var cursor = activeBuffer.getCursor();
            var value = Copy.getInstance().getValue(window.consumeSelectedRegister());
            window.beginRepeatRecording("P");
            if (value.isLine()) {
                cursor.goStartOfLine();
                activeBuffer.insert(value.text());
            } else {
                activeBuffer.insert(value.text());
            }
            activeBuffer.getUndoLog().commit();
            window.commitRepeatRecording();
        });
        _rootResponder.addEventResponder("y y", () -> {
            allow("yank");
            var text = window.getBufferContext().getBuffer().getCurrentLineText();
            Copy.getInstance().setText(text, true /* isLine */, window.consumeSelectedRegister());
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
        _rootResponder.addEventResponder("m", () -> {
            allow("project file list");
            if (window.isShowingList()) {
                window.hideList();
            } else {
                window.showList(FileIndex.createFileList(), "Project Files");
            }
        });
        _rootResponder.addEventResponder("M", () -> {
            allow("project search panel");
            ProjectSearchUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder("B", () -> {
            if (window.blockEditorDriveAction("debug breakpoint", "debugger actions are outside the editor-control sandbox")) {
                return;
            }
            try {
                DebuggerManager.toggleBreakpointAtCursor();
            } catch (Exception e) {
                window.getCommandView().setMessage(e.getMessage() == null ? "Failed to toggle breakpoint" : e.getMessage());
            }
        });
        _rootResponder.addEventResponder("e", () -> {
            if (window.blockEditorDriveAction("mail workspace", "email is confidential and unavailable to Nemo")) {
                return;
            }
            MailUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder("s", () -> {
            if (window.blockEditorDriveAction("Slack workspace", "Slack is outside the editor-control sandbox")) {
                return;
            }
            SlackUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder("t", () -> {
            if (window.blockEditorDriveAction("Todo workspace", "Todo is outside the editor-control sandbox")) {
                return;
            }
            TodoUiSupport.toggle(window);
        });
        _rootResponder.addEventResponder(":", () -> {
            window.getCommandView().activate(":");
        });
        _rootResponder.addEventResponder("!", () -> {
            if (window.blockEditorDriveAction("Nemo chat", "opening Nemo from editor control is not allowed")) {
                return;
            }
            NemoClient.getInstance().run(window.getBufferContext(), "");
        });
        _rootResponder.addEventResponder(">", () -> {
            if (window.blockEditorDriveAction("shell panel", "opening shell input through drive_editor is not allowed")) {
                return;
            }
            if (!window.showShellPanel()) {
                window.getCommandView().setMessage("Failed to start shell");
            }
        });
        _rootResponder.addEventResponder("*", () -> {
            allow("search current word");
            var word = window.getBufferContext().getBuffer().getInnerWord();
            if (word != null && !word.equals("")) {
                window.getCommandView().activate("/");
                window.getCommandView().runSearch(word);
                window.getCommandView().deactivate();
            }
        });
        _rootResponder.addEventResponder("#", () -> {
            allow("search current word");
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
            allow("search next");
            window.getCommandView().searchNext();
        });
        _rootResponder.addEventResponder("N", () -> {
            allow("search previous");
            window.getCommandView().searchPrevious();
        });
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

    private static EventResponder ctrlWMotion(String motion, MotionResponder.Responder responder) {
        return new EventResponder() {
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
        _rootResponder.addEventResponder(pattern, () -> applyTextObject(window, pattern, object, around, operator));
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
            buffer.remove(range.getStart(), range.getEnd());
            buffer.getUndoLog().commit();
            window.commitRepeatRecording();
        }
        case CHANGE -> {
            window.allowEditorDriveAction("buffer edit");
            window.beginRepeatRecording(pattern);
            buffer.remove(range.getStart(), range.getEnd());
            window.switchToMode(window.getInputMode());
        }
        case YANK -> {
            window.allowEditorDriveAction("yank");
            Copy.getInstance().setText(
                    buffer.getSubstring(range.getStart(), range.getEnd()),
                    false,
                    window.consumeSelectedRegister());
        }
        }
    }
}
