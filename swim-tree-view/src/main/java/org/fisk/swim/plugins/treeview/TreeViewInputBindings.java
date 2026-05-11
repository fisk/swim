package org.fisk.swim.plugins.treeview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TreeViewInputBindings {
    private final Map<String, TreeViewCommand> _bindings;

    public TreeViewInputBindings(Map<String, TreeViewCommand> bindings) {
        _bindings = Map.copyOf(bindings);
    }

    public static TreeViewInputBindings defaults() {
        Map<String, TreeViewCommand> bindings = new LinkedHashMap<>();
        bindings.put("up", TreeViewCommand.MOVE_UP);
        bindings.put("k", TreeViewCommand.MOVE_UP);
        bindings.put("down", TreeViewCommand.MOVE_DOWN);
        bindings.put("j", TreeViewCommand.MOVE_DOWN);
        bindings.put("left", TreeViewCommand.COLLAPSE);
        bindings.put("h", TreeViewCommand.COLLAPSE);
        bindings.put("right", TreeViewCommand.EXPAND);
        bindings.put("l", TreeViewCommand.EXPAND);
        bindings.put("enter", TreeViewCommand.ACTIVATE);
        bindings.put("space", TreeViewCommand.ACTIVATE);
        bindings.put("r", TreeViewCommand.REFRESH);
        return new TreeViewInputBindings(bindings);
    }

    public Optional<TreeViewCommand> lookup(String input) {
        Objects.requireNonNull(input);
        return Optional.ofNullable(_bindings.get(normalize(input)));
    }

    public Map<String, TreeViewCommand> asMap() {
        return _bindings;
    }

    private static String normalize(String input) {
        return input.strip().toLowerCase();
    }
}
