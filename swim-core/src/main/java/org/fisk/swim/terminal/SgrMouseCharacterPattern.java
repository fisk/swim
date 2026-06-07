package org.fisk.swim.terminal;

import java.util.List;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

final class SgrMouseCharacterPattern implements CharacterPattern {
    private static final char[] PREFIX = { KeyDecodingProfile.ESC_CODE, '[', '<' };
    private static final int MOTION_MASK = 32;
    private static final int WHEEL_MASK = 64;

    @Override
    public Matching match(List<Character> seq) {
        if (seq.size() > 40) {
            return null;
        }
        for (int i = 0; i < PREFIX.length; i++) {
            if (i >= seq.size()) {
                return Matching.NOT_YET;
            }
            if (seq.get(i) != PREFIX[i]) {
                return null;
            }
        }
        return parse(seq);
    }

    private Matching parse(List<Character> seq) {
        int[] values = new int[3];
        int field = 0;
        boolean hasDigit = false;

        for (int i = PREFIX.length; i < seq.size(); i++) {
            char ch = seq.get(i);
            if (Character.isDigit(ch)) {
                hasDigit = true;
                values[field] = values[field] * 10 + (ch - '0');
                continue;
            }
            if (ch == ';') {
                if (!hasDigit || field == 2) {
                    return null;
                }
                field++;
                hasDigit = false;
                continue;
            }
            if (ch == 'M' || ch == 'm') {
                if (!hasDigit || field != 2 || values[1] <= 0 || values[2] <= 0) {
                    return null;
                }
                MouseAction action = toMouseAction(values[0], values[1], values[2], ch);
                return action == null ? null : new Matching(action);
            }
            return null;
        }

        return Matching.NOT_YET;
    }

    private MouseAction toMouseAction(int buttonCode, int column, int row, char terminator) {
        boolean release = terminator == 'm';
        boolean wheel = (buttonCode & WHEEL_MASK) != 0;
        boolean motion = (buttonCode & MOTION_MASK) != 0;
        int buttonPart = buttonCode & 0x03;

        MouseActionType actionType;
        int button;
        if (release) {
            actionType = MouseActionType.CLICK_RELEASE;
            button = 0;
        } else if (wheel) {
            if (buttonPart == 0) {
                actionType = MouseActionType.SCROLL_UP;
                button = 4;
            } else if (buttonPart == 1) {
                actionType = MouseActionType.SCROLL_DOWN;
                button = 5;
            } else {
                return null;
            }
        } else {
            button = buttonPart == 3 ? 0 : buttonPart + 1;
            if (motion) {
                actionType = button == 0 ? MouseActionType.MOVE : MouseActionType.DRAG;
            } else {
                actionType = button == 0 ? MouseActionType.CLICK_RELEASE : MouseActionType.CLICK_DOWN;
            }
        }

        return new MouseAction(actionType, button, new TerminalPosition(column - 1, row - 1));
    }
}
