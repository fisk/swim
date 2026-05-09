package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Path;
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
    private static Logger _log = LogFactory.createLog();
    private static Window _instance;

    public static Window getInstance() {
        return _instance;
    }

    public static void createInstance(Path path) {
        _instance = new Window(path);
    }

    private View _rootView;
    private ModeLineView _modeLineView;
    private CommandView _commandView;
    private Size _size;
    private BufferContext _bufferContext;
    private NormalMode _normalMode;
    private InputMode _inputMode;
    private VisualMode _visualMode;
    private VisualLineMode _visualLineMode;
    private VisualBlockMode _visualBlockMode;
    private Mode _currentMode;

    private void setupModes() {
        _normalMode = new NormalMode(this);
        _inputMode = new InputMode(this);
        _visualMode = new VisualMode(this);
        _visualLineMode = new VisualLineMode(this);
        _visualBlockMode = new VisualBlockMode(this);
        _currentMode = _normalMode;
        _bufferContext.getBufferView().setFirstResponder(_currentMode);
    }

    public void setBufferPath(Path path) {
        var bufferView = _bufferContext.getBufferView();
        bufferView.removeFromParent();
        var rect = bufferView.getBounds();
        _bufferContext.getBuffer().close();
        _bufferContext = new BufferContext(rect, path);
        _rootView.addSubview(_bufferContext.getBufferView());
        _rootView.setFirstResponder(_bufferContext.getBufferView());
        setupModes();
    }

    private void setupSplashScreen() {
        var terminalContext = TerminalContext.getInstance();
        var screen = terminalContext.getScreen();
        var terminalSize = screen.getTerminalSize();
        var textGraphics = terminalContext.getGraphics();
        _log.info("Draw splash screen");
        var attrString = new AttributedString();
        var str = "== loading swim ==";
        attrString.append(str, TextColor.ANSI.CYAN, TextColor.ANSI.DEFAULT);
        attrString.drawAt(Point.create(terminalSize.getColumns() / 2 - str.length() / 2, terminalSize.getRows() / 2), textGraphics);
        screen.setCursorPosition(new TerminalPosition(0, 0));
        try {
            screen.refresh(RefreshType.DELTA);
        } catch (IOException e) {}
    }

    private void setupViews(Path path) {
        var terminalContext = TerminalContext.getInstance();
        var terminalSize = terminalContext.getScreen().getTerminalSize();

        _log.info("Terminal size: " + terminalSize.getColumns() + ", " + terminalSize.getRows());

        _bufferContext = new BufferContext(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows() - 2), path);
        _rootView = new View(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows()));
        _rootView.setBackgroundColour(TextColor.ANSI.DEFAULT);

        _modeLineView = new ModeLineView(Rect.create(0, terminalSize.getRows() - 2, terminalSize.getColumns(), 1));
        _modeLineView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_modeLineView);

        _commandView = new CommandView(Rect.create(0, terminalSize.getRows() - 1, terminalSize.getColumns(), 1));
        _commandView.setResizeMask(View.RESIZE_MASK_BOTTOM | View.RESIZE_MASK_LEFT | View.RESIZE_MASK_RIGHT | View.RESIZE_MASK_HEIGHT);
        _rootView.addSubview(_commandView);

        _rootView.addSubview(_bufferContext.getBufferView());
        _rootView.setFirstResponder(_bufferContext.getBufferView());

        _size = _rootView.getBounds().getSize();
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

    public Window(Path path) {
        setupSplashScreen();
        setupViews(path);
        setupBindings();
        setupModes();
    }

    public CommandView getCommandView() {
        return _commandView;
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
        _currentMode.deactivate();
        _currentMode = mode;
        _bufferContext.getBufferView().setFirstResponder(_currentMode);
        _modeLineView.setNeedsRedraw();
        mode.activate();
    }

    public BufferContext getBufferContext() {
        return _bufferContext;
    }

    public ModeLineView getModeLineView() {
        return _modeLineView;
    }

    public void dispose() {
        if (_bufferContext != null) {
            _bufferContext.getBuffer().close();
        }
        if (_modeLineView != null) {
            _modeLineView.close();
        }
        _instance = null;
    }

    public void setRootView(View view) {
        _rootView = view;
        _size = view.getBounds().getSize();
    }

    public View getRootView() {
        return _rootView;
    }

    public void update(boolean forced) {
        _log.info("Maybe relayout");
        if (!forced && !_rootView.needsRedraw()) {
            _log.info("Relayout not needed");
            return;
        }
        var screen = TerminalContext.getInstance().getScreen();
        var terminalSize = screen.doResizeIfNecessary();
        if (terminalSize == null) {
            terminalSize = new TerminalSize(_rootView.getBounds().getSize().getWidth(),
                    _rootView.getBounds().getSize().getHeight());
        }
        _log.info("Terminal size: " + terminalSize.getColumns() + ", " + terminalSize.getRows());
        var size = Size.create(terminalSize.getColumns(), terminalSize.getRows());
        if (_size != null && !_size.equals(size)) {
            _log.info("Relayout");
            _rootView.resize(size);
        } else {
            _log.info("Relayout not needed due to same size");
        }
        _rootView.update(Rect.create(0, 0, terminalSize.getColumns(), terminalSize.getRows()), forced);
        _size = size;
        var cursor = _rootView.getCursor();
        if (cursor != null) {
            screen.setCursorPosition(new TerminalPosition(cursor.getX(), cursor.getYRelative()));
        }
        try {
            screen.refresh(RefreshType.DELTA);
        } catch (IOException e) {}
    }

    @Override
    public void draw(Rect rect) {
        _rootView.draw(rect);
    }

    private View _panelView;

    public boolean isShowingList() {
        return _panelView instanceof ListView;
    }

    public boolean isShowingPanel() {
        return _panelView != null;
    }

    View getPanelView() {
        return _panelView;
    }

    public void showPanel(View panelView) {
        if (_panelView != null) {
            return;
        }
        var bufferView = _bufferContext.getBufferView();
        var rect = bufferView.getBounds();
        int height = rect.getSize().getHeight();
        int newHeight = height * 2 / 3;
        bufferView.setBounds(Rect.create(rect.getPoint().getX(), rect.getPoint().getY(),
                rect.getSize().getWidth(), newHeight));
        bufferView.setNeedsRedraw();
        panelView.setBounds(Rect.create(rect.getPoint().getX(), rect.getPoint().getY() + newHeight,
                rect.getSize().getWidth(), height - newHeight));
        _rootView.addSubview(panelView);
        _rootView.setFirstResponder(panelView);
        _panelView = panelView;
        _rootView.setNeedsRedraw();
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
        var bufferView = _bufferContext.getBufferView();
        var rect = bufferView.getBounds();
        int newHeight = rect.getSize().getHeight() + _panelView.getBounds().getSize().getHeight();
        bufferView.setBounds(Rect.create(rect.getPoint().getX(), rect.getPoint().getY(),
                rect.getSize().getWidth(), newHeight));
        bufferView.setNeedsRedraw();
        _panelView.removeFromParent();
        _rootView.setFirstResponder(_bufferContext.getBufferView());
        _rootView.setNeedsRedraw();
        _panelView = null;
    }
}
