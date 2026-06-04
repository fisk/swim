package org.fisk.swim.ui;

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
import org.fisk.swim.help.HelpIndex;
import org.fisk.swim.mail.MailUiSupport;
import org.fisk.swim.slack.SlackUiSupport;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class CommandView extends View {
    static final int MAX_VISIBLE_COMMANDS = 8;
    private static final List<CommandSpec> COMMAND_SPECS = List.of(
            new CommandSpec("q", List.of(), "", "quit SWIM"),
            new CommandSpec("e", List.of(), "<path>", "open or create a file"),
            new CommandSpec("debug", List.of("dbg"), "[providers|open|stop|continue|next|step|out|break|<provider> ...]",
                    "open the debugger or run debugger commands"),
            new CommandSpec("git", List.of(), "[status]", "open the Git workspace"),
            new CommandSpec("split", List.of("sp"), "", "split the active pane below"),
            new CommandSpec("vsplit", List.of("vs"), "", "split the active pane to the right"),
            new CommandSpec("close", List.of(), "", "close the active pane"),
            new CommandSpec("only", List.of(), "", "keep only the active pane"),
            new CommandSpec("focus", List.of(), "left|right|up|down|next|prev", "move focus between panes"),
            new CommandSpec("grep", List.of("search"), "<text>", "search project text"),
            new CommandSpec("help", List.of("h"), "", "open the built-in help"),
            new CommandSpec("mail", List.of(), "", "open the mail client"),
            new CommandSpec("slack", List.of(), "", "open the Slack client"),
            new CommandSpec("nemo", List.of(), "<question>", "ask Nemo about the current file"),
            new CommandSpec("reload", List.of(), "", "reload the latest built SWIM core"),
            new CommandSpec("rebuild", List.of(), "", "rebuild and reload SWIM"),
            new CommandSpec("shell", List.of("sh"), "", "open a shell workspace"),
            new CommandSpec("vshell", List.of(), "", "open a shell in a split to the right"),
            new CommandSpec("hshell", List.of(), "", "open a shell in a split below"),
            new CommandSpec("upgrade", List.of(), "", "alias for :rebuild"),
            new CommandSpec("w", List.of(), "", "write the current buffer"));

    private String _message = null;
    private String _prompt = null;
    private StringBuilder _command = null;
    private int _commandSelection;
    private String _lastCommand = null;
    private ListEventResponder _responders = new ListEventResponder();
    private boolean _searchForward;
    private String _searchString;
    private static final Logger _log = LogFactory.createLog();

    private boolean isSearch() {
        return "/".equals(_prompt) || "?".equals(_prompt);
    }

    public CommandView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.COMMAND_INACTIVE_BACKGROUND);
        _responders.addEventResponder("<ESC>", () -> {
            deactivate();
        });
        _responders.addEventResponder("<ENTER>", () -> {
            if (isSearch()) {
                runSearch(_command.toString());
            } else {
                runCommand(_command.toString());
            }
            if (Window.getInstance() != null) {
                deactivate();
            }
        });
        _responders.addEventResponder("<BACKSPACE>", () -> {
            if (_command.length() > 0) {
                _command.delete(_command.length() - 1, _command.length());
                resetCommandSelection();
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
                case PREVIOUS_MATCH -> moveCommandSelection(-1);
                case NEXT_MATCH -> moveCommandSelection(1);
                case COMPLETE_MATCH -> completeSelectedCommand();
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
                _command.append(_character);
                resetCommandSelection();
                refreshChrome();
            }
        });
    }

    public void runSearch(String string) {
        var quotedString = Pattern.quote(string);
        _log.info("Searching for: " + string);
        Pattern pattern;
        try {
          pattern = Pattern.compile(quotedString);
        } catch (Throwable e) {
            _log.error("Pattern threw exception: ", e);
            return;
        }
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        _searchString = string;
        if (_prompt.equals("/")) {
            _searchForward = true;
            cursor.goNext(pattern);
        } else {
            _searchForward = false;
            cursor.goPrevious(pattern);
        }
    }

    public void searchNext() {
        if (_searchString == null) {
            return;
        }
        var pattern = Pattern.compile(Pattern.quote(_searchString));
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        if (!_searchForward) {
            cursor.goPrevious(pattern);
        } else {
            cursor.goNext(pattern);
        }
    }

    public void searchPrevious() {
        if (_searchString == null) {
            return;
        }
        var pattern = Pattern.compile(Pattern.quote(_searchString));
        var cursor = Window.getInstance().getBufferContext().getBuffer().getCursor();
        if (!_searchForward) {
            cursor.goNext(pattern);
        } else {
            cursor.goPrevious(pattern);
        }
    }

    private void runCommand(String rawCommand) {
        rawCommand = rawCommand.trim();
        if (rawCommand.equals("")) {
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
            SwimRuntime.exit();
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
        case "grep":
        case "search":
            ProjectSearchUiSupport.open(Window.getInstance(), argument);
            break;
        case "h":
        case "help":
            Window.getInstance().showList(HelpIndex.createHelpList(), "SWIM Help");
            break;
        case "mail":
            MailUiSupport.toggle(Window.getInstance());
            break;
        case "slack":
            SlackUiSupport.toggle(Window.getInstance());
            break;
        case "nemo":
            org.fisk.swim.nemo.NemoClient.getInstance().run(Window.getInstance().getBufferContext(), argument);
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
            Window.getInstance().getBufferContext().getBuffer().write();
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
    }

    private void splitBuffer(boolean vertical) {
        var window = Window.getInstance();
        if (window.getActiveView() instanceof ShellPanelView) {
            boolean opened = vertical ? window.showShellSplitHorizontally() : window.showShellSplitVertically();
            if (!opened) {
                _message = "Failed to split view";
            }
            return;
        }
        var splitView = vertical ? window.splitActiveBufferHorizontally() : window.splitActiveBufferVertically();
        if (splitView == null) {
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

    public void setMessage(String message) {
        _message = message;
        setNeedsRedraw();
    }

    boolean isActive() {
        return _command != null;
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
        var matches = matchingCommandSpecs();
        return new CommandMenuState(true, commandPrefix(), List.copyOf(matches), normalizeSelection(matches.size()));
    }

    public void activate(String prompt) {
        _message = null;
        _prompt = prompt;
        _command = new StringBuilder();
        resetCommandSelection();
        var window = Window.getInstance();
        var rootView = window.getRootView();
        rootView.setFirstResponder(this);
        window.refreshChromeState();
        rootView.setNeedsRedraw();
    }

    public void deactivate() {
        _command = null;
        _prompt = null;
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

    public static record CommandSpec(
            String primaryName,
            List<String> aliases,
            String arguments,
            String description,
            String replacementText,
            boolean replaceEntireInput) {
        public CommandSpec(String primaryName, List<String> aliases, String arguments, String description) {
            this(primaryName, aliases, arguments, description, primaryName, false);
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
            return ":" + primaryName + (arguments.isBlank() ? "" : " " + arguments);
        }

        String detail() {
            if (aliases.isEmpty()) {
                return description;
            }
            return description + "  alias " + aliases.stream().map(alias -> ":" + alias).reduce((l, r) -> l + ", " + r).orElse("");
        }
    }

    public static record CommandMenuState(boolean visible, String prefix, List<CommandSpec> matches, int selection) {
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
            String prefix = commandPrefix(text == null ? "" : text);
            var matches = List.copyOf(matchingCommandSpecs(prefix, commandSpecs));
            int normalizedSelection = matches.isEmpty() ? 0 : Math.max(0, Math.min(selection, matches.size() - 1));
            return new CommandMenuState(true, prefix, matches, normalizedSelection);
        }

        public CommandSpec selectedMatch() {
            if (matches.isEmpty()) {
                return null;
            }
            return matches.get(Math.max(0, Math.min(selection, matches.size() - 1)));
        }
    }
}
