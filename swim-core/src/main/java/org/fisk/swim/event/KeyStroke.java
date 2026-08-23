package org.fisk.swim.event;

import java.util.Objects;

/** A terminal-independent key stroke. */
public class KeyStroke {
    private final KeyType keyType;
    private final Character character;
    private final boolean ctrlDown;
    private final boolean altDown;
    private final boolean shiftDown;

    public KeyStroke(KeyType keyType) { this(keyType, false, false, false); }
    public KeyStroke(KeyType keyType, boolean ctrlDown, boolean altDown) { this(keyType, ctrlDown, altDown, false); }
    public KeyStroke(KeyType keyType, boolean ctrlDown, boolean altDown, boolean shiftDown) {
        this.keyType = Objects.requireNonNull(keyType, "keyType");
        this.character = null;
        this.ctrlDown = ctrlDown;
        this.altDown = altDown;
        this.shiftDown = shiftDown;
    }
    public KeyStroke(Character character, boolean ctrlDown, boolean altDown) { this(character, ctrlDown, altDown, false); }
    public KeyStroke(Character character, boolean ctrlDown, boolean altDown, boolean shiftDown) {
        this.keyType = KeyType.Character;
        this.character = Objects.requireNonNull(character, "character");
        this.ctrlDown = ctrlDown;
        this.altDown = altDown;
        this.shiftDown = shiftDown;
    }
    public KeyType getKeyType() { return keyType; }
    public Character getCharacter() { return character; }
    public boolean isCtrlDown() { return ctrlDown; }
    public boolean isAltDown() { return altDown; }
    public boolean isShiftDown() { return shiftDown; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof KeyStroke stroke)) return false;
        return keyType == stroke.keyType && Objects.equals(character, stroke.character) && ctrlDown == stroke.ctrlDown
                && altDown == stroke.altDown && shiftDown == stroke.shiftDown;
    }
    @Override public int hashCode() { return Objects.hash(keyType, character, ctrlDown, altDown, shiftDown); }
}
