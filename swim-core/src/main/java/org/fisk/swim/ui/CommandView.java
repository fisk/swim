package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.debug.DebuggerManager;
import org.fisk.swim.debug.DebuggerUiSupport;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.mail.MailUiSupport;
import org.fisk.swim.slack.SlackUiSupport;
import org.fisk.swim.session.SwimServerSession;
import org.fisk.swim.session.SwimServerSessions;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.todo.TodoUiSupport;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class CommandView extends View {
    private static final class CommandCursor extends Cursor {
        private final CommandView _owner;

        private CommandCursor(CommandView owner) {
            super(null);
            _owner = owner;
        }

        @Override
        public int getXOnScreen() {
            return _owner.cursorScreenPosition().getX();
        }

        @Override
        public int getYOnScreen() {
            return _owner.cursorScreenPosition().getY();
        }

        @Override
        public TerminalCursorShape getShape() {
            return TerminalCursorShape.BAR;
        }
    }

    static final int MAX_VISIBLE_COMMANDS = 8;
    private static final List<CommandSpec> COMMAND_SPECS = List.of(
            new CommandSpec("q", List.of(), "", "close current window; exit on last tab"),
            new CommandSpec("e", List.of(), "<path>", "open or create a file"),
            new CommandSpec("debug", List.of("dbg"), "[providers|open|stop|continue|next|step|out|break|<provider> ...]",
                    "open the debugger or run debugger commands"),
            new CommandSpec("git", List.of(), "[status]", "open the Git workspace"),
            new CommandSpec("split", List.of("sp"), "", "split the active pane below"),
            new CommandSpec("vsplit", List.of("vs"), "", "split the active pane to the right"),
            new CommandSpec("close", List.of(), "", "close the active pane"),
            new CommandSpec("only", List.of(), "", "keep only the active pane"),
            new CommandSpec("focus", List.of(), "left|right|up|down|next|prev", "move focus between panes"),
            new CommandSpec("tab-rename", List.of("rename-tab", "rename-window"), "<name>", "rename the current tab"),
            new CommandSpec("tab-move", List.of("move-tab", "move-window"), "<index>", "move the current tab"),
            new CommandSpec("tab-swap-left", List.of(), "", "swap the current tab left"),
            new CommandSpec("tab-swap-right", List.of(), "", "swap the current tab right"),
            new CommandSpec("buffers", List.of("ls"), "", "show open buffers"),
            new CommandSpec("buffer", List.of("b"), "<index|path>", "switch to an open buffer"),
            new CommandSpec("bnext", List.of("bn"), "", "switch to the next open buffer"),
            new CommandSpec("bprev", List.of("bp"), "", "switch to the previous open buffer"),
            new CommandSpec("copen", List.of(), "", "open the quickfix list"),
            new CommandSpec("cclose", List.of(), "", "close the quickfix or location list"),
            new CommandSpec("cnext", List.of("cn"), "", "jump to the next quickfix entry"),
            new CommandSpec("cprev", List.of("cp"), "", "jump to the previous quickfix entry"),
            new CommandSpec("lopen", List.of(), "", "open the location list"),
            new CommandSpec("lclose", List.of(), "", "close the location list"),
            new CommandSpec("lnext", List.of("ln"), "", "jump to the next location-list entry"),
            new CommandSpec("lprev", List.of("lp"), "", "jump to the previous location-list entry"),
            new CommandSpec("lgrep", List.of(), "<text>", "search the current buffer into the location list"),
            new CommandSpec("multicursor", List.of("mc"), "<text>", "place cursors on each literal match in the current buffer"),
            new CommandSpec("move", List.of("m"), "{address}", "move the current line or range"),
            new CommandSpec("s", List.of(), "/pattern/replacement/[g]", "substitute in the current line"),
            new CommandSpec("%s", List.of(), "/pattern/replacement/[g]", "substitute in the whole buffer"),
            new CommandSpec("grep", List.of("search"), "<text>", "search project text"),
            new CommandSpec("help", List.of("h"), "", "open the built-in help"),
            new CommandSpec("detach", List.of(), "", "detach the current client"),
            new CommandSpec("sessions", List.of(), "", "show live SWIM server sessions"),
            new CommandSpec("session", List.of(), "<name>", "switch to a live SWIM server session"),
            new CommandSpec("session-kill", List.of(), "<name>", "kill a live SWIM server session"),
            new CommandSpec("mail", List.of(), "", "open the mail client"),
            new CommandSpec("todo", List.of(), "", "open the Todo workspace"),
            new CommandSpec("tree", List.of(), "", "open the tree view"),
            new CommandSpec("registers", List.of("reg"), "", "show registers and macros"),
            new CommandSpec("marks", List.of(), "", "show marks"),
            new CommandSpec("jumps", List.of(), "", "show the jump list"),
            new CommandSpec("slack", List.of(), "", "open the Slack client"),
            new CommandSpec("nemo", List.of(), "<question>", "open Nemo workspace and optionally ask a question"),
            new CommandSpec("reload", List.of(), "", "reload the latest built SWIM core"),
            new CommandSpec("rebuild", List.of(), "", "rebuild and reload SWIM"),
            new CommandSpec("shell", List.of("sh"), "", "open a shell workspace"),
            new CommandSpec("vshell", List.of(), "", "open a shell in a split to the right"),
            new CommandSpec("hshell", List.of(), "", "open a shell in a split below"),
            new CommandSpec("upgrade", List.of(), "", "alias for :rebuild"),
            new CommandSpec("w", List.of(), "[path]", "write the current buffer"),
            new CommandSpec("wq", List.of(), "", "write current buffer and close current window"),
            new CommandSpec("x", List.of(), "", "write current buffer and close current window"),
            new CommandSpec("q!", List.of(), "", "close current window without checks"),
            new CommandSpec("read", List.of("r"), "<path>", "read a file below the current line"),
            new CommandSpec("set", List.of(), "[option=value]", "show or update editor options"),
            new CommandSpec("normal", List.of("norm"), "<keys>", "run normal-mode keys"),
            new CommandSpec("g", List.of(), "/pattern/d|s/.../.../[g]", "run a command on matching lines"),
            new CommandSpec("v", List.of(), "/pattern/d|s/.../.../[g]", "run a command on non-matching lines"));

    private String _message = null;
    private String _prompt = null;
    private StringBuilder _command = null;
    private int _commandSelection;
    private String _lastCommand = null;
    private boolean _editorDriveOwned;
    private ProjectSearchPanelView _liveGrepPreviewPanel;
    private boolean _liveGrepPreviewOwned;
    private ListEventResponder _responders = new ListEventResponder();
    private boolean _searchForward;
    private String _searchString;
    private final CommandCursor _cursor;
    private static final Logger _log = LogFactory.createLog();

    private boolean isSearch() {
        return "/".equals(_prompt) || "?".equals(_prompt);
    }

    public CommandView(Rect bounds) {
        super(bounds);
        _cursor = new CommandCursor(this);
        setBackgroundColour(UiTheme.COMMAND_INACTIVE_BACKGROUND);
        _responders.addEventResponder("<ESC>", () -> {
            allowEditorDrivePromptAction("close prompt");
            deactivate();
        });
        _responders.addEventResponder("<ENTER>", () -> {
            allowEditorDrivePromptAction(isSearch() ? "run search prompt" : "run command prompt");
            if (isSearch()) {
                runSearch(_command.toString());
            } else {
                runCommand(_command.toString());
            }
            if (Window.getInstance() != null) {
                deactivate(true);
            }
        });
        _responders.addEventResponder("<BACKSPACE>", () -> {
            allowEditorDrivePromptAction("edit prompt");
            if (_command.length() > 0) {
                _command.delete(_command.length() - 1, _command.length());
                resetCommandSelection();
                syncLiveGrepPreview();
                refreshChrome();
            }
        });
        _responders.addEventResponder(new EventResponder() {
            private CommandKeyAction _action = CommandKeyAction.NONE;

            @Override
            public Response processEvent(KeyStrokes events) {
                _action = CommandKeyAction.NONE;
                if (events.remaining() != 0 || !isCommandPrompt()) {
                    return Response.NO;
                }
                var event = events.current();
                _action = switch (event.getKeyType()) {
                case ArrowUp -> CommandKeyAction.PREVIOUS_MATCH;
                case ArrowDown -> CommandKeyAction.NEXT_MATCH;
                case ReverseTab -> CommandKeyAction.PREVIOUS_MATCH;
                case Tab -> CommandKeyAction.COMPLETE_MATCH;
                default -> CommandKeyAction.NONE;
                };
                return _action == CommandKeyAction.NONE ? Response.NO : Response.YES;
            }

            @Override
            public void respond() {
                switch (_action) {
                case PREVIOUS_MATCH -> {
                    allowEditorDrivePromptAction("select command completion");
                    moveCommandSelection(-1);
                }
                case NEXT_MATCH -> {
                    allowEditorDrivePromptAction("select command completion");
                    moveCommandSelection(1);
                }
                case COMPLETE_MATCH -> {
                    allowEditorDrivePromptAction("complete command");
                    completeSelectedCommand();
                }
                default -> {
                }
                }
            }
        });
        _responders.addEventResponder(new EventResponder() {
            private char _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                var event = events.current();
                if (event.getKeyType() == KeyType.Character) {
                    _character = event.getCharacter();
                    return Response.YES;
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                allowEditorDrivePromptAction("edit prompt");
                _command.append(_character);
                resetCommandSelection();
                syncLiveGrepPreview();
                refreshChrome();
            }
        });
    }

    public void runSearch(String string) {
        _log.debug("Searching for: " + string);
        Pattern pattern;
        try {
          pattern = Pattern.compile(string);
        } catch (Throwable e) {
            _message = "Invalid search pattern: " + e.getMessage();
            _log.error("Pattern threw exception: ", e);
            return;
        }
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        _searchString = string;
        if (_prompt.equals("/")) {
            _searchForward = true;
            Window.getInstance().performJump(() -> cursor.goNext(pattern));
        } else {
            _searchForward = false;
            Window.getInstance().performJump(() -> cursor.goPrevious(pattern));
        }
    }

    public void searchNext() {
        if (_searchString == null) {
            return;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(_searchString);
        } catch (Throwable e) {
            _message = "Invalid search pattern: " + e.getMessage();
            return;
        }
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        if (!_searchForward) {
            Window.getInstance().performJump(() -> cursor.goPrevious(pattern));
        } else {
            Window.getInstance().performJump(() -> cursor.goNext(pattern));
        }
    }

    public void searchPrevious() {
        if (_searchString == null) {
            return;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(_searchString);
        } catch (Throwable e) {
            _message = "Invalid search pattern: " + e.getMessage();
            return;
        }
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        if (!_searchForward) {
            Window.getInstance().performJump(() -> cursor.goNext(pattern));
        } else {
            Window.getInstance().performJump(() -> cursor.goPrevious(pattern));
        }
    }

    private void runCommand(String rawCommand) {
        rawCommand = rawCommand.trim();
        if (rawCommand.equals("")) {
            return;
        }
        rawCommand = editorDriveSandboxedCommand(rawCommand);
        if (rawCommand == null) {
            return;
        }
        var substitution = parseSubstitute(rawCommand);
        if (substitution != null) {
            runSubstitute(substitution);
            _lastCommand = rawCommand;
            return;
        }
        var global = parseGlobal(rawCommand);
        if (global != null) {
            runGlobal(global);
            _lastCommand = rawCommand;
            return;
        }
        MoveCommand move;
        try {
            move = parseMove(rawCommand);
        } catch (IllegalArgumentException e) {
            _message = e.getMessage();
            return;
        }
        if (move != null) {
            runMove(move);
            _lastCommand = rawCommand;
            return;
        }
        _lastCommand = rawCommand;
        int splitIndex = rawCommand.indexOf(' ');
        String command;
        String argument = "";
        if (splitIndex == -1) {
            command = rawCommand;
        } else {
            command = rawCommand.substring(0, splitIndex);
            argument = rawCommand.substring(splitIndex + 1).trim();
        }
        switch (command) {
        case "q":
            Window.getInstance().quitCurrentWindowOrExit();
            break;
        case "q!":
            Window.getInstance().quitCurrentWindowOrExit();
            break;
        case "e":
            open(argument);
            break;
        case "debug":
        case "dbg":
            handleDebug(argument);
            break;
        case "git":
            openGit(argument);
            break;
        case "split":
        case "sp":
            splitBuffer(false);
            break;
        case "vsplit":
        case "vs":
            splitBuffer(true);
            break;
        case "close":
            closeView();
            break;
        case "only":
            closeOtherViews();
            break;
        case "focus":
            focus(argument);
            break;
        case "tab-rename":
        case "rename-tab":
        case "rename-window":
            if (!Window.getInstance().renameCurrentTab(argument)) {
                _message = "Unable to rename current tab";
            }
            break;
        case "tab-move":
        case "move-tab":
        case "move-window":
            moveTab(argument);
            break;
        case "tab-swap-left":
            if (!Window.getInstance().swapCurrentTabByDelta(-1)) {
                _message = "No tab to the left";
            }
            break;
        case "tab-swap-right":
            if (!Window.getInstance().swapCurrentTabByDelta(1)) {
                _message = "No tab to the right";
            }
            break;
        case "buffers":
        case "ls":
            Window.getInstance().showBufferList();
            break;
        case "buffer":
        case "b":
            if (argument.isBlank() || !Window.getInstance().switchBufferByToken(argument)) {
                _message = argument.isBlank() ? "Wrong number of parameters" : "No such buffer: " + argument;
            }
            break;
        case "bnext":
        case "bn":
            if (!Window.getInstance().switchNextBuffer()) {
                _message = "No next buffer";
            }
            break;
        case "bprev":
        case "bp":
            if (!Window.getInstance().switchPreviousBuffer()) {
                _message = "No previous buffer";
            }
            break;
        case "copen":
            if (!Window.getInstance().openQuickfixList()) {
                _message = "Quickfix list is empty";
            }
            break;
        case "cclose":
        case "lclose":
            if (!Window.getInstance().closeLocationLists()) {
                _message = "No location list is open";
            }
            break;
        case "cnext":
        case "cn":
            if (!Window.getInstance().nextQuickfix()) {
                _message = "No next quickfix entry";
            }
            break;
        case "cprev":
        case "cp":
            if (!Window.getInstance().previousQuickfix()) {
                _message = "No previous quickfix entry";
            }
            break;
        case "lopen":
            if (!Window.getInstance().openLocationList()) {
                _message = "Location list is empty";
            }
            break;
        case "lnext":
        case "ln":
            if (!Window.getInstance().nextLocation()) {
                _message = "No next location entry";
            }
            break;
        case "lprev":
        case "lp":
            if (!Window.getInstance().previousLocation()) {
                _message = "No previous location entry";
            }
            break;
        case "lgrep":
            if (argument.isBlank()) {
                _message = "Wrong number of parameters";
            } else {
                runLocationGrep(argument);
            }
            break;
        case "multicursor":
        case "mc":
            if (argument.isBlank() || !Window.getInstance().createCursorsForLiteral(argument)) {
                _message = argument.isBlank() ? "Wrong number of parameters" : "No matches for multicursor";
            }
            break;
        case "grep":
        case "search":
            ProjectSearchUiSupport.open(Window.getInstance(), argument);
            break;
        case "h":
        case "help":
            if (!Window.getInstance().showHelpWorkspace()) {
                _message = "Failed to open help";
            }
            break;
        case "sessions":
            showServerSessions();
            break;
        case "detach":
            Window.getInstance().detachCurrentSession();
            break;
        case "session":
            switchServerSession(argument);
            break;
        case "session-kill":
            killServerSession(argument);
            break;
        case "mail":
            MailUiSupport.toggle(Window.getInstance());
            break;
        case "todo":
            TodoUiSupport.open(Window.getInstance());
            break;
        case "tree":
            TreeUiSupport.toggle(Window.getInstance());
            break;
        case "registers":
        case "reg":
            Window.getInstance().showTextPanel("Registers", Window.getInstance().registersSummary());
            break;
        case "marks":
            Window.getInstance().showTextPanel("Marks", Window.getInstance().marksSummary());
            break;
        case "jumps":
            Window.getInstance().showTextPanel("Jumps", Window.getInstance().jumpsSummary());
            break;
        case "slack":
            SlackUiSupport.toggle(Window.getInstance());
            break;
        case "nemo":
            org.fisk.swim.nemo.NemoClient.getInstance().runWorkspace(Window.getInstance().getBufferContext(), argument);
            break;
        case "reload":
            SwimRuntime.reload();
            break;
        case "rebuild":
        case "upgrade":
            SwimRuntime.rebuildAndReload();
            break;
        case "shell":
        case "sh":
            if (!Window.getInstance().showShellWorkspace()) {
                _message = "Failed to open shell workspace";
            }
            break;
        case "vshell":
            if (!Window.getInstance().showShellSplitHorizontally()) {
                _message = "Failed to open shell workspace";
            }
            break;
        case "hshell":
            if (!Window.getInstance().showShellSplitVertically()) {
                _message = "Failed to open shell workspace";
            }
            break;
        case "w":
            write(argument);
            break;
        case "wq":
        case "x":
            Window.getInstance().getBufferContext().getBuffer().write();
            Window.getInstance().quitCurrentWindowOrExit();
            break;
        case "read":
        case "r":
            readFile(argument);
            break;
        case "set":
            setOption(argument);
            break;
        case "normal":
        case "norm":
            runNormal(argument);
            break;
        default:
            _message = "Unknown command: " + command;
            break;
        }
    }

    private void moveCommandSelection(int delta) {
        var matches = matchingCommandSpecs();
        if (matches.isEmpty()) {
            return;
        }
        _commandSelection = Math.floorMod(_commandSelection + delta, matches.size());
        refreshChrome();
    }

    private void completeSelectedCommand() {
        var matches = matchingCommandSpecs();
        if (matches.isEmpty()) {
            return;
        }
        applyCommandSpec(matches.get(normalizeSelection(matches.size())));
        resetCommandSelection();
        refreshChrome();
    }

    private void applyCommandSpec(CommandSpec spec) {
        if (spec.replaceEntireInput()) {
            _command = new StringBuilder(spec.replacement());
            syncLiveGrepPreview();
            return;
        }
        String current = _command == null ? "" : _command.toString();
        int tokenStart = 0;
        while (tokenStart < current.length() && Character.isWhitespace(current.charAt(tokenStart))) {
            tokenStart++;
        }
        int tokenEnd = tokenStart;
        while (tokenEnd < current.length() && !Character.isWhitespace(current.charAt(tokenEnd))) {
            tokenEnd++;
        }
        String before = current.substring(0, tokenStart);
        String after = current.substring(tokenEnd);
        boolean hasArguments = !after.isBlank();
        String replacement = spec.replacement();
        if (!hasArguments && spec.expectsArgument()) {
            replacement += " ";
        }
        _command = new StringBuilder(before).append(replacement).append(after);
        syncLiveGrepPreview();
    }

    private void syncLiveGrepPreview() {
        var window = Window.getInstance();
        if (window == null || _command == null || !isCommandPrompt()) {
            return;
        }
        LiveGrepCommand liveGrep = parseLiveGrepCommand(_command.toString());
        if (liveGrep == null) {
            cancelLiveGrepPreview();
            return;
        }
        boolean alreadyShowingSearch = window.getPanelView() instanceof ProjectSearchPanelView;
        ProjectSearchPanelView panel = ProjectSearchUiSupport.openPreview(window, liveGrep.query());
        if (panel == null) {
            return;
        }
        _liveGrepPreviewPanel = panel;
        if (!alreadyShowingSearch) {
            _liveGrepPreviewOwned = true;
        }
        window.getRootView().setFirstResponder(this);
    }

    private void cancelLiveGrepPreview() {
        var window = Window.getInstance();
        if (window == null) {
            clearLiveGrepPreview();
            return;
        }
        if (_liveGrepPreviewOwned && _liveGrepPreviewPanel != null && window.getPanelView() == _liveGrepPreviewPanel) {
            window.hidePanel();
            if (_command != null && window.getRootView() != null) {
                window.getRootView().setFirstResponder(this);
            }
        }
        clearLiveGrepPreview();
    }

    private void clearLiveGrepPreview() {
        _liveGrepPreviewPanel = null;
        _liveGrepPreviewOwned = false;
    }

    private static LiveGrepCommand parseLiveGrepCommand(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String command = text.substring(start, end).toLowerCase(Locale.ROOT);
        if (!"grep".equals(command) && !"search".equals(command)) {
            return null;
        }
        String query = end >= text.length() ? "" : text.substring(end + 1).trim();
        return new LiveGrepCommand(query);
    }

    private String editorDriveSandboxedCommand(String rawCommand) {
        var window = Window.getInstance();
        if (window == null || !_editorDriveOwned || !window.isEditorDriveCommandSandboxActive()) {
            return rawCommand;
        }
        int splitIndex = rawCommand.indexOf(' ');
        String command = splitIndex == -1 ? rawCommand : rawCommand.substring(0, splitIndex);
        String argument = splitIndex == -1 ? "" : rawCommand.substring(splitIndex + 1).trim();
        try {
            if (parseSubstitute(rawCommand) != null) {
                return allowEditorDriveCommand(window, rawCommand);
            }
            if (parseGlobal(rawCommand) != null) {
                return allowEditorDriveCommand(window, rawCommand);
            }
            if (parseMove(rawCommand) != null) {
                return allowEditorDriveCommand(window, rawCommand);
            }
        } catch (IllegalArgumentException ignored) {
            return allowEditorDriveCommand(window, rawCommand);
        }
        return switch (command) {
        case "e" -> sandboxedEditorOpenCommand(window, rawCommand, argument);
        case "split", "sp", "vsplit", "vs", "close", "only", "focus",
                "buffers", "ls", "buffer", "b", "bnext", "bn", "bprev", "bp",
                "copen", "cclose", "cnext", "cn", "cprev", "cp",
                "lopen", "lclose", "lnext", "ln", "lprev", "lp",
                "lgrep", "multicursor", "mc", "grep", "search",
                "h", "help", "tree", "registers", "reg", "marks", "jumps",
                "set", "normal", "norm" -> allowEditorDriveCommand(window, rawCommand);
        case "detach", "sessions", "session", "session-kill" -> blockEditorDriveCommand(window, rawCommand,
                "server session management requires host action");
        case "read", "r" -> sandboxedEditorReadCommand(window, rawCommand, argument);
        case "w" -> sandboxedEditorWriteCommand(window, rawCommand, argument);
        case "q", "q!", "wq", "x" -> blockEditorDriveCommand(window, rawCommand, "quitting SWIM is not allowed");
        case "mail", "todo", "slack", "nemo" -> blockEditorDriveCommand(window, rawCommand,
                "opening host communication or assistant workspaces is not allowed");
        case "shell", "sh", "vshell", "hshell" -> blockEditorDriveCommand(window, rawCommand,
                "opening shell input through drive_editor is not allowed");
        case "reload", "rebuild", "upgrade" -> blockEditorDriveCommand(window, rawCommand,
                "reload and rebuild commands require host action");
        case "debug", "dbg" -> blockEditorDriveCommand(window, rawCommand,
                "debugger commands are outside the editor-control sandbox");
        case "git" -> blockEditorDriveCommand(window, rawCommand,
                "git UI commands are outside the editor-control sandbox");
        case "tab-rename", "rename-tab", "rename-window",
                "tab-move", "move-tab", "move-window",
                "tab-swap-left", "tab-swap-right" -> blockEditorDriveCommand(window, rawCommand,
                        "tab layout management requires host action");
        default -> blockEditorDriveCommand(window, rawCommand,
                "unknown or unsupported command in the editor-control sandbox");
        };
    }

    private String sandboxedEditorOpenCommand(Window window, String rawCommand, String argument) {
        if (argument.isBlank()) {
            return blockEditorDriveCommand(window, rawCommand, ":e requires an existing workspace path");
        }
        Path path;
        try {
            path = window.resolveEditorDriveWorkspacePath(argument);
        } catch (IllegalArgumentException e) {
            return blockEditorDriveCommand(window, rawCommand, e.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            Path parent = path.getParent();
            if (Files.exists(path) || parent == null || !Files.isDirectory(parent)) {
                return blockEditorDriveCommand(window, rawCommand,
                        "path is not an existing workspace file or creatable workspace file: " + argument);
            }
        }
        window.allowEditorDriveAction(":e");
        return "e " + path;
    }

    private String sandboxedEditorWriteCommand(Window window, String rawCommand, String argument) {
        if (!argument.isBlank()) {
            return blockEditorDriveCommand(window, rawCommand, ":w does not accept arguments");
        }
        var context = window.getBufferContext();
        Path path = context == null ? null : context.getBuffer().getPath();
        String block = window.editorDriveWorkspacePathBlock(path, "save");
        if (block != null) {
            return blockEditorDriveCommand(window, rawCommand, block);
        }
        return allowEditorDriveCommand(window, rawCommand);
    }

    private String sandboxedEditorReadCommand(Window window, String rawCommand, String argument) {
        if (argument.isBlank()) {
            return blockEditorDriveCommand(window, rawCommand, ":read requires an existing workspace path");
        }
        Path path;
        try {
            path = window.resolveEditorDriveWorkspacePath(argument);
        } catch (IllegalArgumentException e) {
            return blockEditorDriveCommand(window, rawCommand, e.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            return blockEditorDriveCommand(window, rawCommand, "path is not an existing workspace file: " + argument);
        }
        window.allowEditorDriveAction(":read");
        return "read " + path;
    }

    private String blockEditorDriveCommand(Window window, String rawCommand, String reason) {
        _message = "Editor control blocked :" + rawCommand + ": " + reason;
        window.blockEditorDriveCommand(_message);
        return null;
    }

    private String allowEditorDriveCommand(Window window, String rawCommand) {
        window.allowEditorDriveAction(":" + rawCommand);
        return rawCommand;
    }

    private void allowEditorDrivePromptAction(String action) {
        var window = Window.getInstance();
        if (window != null && _editorDriveOwned) {
            window.allowEditorDriveAction(action);
        }
    }

    private void splitBuffer(boolean vertical) {
        var window = Window.getInstance();
        boolean opened = vertical ? window.splitActiveContentHorizontally() : window.splitActiveContentVertically();
        if (!opened) {
            _message = "Failed to split view";
        }
    }

    private void closeView() {
        if (!Window.getInstance().closeActiveView()) {
            _message = "Cannot close the last buffer view";
        }
    }

    private void closeOtherViews() {
        if (!Window.getInstance().closeOtherViews()) {
            _message = "No other views to close";
        }
    }

    private void focus(String argument) {
        if (argument.isEmpty()) {
            _message = "Wrong number of parameters";
            return;
        }
        boolean moved = switch (argument) {
        case "left", "h" -> Window.getInstance().focusView(Window.Direction.LEFT);
        case "right", "l" -> Window.getInstance().focusView(Window.Direction.RIGHT);
        case "up", "k" -> Window.getInstance().focusView(Window.Direction.UP);
        case "down", "j" -> Window.getInstance().focusView(Window.Direction.DOWN);
        case "next" -> Window.getInstance().focusNextView();
        case "prev", "previous" -> Window.getInstance().focusPreviousView();
        default -> {
            _message = "Unknown focus target: " + argument;
            yield true;
        }
        };
        if (!moved) {
            _message = "No view in that direction";
        }
    }

    private void moveTab(String argument) {
        if (argument.isBlank()) {
            _message = "Wrong number of parameters";
            return;
        }
        int index;
        try {
            index = Integer.parseInt(argument.trim());
        } catch (NumberFormatException e) {
            _message = "Invalid tab index: " + argument;
            return;
        }
        if (!Window.getInstance().moveCurrentTabToIndex(index)) {
            _message = "No tab " + index;
        }
    }

    private void showServerSessions() {
        if (!SwimServerSessions.isAvailable()) {
            Window.getInstance().showTextPanel("Sessions",
                    "No SWIM session server is attached.\n\nLaunch swim through the normal client to use server-managed sessions.");
            return;
        }
        List<SwimServerSession> sessions;
        try {
            sessions = SwimServerSessions.list();
        } catch (IOException e) {
            Window.getInstance().showTextPanel("Sessions", "Unable to read SWIM server sessions: " + e.getMessage());
            return;
        }
        if (sessions.isEmpty()) {
            Window.getInstance().showTextPanel("Sessions", "No live SWIM server sessions.");
            return;
        }

        var items = new ArrayList<ListView.ListItem>();
        for (SwimServerSession session : sessions) {
            items.add(new ListView.ListItem() {
                @Override
                public void onClick() {
                    if (session.current()) {
                        return;
                    }
                    try {
                        SwimServerSessions.switchTo(session.name());
                    } catch (IOException e) {
                        _message = "Unable to switch SWIM session: " + e.getMessage();
                    }
                }

                @Override
                public String displayString() {
                    return serverSessionDisplayString(session);
                }
            });
        }
        Window.getInstance().showList(items, "Sessions");
    }

    private void switchServerSession(String argument) {
        if (argument.isBlank()) {
            showServerSessions();
            return;
        }
        try {
            SwimServerSessions.switchTo(argument);
            _message = "Switching to SWIM session " + SwimServerSessions.normalizeName(argument);
        } catch (IOException e) {
            _message = "Unable to switch SWIM session: " + e.getMessage();
        }
    }

    private void killServerSession(String argument) {
        if (argument.isBlank()) {
            _message = "Usage: :session-kill <name>";
            return;
        }
        try {
            _message = SwimServerSessions.kill(argument);
        } catch (IOException e) {
            _message = "Unable to kill SWIM session: " + e.getMessage();
        }
    }

    private static String serverSessionDisplayString(SwimServerSession session) {
        String marker = session.current() ? "*" : " ";
        String attached = session.attached() ? "attached" : "detached";
        String running = session.running() ? "pid " + session.pid() : "stopped";
        String args = session.launchArgs().isEmpty() ? "" : " | " + String.join(" ", session.launchArgs());
        return marker + " " + session.name() + " | " + attached + " | " + running + args;
    }

    private boolean isCommandPrompt() {
        return ":".equals(_prompt);
    }

    private void resetCommandSelection() {
        _commandSelection = 0;
    }

    private int normalizeSelection(int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(_commandSelection, size - 1));
    }

    private List<CommandSpec> matchingCommandSpecs() {
        return matchingCommandSpecs(commandPrefix(), _lastCommand);
    }

    private static List<CommandSpec> matchingCommandSpecs(String prefix) {
        return matchingCommandSpecs(prefix, null, COMMAND_SPECS);
    }

    private static List<CommandSpec> matchingCommandSpecs(String prefix, String lastCommand) {
        return matchingCommandSpecs(prefix, lastCommand, COMMAND_SPECS);
    }

    private static List<CommandSpec> matchingCommandSpecs(String prefix, List<CommandSpec> commandSpecs) {
        return matchingCommandSpecs(prefix, null, commandSpecs);
    }

    private static List<CommandSpec> matchingCommandSpecs(String prefix, String lastCommand, List<CommandSpec> commandSpecs) {
        var matches = new ArrayList<CommandSpec>();
        if (prefix.isBlank() && lastCommand != null && !lastCommand.isBlank()) {
            matches.add(CommandSpec.lastCommand(lastCommand));
        }
        for (var spec : commandSpecs) {
            if (prefix.isBlank() || spec.matches(prefix)) {
                matches.add(spec);
            }
        }
        matches.sort(Comparator.comparingInt((CommandSpec spec) -> spec.matchScore(prefix)));
        return matches;
    }

    private String commandPrefix() {
        return commandPrefix(_command == null ? "" : _command.toString());
    }

    private static String commandPrefix(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private void refreshChrome() {
        var window = Window.getInstance();
        if (window != null) {
            window.refreshChromeState();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
            return;
        }
        setNeedsRedraw();
    }

    private void open(String pathString) {
        if (pathString.equals("")) {
            _message = "Wrong number of parameters";
            return;
        }
        var path = Paths.get(pathString).toAbsolutePath();
        if (path.toFile().isDirectory()) {
            if (!Window.getInstance().showDirectoryBrowser(path)) {
                _message = "Failed to open directory";
            }
            return;
        }
        if (!path.toFile().exists()) {
            try {
                if (path.toFile().createNewFile()) {
                    if (!Window.getInstance().setBufferPath(path)) {
                        _message = "Failed to open file";
                    }
                    return;
                }
            } catch (Exception e) {
            }
            _message = "File does not exist";
        } else {
            if (!Window.getInstance().setBufferPath(path)) {
                _message = "Failed to open file";
            }
        }
    }

    private void handleDebug(String argument) {
        var window = Window.getInstance();
        if (window == null) {
            _message = "Debugger unavailable";
            return;
        }
        if (argument == null || argument.isBlank() || "open".equals(argument)) {
            DebuggerUiSupport.open(window);
            return;
        }
        int splitIndex = argument.indexOf(' ');
        String verb = splitIndex < 0 ? argument : argument.substring(0, splitIndex).trim();
        String rest = splitIndex < 0 ? "" : argument.substring(splitIndex + 1).trim();
        try {
            switch (verb) {
            case "providers":
                _message = DebuggerManager.providersSummary();
                break;
            case "stop":
                DebuggerManager.stop();
                break;
            case "continue":
                DebuggerManager.resume();
                break;
            case "next":
                DebuggerManager.stepOver();
                break;
            case "step":
                DebuggerManager.stepInto();
                break;
            case "out":
                DebuggerManager.stepOut();
                break;
            case "break":
                DebuggerManager.toggleBreakpointAtCursor();
                break;
            default:
                DebuggerManager.launchFromCommand(verb,
                        window.getBufferContext() == null ? null : window.getBufferContext().getBuffer().getPath(),
                        rest);
                DebuggerUiSupport.open(window);
                break;
            }
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Debugger command failed" : e.getMessage();
        }
    }

    private void openGit(String argument) {
        if (!argument.isBlank() && !"status".equals(argument)) {
            _message = "Unknown git command: " + argument;
            return;
        }
        String pluginId = "swim-git";
        SwimRuntime.loadPlugin(pluginId);
        var panel = SwimRuntime.getPanel(pluginId);
        if (panel == null) {
            _message = "Git plugin unavailable";
            return;
        }
        if (!Window.getInstance().showPluginWorkspace(pluginId, panel)) {
            _message = "Unable to open Git workspace";
        }
    }

    private void runSubstitute(SubstituteCommand command) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        try {
            Pattern pattern = Pattern.compile(command.pattern());
            var buffer = window.getBufferContext().getBuffer();
            int matches;
            if (command.wholeBuffer()) {
                matches = buffer.substitute(pattern, command.replacement(), command.global(), true);
            } else if (command.rangePrefix() != null && !command.rangePrefix().isBlank()) {
                LineRange range = resolveLineRange(buffer, command.rangePrefix());
                matches = buffer.substitute(pattern, command.replacement(), command.global(), range.startLine(), range.endLine());
            } else {
                matches = buffer.substitute(pattern, command.replacement(), command.global(), false);
            }
            if (matches == 0) {
                _message = "Pattern not found: " + command.pattern();
                return;
            }
            window.getBufferContext().getBuffer().getUndoLog().commit();
            _message = "Substituted " + matches + " match" + (matches == 1 ? "" : "es");
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Invalid substitute command" : e.getMessage();
        }
    }

    private void runGlobal(GlobalCommand command) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        try {
            Pattern pattern = Pattern.compile(command.pattern());
            var buffer = window.getBufferContext().getBuffer();
            String body = command.command().trim();
            int affected;
            if ("d".equals(body) || "delete".equals(body)) {
                affected = buffer.deleteMatchingLines(pattern, command.invert());
                buffer.getUndoLog().commit();
                _message = "Deleted " + affected + " line" + (affected == 1 ? "" : "s");
                return;
            }
            var substitution = parseSubstitute(body);
            if (substitution != null) {
                affected = 0;
                int lines = buffer.getLineCount();
                for (int line = lines - 1; line >= 0; line--) {
                    String text = buffer.getSubstring(buffer.getLineStartByIndex(line), buffer.getLineEndByIndex(line, false));
                    boolean matches = pattern.matcher(text).find();
                    if (matches == command.invert()) {
                        continue;
                    }
                    affected += buffer.substitute(Pattern.compile(substitution.pattern()), substitution.replacement(),
                            substitution.global(), line, line);
                }
                buffer.getUndoLog().commit();
                _message = "Substituted " + affected + " match" + (affected == 1 ? "" : "es");
                return;
            }
            _message = "Unsupported global command: " + body;
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Invalid global command" : e.getMessage();
        }
    }

    private void runMove(MoveCommand command) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        try {
            var buffer = window.getBufferContext().getBuffer();
            LineRange range = command.rangePrefix().isBlank()
                    ? currentLineRange(buffer)
                    : resolveMoveLineRange(buffer, command.rangePrefix());
            int destination = resolveMoveDestination(buffer, command.destination());
            var result = buffer.moveLineRangeAfter(range.startLine(), range.endLine(), destination);
            if (result == null) {
                _message = "Cannot move lines";
                return;
            }
            buffer.getUndoLog().commit();
            _message = "Moved " + (result.endLine() - result.startLine() + 1) + " line"
                    + (result.endLine() == result.startLine() ? "" : "s");
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Invalid move command" : e.getMessage();
        }
    }

    private void write(String argument) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        if (argument == null || argument.isBlank()) {
            window.getBufferContext().getBuffer().write();
            return;
        }
        try {
            Path path = Paths.get(argument).toAbsolutePath();
            Files.writeString(path, window.getBufferContext().getBuffer().getString());
            _message = "Saved file: " + path;
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Write failed" : e.getMessage();
        }
    }

    private void readFile(String argument) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        if (argument == null || argument.isBlank()) {
            _message = "Wrong number of parameters";
            return;
        }
        try {
            Path path = Paths.get(argument).toAbsolutePath();
            String text = Files.readString(path);
            if (!text.endsWith("\n")) {
                text += "\n";
            }
            var buffer = window.getBufferContext().getBuffer();
            int insertAt = buffer.getLineEndPosition(buffer.getCursor().getPosition(), true);
            buffer.insert(insertAt, text);
            buffer.getUndoLog().commit();
            _message = "Read file: " + path;
        } catch (Exception e) {
            _message = e.getMessage() == null || e.getMessage().isBlank() ? "Read failed" : e.getMessage();
        }
    }

    private void setOption(String argument) {
        if (argument == null || argument.isBlank()) {
            _message = "tabstop=" + System.getProperty("swim.indent.default.size", "4")
                    + " shiftwidth=" + System.getProperty("swim.indent.default.size", "4")
                    + " expandtab";
            return;
        }
        String[] parts = argument.split("=", 2);
        if (parts.length != 2) {
            _message = "Usage: :set tabstop=<n> or :set shiftwidth=<n>";
            return;
        }
        String name = parts[0].trim();
        String value = parts[1].trim();
        try {
            int width = Integer.parseInt(value);
            if (width <= 0) {
                throw new NumberFormatException();
            }
            if ("tabstop".equals(name) || "shiftwidth".equals(name)) {
                System.setProperty("swim.indent.default.size", Integer.toString(width));
                _message = name + "=" + width;
            } else {
                _message = "Unknown option: " + name;
            }
        } catch (NumberFormatException e) {
            _message = "Invalid numeric option: " + value;
        }
    }

    private void runNormal(String argument) {
        var window = Window.getInstance();
        if (window == null || argument == null || argument.isBlank()) {
            _message = "Wrong number of parameters";
            return;
        }
        var mode = window.getNormalMode();
        var pending = new ArrayList<com.googlecode.lanterna.input.KeyStroke>();
        for (var stroke : parseNormalCommandKeys(argument)) {
            pending.add(stroke);
            var response = mode.processEvent(new KeyStrokes(List.copyOf(pending)));
            if (response == Response.YES) {
                mode.respond();
                pending.clear();
            } else if (response == Response.NO) {
                pending.clear();
            }
        }
        _message = pending.isEmpty() ? "Ran normal keys" : "Incomplete normal keys";
    }

    private void runLocationGrep(String query) {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            _message = "No active buffer";
            return;
        }
        var matches = new ArrayList<org.fisk.swim.fileindex.ProjectSearch.Match>();
        String[] lines = window.getBufferContext().getBuffer().getString().split("\\R", -1);
        Path path = window.getBufferContext().getBuffer().getPath();
        for (int i = 0; i < lines.length; i++) {
            int column = lines[i].indexOf(query);
            if (column >= 0) {
                matches.add(new org.fisk.swim.fileindex.ProjectSearch.Match(
                        path,
                        path == null ? Path.of("*scratch*") : path.getFileName(),
                        i + 1,
                        column + 1,
                        lines[i]));
            }
        }
        window.setLocationResults("Location", matches);
        if (matches.isEmpty()) {
            _message = "Location list is empty";
        } else {
            window.openLocationList();
        }
    }

    private static SubstituteCommand parseSubstitute(String rawCommand) {
        boolean wholeBuffer;
        String rangePrefix = "";
        String remainder;
        if (rawCommand.startsWith("%s")) {
            wholeBuffer = true;
            remainder = rawCommand.substring(2);
        } else if (rawCommand.startsWith("s")) {
            wholeBuffer = false;
            remainder = rawCommand.substring(1);
        } else {
            int commandIndex = substituteCommandIndex(rawCommand);
            if (commandIndex < 0) {
                return null;
            }
            rangePrefix = rawCommand.substring(0, commandIndex);
            wholeBuffer = false;
            remainder = rawCommand.substring(commandIndex + 1);
        }
        if (remainder.isEmpty()) {
            return null;
        }
        char delimiter = remainder.charAt(0);
        if (Character.isLetterOrDigit(delimiter) || Character.isWhitespace(delimiter)) {
            return null;
        }
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < remainder.length(); i++) {
            char character = remainder.charAt(i);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                current.append(character);
                continue;
            }
            if (character == delimiter && parts.size() < 2) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        parts.add(current.toString());
        if (parts.size() < 3) {
            throw new IllegalArgumentException("Usage: " + (wholeBuffer ? ":%s" : ":s") + "/pattern/replacement/[g]");
        }
        String options = parts.get(2);
        return new SubstituteCommand(wholeBuffer, rangePrefix, parts.get(0), parts.get(1), options.contains("g"));
    }

    private static int substituteCommandIndex(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return -1;
        }
        for (int i = 0; i < rawCommand.length(); i++) {
            char character = rawCommand.charAt(i);
            if (character == 's') {
                String prefix = rawCommand.substring(0, i);
                return isLineRangePrefix(prefix) ? i : -1;
            }
            if (!(Character.isDigit(character) || character == ',' || character == '.' || character == '$')) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isLineRangePrefix(String prefix) {
        return prefix != null && prefix.matches("(\\d+|\\.|\\$)(,(\\d+|\\.|\\$))?");
    }

    private static GlobalCommand parseGlobal(String rawCommand) {
        if (rawCommand == null || rawCommand.length() < 2) {
            return null;
        }
        boolean invert;
        if (rawCommand.startsWith("g")) {
            invert = false;
        } else if (rawCommand.startsWith("v")) {
            invert = true;
        } else {
            return null;
        }
        String remainder = rawCommand.substring(1);
        if (remainder.isEmpty()) {
            return null;
        }
        char delimiter = remainder.charAt(0);
        if (Character.isLetterOrDigit(delimiter) || Character.isWhitespace(delimiter)) {
            return null;
        }
        var pattern = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < remainder.length(); i++) {
            char character = remainder.charAt(i);
            if (escaped) {
                pattern.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                pattern.append(character);
                escaped = true;
                continue;
            }
            if (character == delimiter) {
                return new GlobalCommand(invert, pattern.toString(), remainder.substring(i + 1));
            }
            pattern.append(character);
        }
        return null;
    }

    private static MoveCommand parseMove(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return null;
        }
        String command = rawCommand.trim();
        for (int i = 0; i < command.length(); i++) {
            if (startsWithMoveCommand(command, i, "move")) {
                String prefix = command.substring(0, i).trim();
                if (!isMoveLineRangePrefix(prefix)) {
                    continue;
                }
                String destination = command.substring(i + "move".length()).trim();
                if (destination.isBlank()) {
                    throw new IllegalArgumentException("Usage: :[range]m {address}");
                }
                return new MoveCommand(prefix, destination);
            }
            if (command.charAt(i) == 'm' && isMoveShortCommand(command, i)) {
                String prefix = command.substring(0, i).trim();
                if (!isMoveLineRangePrefix(prefix)) {
                    continue;
                }
                String destination = command.substring(i + 1).trim();
                if (destination.isBlank()) {
                    throw new IllegalArgumentException("Usage: :[range]m {address}");
                }
                return new MoveCommand(prefix, destination);
            }
        }
        return null;
    }

    private static boolean startsWithMoveCommand(String command, int index, String name) {
        if (!command.startsWith(name, index)) {
            return false;
        }
        int after = index + name.length();
        return after == command.length() || Character.isWhitespace(command.charAt(after)) || isMoveAddressStart(command.charAt(after));
    }

    private static boolean isMoveShortCommand(String command, int index) {
        int after = index + 1;
        return after == command.length() || Character.isWhitespace(command.charAt(after)) || isMoveAddressStart(command.charAt(after));
    }

    private static boolean isMoveAddressStart(char character) {
        return Character.isDigit(character) || character == '.' || character == '$' || character == '+' || character == '-';
    }

    private static boolean isMoveLineRangePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        if ("%".equals(prefix)) {
            return true;
        }
        return prefix.matches("[0-9.$+\\-]+(,[0-9.$+\\-]+)?");
    }

    private static LineRange resolveLineRange(org.fisk.swim.text.Buffer buffer, String prefix) {
        String[] parts = prefix.split(",", -1);
        int start = resolveLineAddress(buffer, parts[0]);
        int end = parts.length > 1 ? resolveLineAddress(buffer, parts[1]) : start;
        return new LineRange(Math.max(0, Math.min(start, end)), Math.min(buffer.getLineCount() - 1, Math.max(start, end)));
    }

    private static LineRange currentLineRange(org.fisk.swim.text.Buffer buffer) {
        int line = buffer.getLineIndexAt(buffer.getCursor().getPosition());
        return new LineRange(line, line);
    }

    private static LineRange resolveMoveLineRange(org.fisk.swim.text.Buffer buffer, String prefix) {
        if ("%".equals(prefix)) {
            return new LineRange(0, buffer.getLineCount() - 1);
        }
        String[] parts = prefix.split(",", -1);
        int start = resolveMoveLineAddress(buffer, parts[0], false);
        int end = parts.length > 1 ? resolveMoveLineAddress(buffer, parts[1], false) : start;
        return new LineRange(Math.max(0, Math.min(start, end)),
                Math.min(buffer.getLineCount() - 1, Math.max(start, end)));
    }

    private static int resolveMoveDestination(org.fisk.swim.text.Buffer buffer, String address) {
        return resolveMoveLineAddress(buffer, address, true);
    }

    private static int resolveMoveLineAddress(org.fisk.swim.text.Buffer buffer, String address, boolean destination) {
        String value = address == null ? "." : address.trim();
        if (value.isEmpty() || ".".equals(value)) {
            return buffer.getLineIndexAt(buffer.getCursor().getPosition());
        }
        int base;
        String offset = "";
        if (value.charAt(0) == '$') {
            base = buffer.getLineCount() - 1;
            offset = value.substring(1);
        } else if (value.charAt(0) == '.') {
            base = buffer.getLineIndexAt(buffer.getCursor().getPosition());
            offset = value.substring(1);
        } else if (value.charAt(0) == '+' || value.charAt(0) == '-') {
            base = buffer.getLineIndexAt(buffer.getCursor().getPosition());
            offset = value;
        } else {
            int lineNumber = Integer.parseInt(value);
            if (destination && lineNumber == 0) {
                return -1;
            }
            return Math.max(0, Math.min(buffer.getLineCount() - 1, lineNumber - 1));
        }
        if (!offset.isBlank()) {
            base += Integer.parseInt(offset);
        }
        return Math.max(destination ? -1 : 0, Math.min(buffer.getLineCount() - 1, base));
    }

    private static int resolveLineAddress(org.fisk.swim.text.Buffer buffer, String address) {
        String value = address == null ? "." : address.trim();
        if (value.isEmpty() || ".".equals(value)) {
            return buffer.getLineIndexAt(buffer.getCursor().getPosition());
        }
        if ("$".equals(value)) {
            return buffer.getLineCount() - 1;
        }
        return Math.max(0, Math.min(buffer.getLineCount() - 1, Integer.parseInt(value) - 1));
    }

    private static List<com.googlecode.lanterna.input.KeyStroke> parseNormalCommandKeys(String argument) {
        var keys = new ArrayList<com.googlecode.lanterna.input.KeyStroke>();
        if (argument.contains("<") || argument.contains(" ")) {
            try {
                for (var key : org.fisk.swim.event.RecordedKey.parseSequence(argument)) {
                    keys.add(key.toKeyStroke());
                }
                return keys;
            } catch (IllegalArgumentException ignored) {
                keys.clear();
            }
        }
        for (int i = 0; i < argument.length(); i++) {
            keys.add(new com.googlecode.lanterna.input.KeyStroke(argument.charAt(i), false, false));
        }
        return keys;
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        int width = rect.getSize().getWidth();
        var line = new AttributedString();

        if (_message != null) {
            TextColor tone = _message.startsWith("Unknown")
                    || _message.startsWith("Failed")
                    || _message.startsWith("Wrong")
                    || _message.startsWith("File does not exist")
                            ? UiTheme.COMMAND_ERROR
                            : UiTheme.COMMAND_SUCCESS;
            line.append(" notice ", UiTheme.TEXT_ON_ACCENT, tone);
            line.append(" " + _message, UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_INACTIVE_BACKGROUND);
        } else if (_command != null) {
            TextColor promptTone = isSearch() ? UiTheme.COMMAND_SEARCH : UiTheme.COMMAND_PROMPT;
            String label = isSearch() ? " search " : " command ";
            line.append(label, UiTheme.TEXT_ON_ACCENT, promptTone);
            line.append(" " + _prompt + _command, UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_BACKGROUND);
        } else {
            line.append(" normal ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
            line.append(" : commands  / search  ? reverse-search ", UiTheme.TEXT_MUTED,
                    UiTheme.COMMAND_INACTIVE_BACKGROUND);
        }
        UiTheme.drawLine(graphics, rect.getPoint(), width, line, UiTheme.TEXT_MUTED,
                _command != null ? UiTheme.COMMAND_BACKGROUND : UiTheme.COMMAND_INACTIVE_BACKGROUND);
    }

    @Override
    public Cursor getCursor() {
        return _command == null ? null : _cursor;
    }

    private Point cursorScreenPosition() {
        Point origin = absoluteOrigin();
        int width = Math.max(1, getBounds().getSize().getWidth());
        String label = isSearch() ? " search " : " command ";
        int commandLength = _command == null ? 0 : _command.length();
        int x = Math.min(width - 1, label.length() + 1 + (_prompt == null ? 0 : _prompt.length()) + commandLength);
        return Point.create(origin.getX() + Math.max(0, x), origin.getY());
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (var parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    public void setMessage(String message) {
        _message = message;
        setNeedsRedraw();
    }

    boolean isActive() {
        return _command != null;
    }

    boolean isEditorDriveOwned() {
        return _editorDriveOwned;
    }

    boolean isEditorDriveCommandPrompt() {
        return isActive() && _editorDriveOwned && isCommandPrompt();
    }

    boolean isEditorDriveOwnedPrompt() {
        return isActive() && _editorDriveOwned;
    }

    String getPrompt() {
        return _prompt;
    }

    String getCommandText() {
        return _command == null ? "" : _command.toString();
    }

    CommandMenuState getMenuState() {
        if (_command == null || !isCommandPrompt()) {
            return CommandMenuState.hidden();
        }
        if (parseLiveGrepCommand(_command.toString()) != null) {
            return CommandMenuState.hidden();
        }
        var matches = matchingCommandSpecs().stream()
                .map(this::withDiscoveredShortcut)
                .toList();
        return new CommandMenuState(true, commandPrefix(), List.copyOf(matches), normalizeSelection(matches.size()));
    }

    private CommandSpec withDiscoveredShortcut(CommandSpec spec) {
        var window = Window.getInstance();
        if (window == null) {
            return spec;
        }
        return spec.withShortcutLabel(window.shortcutForCommand(spec.primaryName()));
    }

    public void activate(String prompt) {
        activate(prompt, "");
    }

    public void activate(String prompt, String initialText) {
        _message = null;
        _prompt = prompt;
        _command = new StringBuilder(initialText == null ? "" : initialText);
        clearLiveGrepPreview();
        resetCommandSelection();
        var window = Window.getInstance();
        _editorDriveOwned = window != null && window.isEditorDriveInputActive();
        if (_editorDriveOwned) {
            window.allowEditorDriveAction(isCommandPrompt() ? "open command prompt" : "open search prompt");
        }
        var rootView = window.getRootView();
        rootView.setFirstResponder(this);
        window.refreshChromeState();
        rootView.setNeedsRedraw();
    }

    public void execute(String rawCommand) {
        _message = null;
        runCommand(rawCommand);
        refreshChrome();
    }

    public void deactivate() {
        deactivate(false);
    }

    private void deactivate(boolean submitted) {
        if (submitted) {
            clearLiveGrepPreview();
        } else {
            cancelLiveGrepPreview();
        }
        _command = null;
        _prompt = null;
        _editorDriveOwned = false;
        resetCommandSelection();
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        var rootView = window.getRootView();
        var activeView = window.getActiveView();
        rootView.setFirstResponder(activeView != null ? activeView : window.getBufferContext().getBufferView());
        window.refreshChromeState();
        rootView.setNeedsRedraw();
    }

    enum CommandKeyAction {
        NONE,
        PREVIOUS_MATCH,
        NEXT_MATCH,
        COMPLETE_MATCH
    }

    private record SubstituteCommand(boolean wholeBuffer, String rangePrefix, String pattern, String replacement, boolean global) {
    }

    private record GlobalCommand(boolean invert, String pattern, String command) {
    }

    private record MoveCommand(String rangePrefix, String destination) {
    }

    private record LineRange(int startLine, int endLine) {
    }

    private record LiveGrepCommand(String query) {
    }

    public static record CommandSpec(
            String primaryName,
            List<String> aliases,
            String arguments,
            String description,
            String replacementText,
            boolean replaceEntireInput,
            String displayLabel,
            String shortcutLabel) {
        public CommandSpec(String primaryName, List<String> aliases, String arguments, String description) {
            this(primaryName, aliases, arguments, description, primaryName, false, "", "");
        }

        public CommandSpec(String primaryName, List<String> aliases, String arguments, String description,
                String shortcutLabel) {
            this(primaryName, aliases, arguments, description, primaryName, false, "", shortcutLabel);
        }

        public CommandSpec(String primaryName, List<String> aliases, String arguments, String description,
                String replacementText, boolean replaceEntireInput) {
            this(primaryName, aliases, arguments, description, replacementText, replaceEntireInput, "", "");
        }

        public CommandSpec(String primaryName, List<String> aliases, String arguments, String description,
                String replacementText, boolean replaceEntireInput, String displayLabel) {
            this(primaryName, aliases, arguments, description, replacementText, replaceEntireInput, displayLabel, "");
        }

        CommandSpec withShortcutLabel(String shortcutLabel) {
            return new CommandSpec(primaryName, aliases, arguments, description, replacementText, replaceEntireInput,
                    displayLabel, shortcutLabel == null ? "" : shortcutLabel);
        }

        static CommandSpec lastCommand(String command) {
            return new CommandSpec(command, List.of(), "", "last command", command, true);
        }

        boolean expectsArgument() {
            return !arguments.isBlank();
        }

        boolean matches(String prefix) {
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (primaryName.startsWith(normalizedPrefix)) {
                return true;
            }
            for (var alias : aliases) {
                if (alias.startsWith(normalizedPrefix)) {
                    return true;
                }
            }
            return false;
        }

        int matchScore(String prefix) {
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (normalizedPrefix.isBlank()) {
                return 0;
            }
            if (primaryName.equals(normalizedPrefix)) {
                return 0;
            }
            for (var alias : aliases) {
                if (alias.equals(normalizedPrefix)) {
                    return 1;
                }
            }
            if (primaryName.startsWith(normalizedPrefix)) {
                return 2;
            }
            for (var alias : aliases) {
                if (alias.startsWith(normalizedPrefix)) {
                    return 3;
                }
            }
            return 4;
        }

        String replacement() {
            return replacementText == null ? primaryName : replacementText;
        }

        String label() {
            if (displayLabel != null && !displayLabel.isBlank()) {
                return displayLabel;
            }
            return ":" + primaryName + (arguments.isBlank() ? "" : " " + arguments);
        }

        String detail() {
            if (aliases.isEmpty()) {
                return description;
            }
            return description + "  alias " + aliases.stream().map(alias -> ":" + alias).reduce((l, r) -> l + ", " + r).orElse("");
        }
    }

    public static record CommandMenuState(boolean visible, String prefix, List<CommandSpec> matches, int selection,
            String title) {
        public CommandMenuState(boolean visible, String prefix, List<CommandSpec> matches, int selection) {
            this(visible, prefix, matches, selection, "command matches");
        }

        public static CommandMenuState hidden() {
            return new CommandMenuState(false, "", List.of(), 0);
        }

        public static CommandMenuState forCommandText(String text) {
            return forCommandText(text, 0);
        }

        public static CommandMenuState forCommandText(String text, int selection) {
            return forCommandText(text, selection, matchingCommandSpecs(commandPrefix(text == null ? "" : text)));
        }

        public static CommandMenuState forCommandText(String text, int selection, List<CommandSpec> commandSpecs) {
            return forCommandText(text, selection, commandSpecs, "command matches");
        }

        public static CommandMenuState forCommandText(String text, int selection, List<CommandSpec> commandSpecs,
                String title) {
            String prefix = commandPrefix(text == null ? "" : text);
            var matches = List.copyOf(matchingCommandSpecs(prefix, commandSpecs));
            int normalizedSelection = matches.isEmpty() ? 0 : Math.max(0, Math.min(selection, matches.size() - 1));
            return new CommandMenuState(true, prefix, matches, normalizedSelection, title);
        }

        public CommandSpec selectedMatch() {
            if (matches.isEmpty()) {
                return null;
            }
            return matches.get(Math.max(0, Math.min(selection, matches.size() - 1)));
        }
    }
}
