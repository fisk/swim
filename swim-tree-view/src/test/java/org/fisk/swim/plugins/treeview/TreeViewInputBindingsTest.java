package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TreeViewInputBindingsTest {
    @Test
    void resolvesDefaultBindingsCaseInsensitively() {
        TreeViewInputBindings bindings = TreeViewInputBindings.defaults();

        assertEquals(TreeViewCommand.MOVE_UP, bindings.lookup("UP").orElseThrow());
        assertEquals(TreeViewCommand.MOVE_DOWN, bindings.lookup(" j ").orElseThrow());
        assertEquals(TreeViewCommand.COLLAPSE, bindings.lookup("left").orElseThrow());
        assertEquals(TreeViewCommand.EXPAND, bindings.lookup("RIGHT").orElseThrow());
        assertTrue(bindings.lookup("unknown").isEmpty());
    }

    @Test
    void exposesKeyHintsFromDefaultBindings() {
        TreeViewInputBindings bindings = TreeViewInputBindings.defaults();

        assertTrue(bindings.keyBindingHints().stream()
                .anyMatch(hint -> "<ENTER>".equals(hint.key()) && hint.summary().contains("open")));
        assertTrue(bindings.keyBindingHints().stream()
                .anyMatch(hint -> "r".equals(hint.key()) && hint.summary().contains("refresh")));
    }
}
