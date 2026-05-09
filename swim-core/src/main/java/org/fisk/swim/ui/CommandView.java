package org.fisk.swim.ui;

import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.help.HelpIndex;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class CommandView extends View {
    private String _message = null;
    private String _prompt = null;
    private StringBuilder _command = null;
    private ListEventResponder _responders = new ListEventResponder();
    private boolean _searchForward;
    private String _searchString;
    private static final Logger _log = LogFactory.createLog();    

    private boolean isSearch() {
        // Is this hacky? Yeah it is. Buy hey deal with it later.
        return _prompt.equals("/") || _prompt.equals("?");
    }

    public CommandView(Rect bounds) {
        super(bounds);
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
                CommandView.this.setNeedsRedraw();
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
                CommandView.this.setNeedsRedraw();
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
        case "h":
        case "help":
            Window.getInstance().showList(HelpIndex.createHelpList(), "SWIM Help");
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
        case "w":
            Window.getInstance().getBufferContext().getBuffer().write();
            break;
        default:
            _message = "Unknown command: " + command;
            break;
        }
    }
    
    private void open(String pathString) {
        if (pathString.equals("")) {
            _message = "Wrong number of parameters";
            return;
        }
        var path = Paths.get(pathString).toAbsolutePath();
        if (!path.toFile().exists()) {
            try {
                if (path.toFile().createNewFile()) {
                    Window.getInstance().setBufferPath(path);
                    return;
                }
            } catch (Exception e) {
            }
            _message = "File does not exist";
        } else {
            Window.getInstance().setBufferPath(path);
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

        graphics.setBackgroundColor(TextColor.ANSI.BLACK);
        graphics.drawRectangle(new TerminalPosition(rect.getPoint().getX(), rect.getPoint().getY()),
        new TerminalSize(rect.getSize().getWidth(), 1), ' ');

        if (_message != null) {
            var message = AttributedString.create(_message, TextColor.ANSI.DEFAULT, _backgroundColour);
            message.drawAt(rect.getPoint(), graphics);
        } else if (_command != null) {
            var message = AttributedString.create(_prompt + _command, TextColor.ANSI.DEFAULT, _backgroundColour);
            message.drawAt(rect.getPoint(), graphics);
        }
    }

    public void setMessage(String message) {
        _message = message;
        setNeedsRedraw();
    }

    public void activate(String prompt) {
        _message = null;
        _prompt = prompt;
        _command = new StringBuilder();
        var window = Window.getInstance();
        var rootView = window.getRootView();
        rootView.setFirstResponder(this);
        rootView.setNeedsRedraw();
    }

    public void deactivate() {
        _command = null;
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        var rootView = window.getRootView();
        rootView.setFirstResponder(window.getBufferContext().getBufferView());
        rootView.setNeedsRedraw();
    }
}
