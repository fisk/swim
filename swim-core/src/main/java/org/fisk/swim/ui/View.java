package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

public class View implements Drawable, EventResponder {
    private static final Logger _log = LogFactory.createLog();
    private View _parent;
    private List<View> _subviews = new ArrayList<>();
    private Rect _bounds;
    protected TextColor _backgroundColour;
    private boolean _needsRedraw = true;
    private EventResponder _firstResponder;
    private EventResponder _lastResponder;
    private Runnable _lastMouseAction;
    private int _resizeMask = RESIZE_MASK_LEFT |
        RESIZE_MASK_RIGHT |
        RESIZE_MASK_TOP |
        RESIZE_MASK_BOTTOM;

    public static final int RESIZE_MASK_LEFT = 1;
    public static final int RESIZE_MASK_RIGHT = 2;
    public static final int RESIZE_MASK_TOP = 4;
    public static final int RESIZE_MASK_BOTTOM = 8;
    public static final int RESIZE_MASK_WIDTH = 16;
    public static final int RESIZE_MASK_HEIGHT = 32;

    public View(Rect bounds) {
        _bounds = bounds;
    }

    public void setFirstResponder(EventResponder responder) {
        _firstResponder = responder;
    }

    public EventResponder getFirstResponder() {
        return _firstResponder;
    }

    public Cursor getCursor() {
        var responder = _firstResponder;
        if (responder instanceof View) {
            return ((View) responder).getCursor();
        }
        return null;
    }

    @Override
    public void draw(Rect rect) {
        var terminalContext = TerminalContext.getInstance();
        var textGraphics = terminalContext.getGraphics();
        if (_backgroundColour != null) {
            textGraphics.setBackgroundColor(_backgroundColour);
            textGraphics.fillRectangle(new TerminalPosition(rect.getPoint().getX(), rect.getPoint().getY()),
                                       new TerminalSize(rect.getSize().getWidth(), rect.getSize().getHeight()), ' ');
        }
    }

    public void update(Rect rect, boolean forced) {
        if (!forced && !_needsRedraw) {
            return;
        }
        _needsRedraw = false;
        draw(rect);
        for (View view: _subviews) {
            var subRect = Rect.create(rect.getPoint().getX() + view._bounds.getPoint().getX(),
                                      rect.getPoint().getY() + view._bounds.getPoint().getY(), view._bounds.getSize().getWidth(),
                                      view._bounds.getSize().getHeight());
            view.update(subRect, true /* forced */);
        }
    }

    public void setNeedsRedraw() {
        View view = this;
        while (view != null) {
            view._needsRedraw = true;
            view = view._parent;
        }
    }

    public boolean needsRedraw() {
        return _needsRedraw;
    }

    public View getParent() {
        return _parent;
    }

    public void addSubview(View view) {
        _subviews.add(view);
        view._parent = this;
    }

    public void insertSubview(int index, View view) {
        if (index < 0) {
            index = 0;
        }
        if (index > _subviews.size()) {
            index = _subviews.size();
        }
        _subviews.add(index, view);
        view._parent = this;
    }

    public void removeFromParent() {
        if (_parent != null) {
            _parent._subviews.remove(this);
            _parent = null;
        }
    }

    public void setBackgroundColour(TextColor colour) {
        _backgroundColour = colour;
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        _lastMouseAction = null;
        if (events.remaining() == 0 && events.current() instanceof MouseAction mouseAction) {
            return processMouseAction(mouseAction);
        }
        var firstResponder = _firstResponder;
        if (firstResponder == null) {
            return Response.NO;
        }
        var result = firstResponder.processEvent(events);
        if (result == Response.YES) {
            _lastResponder = firstResponder;
        }
        return result;
    }

    @Override
    public void respond() {
        if (_lastMouseAction != null) {
            _lastMouseAction.run();
            _lastMouseAction = null;
            return;
        }
        if (_lastResponder != null) {
            _lastResponder.respond();
            _lastResponder = null;
        }
    }

    private Response processMouseAction(MouseAction action) {
        View target = findTopmostSubviewAt(action.getPosition());
        if (target != null) {
            if (action.getActionType() == MouseActionType.CLICK_DOWN) {
                focusViewForMouse(target);
            }
            var response = target.processEvent(new KeyStrokes(List.of(action)));
            if (response == Response.YES) {
                _lastResponder = target;
                return Response.YES;
            }
            if (response == Response.MAYBE) {
                return Response.MAYBE;
            }
        }
        if (action.getActionType() == MouseActionType.CLICK_DOWN) {
            Runnable clickAction = AttributedString.clickActionAt(
                    Point.create(action.getPosition().getColumn(), action.getPosition().getRow()));
            if (clickAction != null) {
                _lastMouseAction = clickAction;
                return Response.YES;
            }
        }
        return Response.NO;
    }

    private View findTopmostSubviewAt(TerminalPosition position) {
        if (position == null) {
            return null;
        }
        for (int i = _subviews.size() - 1; i >= 0; i--) {
            View view = _subviews.get(i);
            if (view.containsAbsolute(position.getColumn(), position.getRow())) {
                return view;
            }
        }
        return null;
    }

    private boolean containsAbsolute(int x, int y) {
        Point origin = absoluteOrigin();
        return x >= origin.getX()
                && y >= origin.getY()
                && x < origin.getX() + _bounds.getSize().getWidth()
                && y < origin.getY() + _bounds.getSize().getHeight();
    }

    private Point absoluteOrigin() {
        int x = _bounds.getPoint().getX();
        int y = _bounds.getPoint().getY();
        for (View parent = _parent; parent != null; parent = parent._parent) {
            x += parent._bounds.getPoint().getX();
            y += parent._bounds.getPoint().getY();
        }
        return Point.create(x, y);
    }

    private static void focusViewForMouse(View view) {
        var window = Window.getInstance();
        if (window == null || view == null) {
            return;
        }
        if (view instanceof BufferView || view instanceof ChatPanelView || view instanceof ShellPanelView || view instanceof ListView
                || view instanceof ProjectSearchPanelView || view instanceof TextPanelView
                || view instanceof PluginPanelView || view instanceof HelpWorkspaceView) {
            window.activateView(view);
        }
    }

    public void setResizeMask(int resizeMask) {
        _resizeMask = resizeMask;
    }

    private boolean isPinned(int mask) {
        return (_resizeMask & mask) != 0;
    }

    public void setBounds(Rect rect) {
        _bounds = rect;
    }

    public void resize(Size newParentSize) {
        Size parentSize = _parent == null ? _bounds.getSize() : _parent._bounds.getSize();
        int left = _bounds.getPoint().getX();
        int right = parentSize.getWidth() - (left + _bounds.getSize().getWidth());
        int top = _bounds.getPoint().getY();
        int bottom = parentSize.getHeight() - (top + _bounds.getSize().getHeight());
        int width = _bounds.getSize().getWidth();
        int height = _bounds.getSize().getHeight();

        int newLeft;
        int newRight;
        int newTop;
        int newBottom;

        if (isPinned(RESIZE_MASK_LEFT)) {
            newLeft = left;
        } else {
            if (!isPinned(RESIZE_MASK_RIGHT) || !isPinned(RESIZE_MASK_WIDTH)) {
                throw new RuntimeException("Layout not supported yet");
            }
            newLeft = newParentSize.getWidth() - width - right; 
        }

        if (isPinned(RESIZE_MASK_RIGHT)) {
            newRight = right;
        } else {
            if (!isPinned(RESIZE_MASK_LEFT) || !isPinned(RESIZE_MASK_WIDTH)) {
                throw new RuntimeException("Layout not supported yet");
            }
            newRight = newParentSize.getWidth() - width - left;
        }

        if (isPinned(RESIZE_MASK_TOP)) {
            newTop = top;
        } else {
            if (!isPinned(RESIZE_MASK_BOTTOM) || !isPinned(RESIZE_MASK_HEIGHT)) {
                throw new RuntimeException("Layout not supported yet");
            }
            newTop = newParentSize.getHeight() - height - bottom;
        }

        if (isPinned(RESIZE_MASK_BOTTOM)) {
            newBottom = bottom;
        } else {
            if (!isPinned(RESIZE_MASK_TOP) || !isPinned(RESIZE_MASK_HEIGHT)) {
                throw new RuntimeException("Layout not supported yet");
            }
            newBottom = newParentSize.getHeight() - height - top;
        }

        int newWidth = newParentSize.getWidth() - newLeft - newRight;
        int newHeight = newParentSize.getHeight() - newTop - newBottom;

        for (View view : _subviews) {
            view.resize(Size.create(newWidth, newHeight));
        }

        _bounds = Rect.create(newLeft, newTop, newWidth, newHeight);
        _log.debug("Resizing view to " + _bounds);
    }

    public Rect getBounds() {
        return _bounds;
    }
}
