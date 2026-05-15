package org.fisk.swim.ui;

import org.fisk.swim.mail.MailNotification;
import org.fisk.swim.mail.MailStatusService;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

public final class MailNotificationView extends View {
    private static final int MIN_WIDTH = 28;
    private static final int MAX_WIDTH = 52;
    private static final int HEIGHT = 3;

    public MailNotificationView(Rect bounds) {
        super(bounds);
    }

    void syncBounds() {
        Size parentSize = getParent() == null ? getBounds().getSize() : getParent().getBounds().getSize();
        setBounds(calculateBounds(parentSize));
    }

    @Override
    public void resize(Size newParentSize) {
        setBounds(calculateBounds(newParentSize));
    }

    @Override
    public void draw(Rect rect) {
        MailNotification notification = MailStatusService.getInstance().currentStatus().notification();
        if (notification == null) {
            return;
        }
        syncBounds();
        rect = getBounds();
        var graphics = TerminalContext.getInstance().getGraphics();
        UiTheme.drawLine(graphics, rect.getPoint(), rect.getSize().getWidth(),
                AttributedString.create(" " + UiTheme.fit(notification.heading(), rect.getSize().getWidth() - 2),
                        UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GREEN),
                UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GREEN);
        UiTheme.drawLine(graphics,
                Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1),
                rect.getSize().getWidth(),
                AttributedString.create(" " + UiTheme.fit(notification.sender(), rect.getSize().getWidth() - 2),
                        UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ELEVATED),
                UiTheme.TEXT_PRIMARY,
                UiTheme.SURFACE_ELEVATED);
        UiTheme.drawLine(graphics,
                Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 2),
                rect.getSize().getWidth(),
                AttributedString.create(" " + UiTheme.fit(notification.detail(), rect.getSize().getWidth() - 2),
                        UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT),
                UiTheme.TEXT_MUTED,
                UiTheme.SURFACE_ACCENT);
    }

    private Rect calculateBounds(Size parentSize) {
        MailNotification notification = MailStatusService.getInstance().currentStatus().notification();
        if (notification == null) {
            return Rect.create(0, 0, 0, 0);
        }
        int preferredWidth = Math.max(notification.heading().length(),
                Math.max(notification.sender().length(), notification.detail().length())) + 2;
        int width = Math.min(parentSize.getWidth(), Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, preferredWidth)));
        int height = Math.min(parentSize.getHeight(), HEIGHT);
        int x = Math.max(0, parentSize.getWidth() - width);
        return Rect.create(x, 0, width, height);
    }
}
