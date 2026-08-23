package org.fisk.swim.event;

/** A terminal-independent mouse action using zero-based cell coordinates. */
public final class MouseAction extends KeyStroke {
    public record Position(int column, int row) { public int getColumn() { return column; } public int getRow() { return row; } }
    private final MouseActionType actionType;
    private final int button;
    private final Position position;
    public MouseAction(MouseActionType actionType, int button, Position position) {
        super(KeyType.MouseEvent);
        this.actionType = actionType;
        this.button = button;
        this.position = position;
    }
    public MouseActionType getActionType() { return actionType; }
    public int getButton() { return button; }
    public Position getPosition() { return position; }
}
