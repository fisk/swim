package org.fisk.swim.ui;

public final class WindowLayoutTestSupport {
    private static final int DEFAULT_TOP_MENU_HEIGHT = 2;

    private WindowLayoutTestSupport() {
    }

    public static WindowChromeLayout chromeLayout(int width, int height) {
        return WindowChromeLayout.compute(Size.create(width, height), DEFAULT_TOP_MENU_HEIGHT,
                WindowChromeLayout.standardFooterBars(true));
    }

    public static Rect workspace(int width, int height) {
        return chromeLayout(width, height).workspace();
    }

    public static Rect rightSplitLeaf(int width, int height) {
        Rect workspace = workspace(width, height);
        return rightSplitLeaf(Rect.create(0, 0, workspace.getSize().getWidth(), workspace.getSize().getHeight()));
    }

    public static Rect absoluteRightSplitLeaf(int width, int height) {
        return translate(rightSplitLeaf(width, height), workspace(width, height).getPoint());
    }

    public static Rect rightSplitLeaf(Rect bounds) {
        int divider = dividerThickness(bounds.getSize().getWidth());
        int frameWidth = Math.max(0, bounds.getSize().getWidth() - divider);
        int firstWidth = partition(frameWidth, 0.5);
        return contentRect(Rect.create(bounds.getPoint().getX() + firstWidth + divider,
                bounds.getPoint().getY(),
                frameWidth - firstWidth,
                bounds.getSize().getHeight()));
    }

    public static Rect bottomSplitLeaf(int width, int height) {
        Rect workspace = workspace(width, height);
        return bottomSplitLeaf(Rect.create(0, 0, workspace.getSize().getWidth(), workspace.getSize().getHeight()));
    }

    public static Rect absoluteBottomSplitLeaf(int width, int height) {
        return translate(bottomSplitLeaf(width, height), workspace(width, height).getPoint());
    }

    public static Rect bottomSplitLeaf(Rect bounds) {
        return contentRect(bottomSplitFrame(bounds));
    }

    public static Rect rightLeafInsideBottomSplit(int width, int height) {
        Rect workspace = workspace(width, height);
        return rightSplitLeaf(bottomSplitFrame(Rect.create(0, 0,
                workspace.getSize().getWidth(),
                workspace.getSize().getHeight())));
    }

    private static Rect bottomSplitFrame(Rect bounds) {
        int divider = dividerThickness(bounds.getSize().getHeight());
        int frameHeight = Math.max(0, bounds.getSize().getHeight() - divider);
        int firstHeight = partition(frameHeight, 0.5);
        return Rect.create(bounds.getPoint().getX(),
                bounds.getPoint().getY() + firstHeight + divider,
                bounds.getSize().getWidth(),
                frameHeight - firstHeight);
    }

    public static Rect overlayPanel(int width, int height, double ratio) {
        Rect workspace = workspace(width, height);
        int overlayHeight = Math.max(1, (int) Math.ceil(workspace.getSize().getHeight() * ratio));
        return Rect.create(0,
                workspace.getPoint().getY() + workspace.getSize().getHeight() - overlayHeight,
                workspace.getSize().getWidth(),
                overlayHeight);
    }

    private static int dividerThickness(int size) {
        return size >= 3 ? 1 : 0;
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

    private static Rect contentRect(Rect frame) {
        if (frame.getSize().getHeight() < 2) {
            return frame;
        }
        return Rect.create(frame.getPoint().getX(), frame.getPoint().getY(), frame.getSize().getWidth(),
                Math.max(0, frame.getSize().getHeight() - 1));
    }

    private static Rect translate(Rect rect, Point offset) {
        return Rect.create(rect.getPoint().getX() + offset.getX(),
                rect.getPoint().getY() + offset.getY(),
                rect.getSize().getWidth(),
                rect.getSize().getHeight());
    }
}
