package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

public class SplitView extends View {
    private static final int DIVIDER_THICKNESS = 1;
    private static final int FRAME_BAR_THICKNESS = 1;

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    public record SessionNode(
            String orientation,
            double ratio,
            SessionNode first,
            SessionNode second,
            String leafId) {
    }

    private sealed interface Node permits LeafNode, BranchNode {
    }

    private static final class LeafNode implements Node {
        private View _view;
        private Rect _frameRect = Rect.create(0, 0, 0, 0);
        private Rect _contentRect = Rect.create(0, 0, 0, 0);

        private LeafNode(View view) {
            _view = view;
        }
    }

    private static final class BranchNode implements Node {
        private final Orientation _orientation;
        private double _ratio;
        private Node _first;
        private Node _second;
        private Rect _bounds = Rect.create(0, 0, 0, 0);

        private BranchNode(Orientation orientation, double ratio, Node first, Node second) {
            _orientation = orientation;
            _ratio = Math.max(0.0, Math.min(1.0, ratio));
            _first = first;
            _second = second;
        }
    }

    private record DividerSegment(BranchNode branch, int x, int y, int height) {
    }

    private record HorizontalSegment(int x, int y, int width) {
    }

    private Node _root;

    public SplitView(Rect bounds, Orientation orientation, View firstView, View secondView) {
        this(bounds, orientation, firstView, secondView, 0.5);
    }

    public SplitView(Rect bounds, Orientation orientation, View firstView, View secondView, double ratio) {
        super(bounds);
        _root = new BranchNode(orientation, ratio, new LeafNode(firstView), new LeafNode(secondView));
        addSubview(firstView);
        addSubview(secondView);
        layoutTree(bounds);
    }

    public Orientation getOrientation() {
        return _root instanceof BranchNode branch ? branch._orientation : null;
    }

    public View getFirstView() {
        return _root instanceof BranchNode branch ? firstLeaf(branch._first) : firstLeaf(_root);
    }

    public View getSecondView() {
        return _root instanceof BranchNode branch ? firstLeaf(branch._second) : null;
    }

    public View getSibling(View view) {
        return siblingOf(_root, view);
    }

    public boolean containsLeaf(View view) {
        return containsLeaf(_root, view);
    }

    public List<View> leafViews() {
        var leaves = new ArrayList<View>();
        collectLeafViews(_root, leaves);
        return List.copyOf(leaves);
    }

    public View firstLeaf() {
        return firstLeaf(_root);
    }

    public boolean isSingleLeaf() {
        return _root instanceof LeafNode;
    }

    public boolean resizeLeaf(View target, Orientation orientation, int deltaCells) {
        if (target == null || deltaCells == 0) {
            return false;
        }
        var branch = findNearestBranch(_root, target, orientation);
        if (branch == null) {
            return false;
        }
        int available = availableSize(branch);
        if (available <= 1) {
            return false;
        }
        double deltaRatio = (double) deltaCells / available;
        if (containsLeaf(branch._first, target)) {
            branch._ratio += deltaRatio;
        } else if (containsLeaf(branch._second, target)) {
            branch._ratio -= deltaRatio;
        } else {
            return false;
        }
        double minRatio = 1.0 / available;
        double maxRatio = (available - 1.0) / available;
        branch._ratio = Math.max(minRatio, Math.min(maxRatio, branch._ratio));
        layoutTree(getBounds());
        setNeedsRedraw();
        return true;
    }

    public boolean equalize() {
        if (!(_root instanceof BranchNode)) {
            return false;
        }
        equalizeNode(_root);
        layoutTree(getBounds());
        setNeedsRedraw();
        return true;
    }

    public boolean split(View existingView, View newView, Orientation orientation, double ratio, boolean existingFirst) {
        if (existingView == null || newView == null || existingView == newView || !containsLeaf(existingView)) {
            return false;
        }
        addSubview(newView);
        var replacement = existingFirst
                ? new BranchNode(orientation, ratio, new LeafNode(existingView), new LeafNode(newView))
                : new BranchNode(orientation, ratio, new LeafNode(newView), new LeafNode(existingView));
        _root = replaceLeafWithNode(_root, existingView, replacement);
        layoutTree(getBounds());
        setNeedsRedraw();
        return true;
    }

    public boolean replaceLeaf(View currentView, View replacementView) {
        if (currentView == null || replacementView == null || currentView == replacementView || !containsLeaf(currentView)) {
            return false;
        }
        if (currentView.getParent() == this) {
            currentView.removeFromParent();
        }
        addSubview(replacementView);
        _root = replaceLeafView(_root, currentView, replacementView);
        layoutTree(getBounds());
        setNeedsRedraw();
        return true;
    }

    public View removeLeaf(View view) {
        if (view == null || !containsLeaf(view) || leafCount(_root) <= 1) {
            return null;
        }
        var removal = removeLeaf(_root, view);
        if (!removal._removed) {
            return null;
        }
        if (view.getParent() == this) {
            view.removeFromParent();
        }
        _root = removal._node;
        layoutTree(getBounds());
        setNeedsRedraw();
        return removal._focusTarget;
    }

    public View detachSingleLeaf() {
        if (!(_root instanceof LeafNode leaf)) {
            return null;
        }
        var view = leaf._view;
        if (view.getParent() == this) {
            view.removeFromParent();
        }
        return view;
    }

    public SessionNode snapshot(Function<View, String> leafIdProvider) {
        return snapshotNode(_root, leafIdProvider);
    }

    @Override
    public void setBounds(Rect rect) {
        super.setBounds(rect);
        layoutTree(rect);
    }

    @Override
    public void resize(Size newParentSize) {
        var bounds = getBounds();
        setBounds(Rect.create(bounds.getPoint().getX(), bounds.getPoint().getY(),
                newParentSize.getWidth(), newParentSize.getHeight()));
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var dividers = new ArrayList<DividerSegment>();
        var horizontals = new ArrayList<HorizontalSegment>();
        collectDividerSegments(_root, rect.getPoint(), dividers);
        collectHorizontalSegments(_root, rect.getPoint(), horizontals);
        drawHorizontalSegments(horizontals);
        drawVerticalDividers(dividers, horizontals);
        drawFrameBars(rect.getPoint(), _root);
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0 || !(events.current() instanceof MouseAction mouseAction)) {
            return super.processEvent(events);
        }
        Response response = super.processEvent(events);
        if (response != Response.NO) {
            return response;
        }
        if (mouseAction.getActionType() != MouseActionType.CLICK_DOWN) {
            return Response.NO;
        }
        View leaf = leafFrameAt(mouseAction);
        if (leaf == null) {
            return Response.NO;
        }
        var window = Window.getInstance();
        if (window != null) {
            window.activateView(leaf);
        }
        return Response.YES;
    }

    private void layoutTree(Rect bounds) {
        layoutNode(_root, Rect.create(0, 0, bounds.getSize().getWidth(), bounds.getSize().getHeight()));
    }

    private View leafFrameAt(MouseAction action) {
        if (action.getPosition() == null) {
            return null;
        }
        Point origin = absoluteOrigin();
        int x = action.getPosition().getColumn() - origin.getX();
        int y = action.getPosition().getRow() - origin.getY();
        return leafFrameAt(_root, x, y);
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

    private void layoutNode(Node node, Rect bounds) {
        if (node instanceof LeafNode leaf) {
            leaf._frameRect = bounds;
            leaf._contentRect = contentRect(bounds);
            leaf._view.setBounds(leaf._contentRect);
            return;
        }
        var branch = (BranchNode) node;
        branch._bounds = bounds;
        if (branch._orientation == Orientation.HORIZONTAL) {
            int divider = dividerThickness(branch._orientation, bounds.getSize().getWidth());
            int frameWidth = Math.max(0, bounds.getSize().getWidth() - divider);
            int firstWidth = partition(frameWidth, branch._ratio);
            Rect firstBounds = Rect.create(bounds.getPoint().getX(), bounds.getPoint().getY(), firstWidth,
                    bounds.getSize().getHeight());
            Rect secondBounds = Rect.create(bounds.getPoint().getX() + firstWidth + divider, bounds.getPoint().getY(),
                    frameWidth - firstWidth, bounds.getSize().getHeight());
            layoutNode(branch._first, firstBounds);
            layoutNode(branch._second, secondBounds);
            return;
        }
        int divider = dividerThickness(branch._orientation, bounds.getSize().getHeight());
        int frameHeight = Math.max(0, bounds.getSize().getHeight() - divider);
        int firstHeight = partition(frameHeight, branch._ratio);
        Rect firstBounds = Rect.create(bounds.getPoint().getX(), bounds.getPoint().getY(), bounds.getSize().getWidth(),
                firstHeight);
        Rect secondBounds = Rect.create(bounds.getPoint().getX(), bounds.getPoint().getY() + firstHeight + divider,
                bounds.getSize().getWidth(), frameHeight - firstHeight);
        layoutNode(branch._first, firstBounds);
        layoutNode(branch._second, secondBounds);
    }

    private static int partition(int size, double ratio) {
        if (size <= 1) {
            return size;
        }
        int partition = (int) Math.floor(size * ratio);
        if (partition <= 0) {
            return 1;
        }
        if (partition >= size) {
            return size - 1;
        }
        return partition;
    }

    private static int dividerThickness(Orientation orientation, int size) {
        return size >= 3 ? DIVIDER_THICKNESS : 0;
    }

    private static int availableSize(BranchNode branch) {
        return branch._orientation == Orientation.HORIZONTAL
                ? Math.max(0, branch._bounds.getSize().getWidth() - dividerThickness(branch._orientation, branch._bounds.getSize().getWidth()))
                : Math.max(0, branch._bounds.getSize().getHeight() - dividerThickness(branch._orientation, branch._bounds.getSize().getHeight()));
    }

    private static Rect contentRect(Rect frame) {
        if (frame.getSize().getHeight() < FRAME_BAR_THICKNESS + 1) {
            return frame;
        }
        return Rect.create(frame.getPoint().getX(), frame.getPoint().getY(), frame.getSize().getWidth(),
                Math.max(0, frame.getSize().getHeight() - FRAME_BAR_THICKNESS));
    }

    private void drawHorizontalSegments(List<HorizontalSegment> horizontals) {
        var graphics = TerminalContext.getInstance().getGraphics();
        for (var horizontal : horizontals) {
            UiTheme.drawLine(graphics, Point.create(horizontal.x(), horizontal.y()), horizontal.width(),
                    AttributedString.create(UiTheme.repeat("─", horizontal.width()), UiTheme.TEXT_MUTED,
                            UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        }
    }

    private void drawVerticalDividers(List<DividerSegment> dividers, List<HorizontalSegment> horizontals) {
        var graphics = TerminalContext.getInstance().getGraphics();
        for (var divider : dividers) {
            int startY = divider.y();
            int endExclusive = divider.y() + divider.height();
            while (hasHorizontalSegmentAt(horizontals, divider.x(), startY - 1)) {
                startY--;
            }
            while (hasHorizontalSegmentAt(horizontals, divider.x(), endExclusive)) {
                endExclusive++;
            }
            for (int y = startY; y < endExclusive; y++) {
                boolean leftHorizontal = hasHorizontalSegmentAt(horizontals, divider.x() - 1, y);
                boolean rightHorizontal = hasHorizontalSegmentAt(horizontals, divider.x() + 1, y);
                boolean continueUp = y > startY;
                boolean continueDown = y < endExclusive - 1;
                String symbol;
                if (leftHorizontal && rightHorizontal) {
                    symbol = continueUp && continueDown ? "┼" : continueDown ? "┬" : continueUp ? "┴" : "─";
                } else if (leftHorizontal) {
                    symbol = "┤";
                } else if (rightHorizontal) {
                    symbol = "├";
                } else {
                    symbol = "│";
                }
                UiTheme.drawLine(graphics, Point.create(divider.x(), y), 1,
                        AttributedString.create(symbol, UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED),
                        UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
            }
        }
    }

    private static boolean hasHorizontalSegmentAt(List<HorizontalSegment> horizontals, int x, int y) {
        for (var horizontal : horizontals) {
            if (horizontal.y() == y
                    && x >= horizontal.x()
                    && x < horizontal.x() + horizontal.width()) {
                return true;
            }
        }
        return false;
    }

    private void drawFrameBars(Point origin, Node node) {
        if (node instanceof LeafNode leaf) {
            drawFrameBar(origin, leaf);
            return;
        }
        var branch = (BranchNode) node;
        drawFrameBars(origin, branch._first);
        drawFrameBars(origin, branch._second);
    }

    private void drawFrameBar(Point origin, LeafNode leaf) {
        var frame = leaf._frameRect;
        if (frame.getSize().getHeight() < 2 || frame.getSize().getWidth() <= 0) {
            return;
        }
        boolean active = Window.getInstance() != null && Window.getInstance().getActiveView() == leaf._view;
        var barBackground = UiTheme.SURFACE_MUTED;
        int width = frame.getSize().getWidth();
        var line = ModeLineView.leftStringForView(leaf._view, active, barBackground);
        if (line.length() > width) {
            line = line.slice(0, width);
        }
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(),
                Point.create(origin.getX() + frame.getPoint().getX(),
                        origin.getY() + frame.getPoint().getY() + frame.getSize().getHeight() - 1),
                width,
                line,
                UiTheme.TEXT_MUTED,
                barBackground);
    }

    private static void collectDividerSegments(Node node, Point origin, List<DividerSegment> dividers) {
        if (!(node instanceof BranchNode branch)) {
            return;
        }
        if (branch._orientation == Orientation.HORIZONTAL && dividerThickness(branch._orientation, branch._bounds.getSize().getWidth()) > 0) {
            var firstBounds = subtreeBounds(branch._first);
            dividers.add(new DividerSegment(branch,
                    origin.getX() + firstBounds.getPoint().getX() + firstBounds.getSize().getWidth(),
                    origin.getY() + branch._bounds.getPoint().getY(),
                    branch._bounds.getSize().getHeight()));
        }
        collectDividerSegments(branch._first, origin, dividers);
        collectDividerSegments(branch._second, origin, dividers);
    }

    private static void collectHorizontalSegments(Node node, Point origin, List<HorizontalSegment> horizontals) {
        if (!(node instanceof BranchNode branch)) {
            return;
        }
        if (branch._orientation == Orientation.VERTICAL && dividerThickness(branch._orientation, branch._bounds.getSize().getHeight()) > 0) {
            var firstBounds = subtreeBounds(branch._first);
            horizontals.add(new HorizontalSegment(
                    origin.getX() + branch._bounds.getPoint().getX(),
                    origin.getY() + firstBounds.getPoint().getY() + firstBounds.getSize().getHeight(),
                    branch._bounds.getSize().getWidth()));
        }
        collectHorizontalSegments(branch._first, origin, horizontals);
        collectHorizontalSegments(branch._second, origin, horizontals);
    }

    private static Rect subtreeBounds(Node node) {
        if (node instanceof LeafNode leaf) {
            return leaf._frameRect;
        }
        return ((BranchNode) node)._bounds;
    }

    private static Node replaceLeafWithNode(Node node, View target, Node replacement) {
        if (node instanceof LeafNode leaf) {
            return leaf._view == target ? replacement : leaf;
        }
        var branch = (BranchNode) node;
        branch._first = replaceLeafWithNode(branch._first, target, replacement);
        branch._second = replaceLeafWithNode(branch._second, target, replacement);
        return branch;
    }

    private static Node replaceLeafView(Node node, View target, View replacement) {
        if (node instanceof LeafNode leaf) {
            if (leaf._view == target) {
                leaf._view = replacement;
            }
            return leaf;
        }
        var branch = (BranchNode) node;
        branch._first = replaceLeafView(branch._first, target, replacement);
        branch._second = replaceLeafView(branch._second, target, replacement);
        return branch;
    }

    private record RemovalResult(Node _node, boolean _removed, View _focusTarget) {
    }

    private static RemovalResult removeLeaf(Node node, View target) {
        if (node instanceof LeafNode leaf) {
            if (leaf._view == target) {
                return new RemovalResult(null, true, null);
            }
            return new RemovalResult(leaf, false, null);
        }
        var branch = (BranchNode) node;
        var left = removeLeaf(branch._first, target);
        if (left._removed) {
            if (left._node == null) {
                return new RemovalResult(branch._second, true,
                        left._focusTarget != null ? left._focusTarget : firstLeaf(branch._second));
            }
            branch._first = left._node;
            return new RemovalResult(branch, true, left._focusTarget);
        }
        var right = removeLeaf(branch._second, target);
        if (right._removed) {
            if (right._node == null) {
                return new RemovalResult(branch._first, true,
                        right._focusTarget != null ? right._focusTarget : firstLeaf(branch._first));
            }
            branch._second = right._node;
            return new RemovalResult(branch, true, right._focusTarget);
        }
        return new RemovalResult(branch, false, null);
    }

    private static boolean containsLeaf(Node node, View view) {
        if (node instanceof LeafNode leaf) {
            return leaf._view == view;
        }
        var branch = (BranchNode) node;
        return containsLeaf(branch._first, view) || containsLeaf(branch._second, view);
    }

    private static View leafFrameAt(Node node, int x, int y) {
        if (node instanceof LeafNode leaf) {
            return contains(leaf._frameRect, x, y) ? leaf._view : null;
        }
        var branch = (BranchNode) node;
        View first = leafFrameAt(branch._first, x, y);
        if (first != null) {
            return first;
        }
        return leafFrameAt(branch._second, x, y);
    }

    private static boolean contains(Rect rect, int x, int y) {
        return x >= rect.getPoint().getX()
                && y >= rect.getPoint().getY()
                && x < rect.getPoint().getX() + rect.getSize().getWidth()
                && y < rect.getPoint().getY() + rect.getSize().getHeight();
    }

    private static BranchNode findNearestBranch(Node node, View target, Orientation orientation) {
        if (!(node instanceof BranchNode branch) || !containsLeaf(node, target)) {
            return null;
        }
        BranchNode descendant = containsLeaf(branch._first, target)
                ? findNearestBranch(branch._first, target, orientation)
                : findNearestBranch(branch._second, target, orientation);
        if (descendant != null) {
            return descendant;
        }
        return branch._orientation == orientation ? branch : null;
    }

    private static void equalizeNode(Node node) {
        if (!(node instanceof BranchNode branch)) {
            return;
        }
        branch._ratio = 0.5;
        equalizeNode(branch._first);
        equalizeNode(branch._second);
    }

    private static void collectLeafViews(Node node, List<View> leaves) {
        if (node instanceof LeafNode leaf) {
            leaves.add(leaf._view);
            return;
        }
        var branch = (BranchNode) node;
        collectLeafViews(branch._first, leaves);
        collectLeafViews(branch._second, leaves);
    }

    private static int leafCount(Node node) {
        if (node instanceof LeafNode) {
            return 1;
        }
        var branch = (BranchNode) node;
        return leafCount(branch._first) + leafCount(branch._second);
    }

    private static View firstLeaf(Node node) {
        if (node instanceof LeafNode leaf) {
            return leaf._view;
        }
        return firstLeaf(((BranchNode) node)._first);
    }

    private static View siblingOf(Node node, View target) {
        if (!(node instanceof BranchNode branch)) {
            return null;
        }
        if (containsLeaf(branch._first, target) && !containsLeaf(branch._second, target)) {
            if (branch._first instanceof LeafNode leaf && leaf._view == target) {
                return firstLeaf(branch._second);
            }
            return siblingOf(branch._first, target);
        }
        if (containsLeaf(branch._second, target) && !containsLeaf(branch._first, target)) {
            if (branch._second instanceof LeafNode leaf && leaf._view == target) {
                return firstLeaf(branch._first);
            }
            return siblingOf(branch._second, target);
        }
        return null;
    }

    private static SessionNode snapshotNode(Node node, Function<View, String> leafIdProvider) {
        if (node instanceof LeafNode leaf) {
            return new SessionNode(null, 0.0, null, null, leafIdProvider.apply(leaf._view));
        }
        var branch = (BranchNode) node;
        return new SessionNode(
                branch._orientation.name(),
                branch._ratio,
                snapshotNode(branch._first, leafIdProvider),
                snapshotNode(branch._second, leafIdProvider),
                null);
    }
}
