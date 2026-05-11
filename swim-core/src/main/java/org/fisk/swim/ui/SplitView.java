package org.fisk.swim.ui;

public class SplitView extends View {
    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    private final Orientation _orientation;
    private final double _ratio;
    private View _firstView;
    private View _secondView;

    public SplitView(Rect bounds, Orientation orientation, View firstView, View secondView) {
        this(bounds, orientation, firstView, secondView, 0.5);
    }

    public SplitView(Rect bounds, Orientation orientation, View firstView, View secondView, double ratio) {
        super(bounds);
        _orientation = orientation;
        _ratio = Math.max(0.0, Math.min(1.0, ratio));
        _firstView = firstView;
        _secondView = secondView;
        addSubview(firstView);
        addSubview(secondView);
        layoutSubviews(bounds);
    }

    public Orientation getOrientation() {
        return _orientation;
    }

    public View getFirstView() {
        return _firstView;
    }

    public View getSecondView() {
        return _secondView;
    }

    public View getSibling(View view) {
        if (view == _firstView) {
            return _secondView;
        }
        if (view == _secondView) {
            return _firstView;
        }
        return null;
    }

    public void replaceChild(View currentView, View replacementView) {
        if (currentView != _firstView && currentView != _secondView) {
            throw new IllegalArgumentException("View is not a child of this split");
        }
        if (currentView.getParent() == this) {
            currentView.removeFromParent();
        }
        if (currentView == _firstView) {
            _firstView = replacementView;
        } else {
            _secondView = replacementView;
        }
        addSubview(replacementView);
        layoutSubviews(getBounds());
    }

    @Override
    public void setBounds(Rect rect) {
        super.setBounds(rect);
        layoutSubviews(rect);
    }

    @Override
    public void resize(Size newParentSize) {
        var bounds = getBounds();
        setBounds(Rect.create(bounds.getPoint().getX(), bounds.getPoint().getY(),
                newParentSize.getWidth(), newParentSize.getHeight()));
    }

    private void layoutSubviews(Rect bounds) {
        int width = bounds.getSize().getWidth();
        int height = bounds.getSize().getHeight();
        if (_orientation == Orientation.HORIZONTAL) {
            int firstWidth = partition(width);
            _firstView.setBounds(Rect.create(0, 0, firstWidth, height));
            _secondView.setBounds(Rect.create(firstWidth, 0, width - firstWidth, height));
            return;
        }
        int firstHeight = partition(height);
        _firstView.setBounds(Rect.create(0, 0, width, firstHeight));
        _secondView.setBounds(Rect.create(0, firstHeight, width, height - firstHeight));
    }

    private int partition(int size) {
        if (size <= 1) {
            return size;
        }
        int partition = (int) Math.floor(size * _ratio);
        if (partition <= 0) {
            return 1;
        }
        if (partition >= size) {
            return size - 1;
        }
        return partition;
    }
}
