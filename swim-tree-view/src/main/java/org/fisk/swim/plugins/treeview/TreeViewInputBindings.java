package org.fisk.swim.plugins.treeview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.fisk.swim.api.SwimKeyBindingHint;

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

    public List<SwimKeyBindingHint> keyBindingHints() {
        return _bindings.entrySet().stream()
                .map(entry -> new SwimKeyBindingHint(displayKey(entry.getKey()), group(entry.getValue()),
                        summary(entry.getValue())))
                .toList();
    }

    private static String normalize(String input) {
        return input.strip().toLowerCase();
    }

    private static String displayKey(String input) {
        return switch (normalize(input)) {
        case "up" -> "<UP>";
        case "down" -> "<DOWN>";
        case "left" -> "<LEFT>";
        case "right" -> "<RIGHT>";
        case "enter" -> "<ENTER>";
        case "space" -> "<SPACE>";
        default -> input;
        };
    }

    private static String group(TreeViewCommand command) {
        return switch (command) {
        case MOVE_UP, MOVE_DOWN, EXPAND, COLLAPSE -> "Navigation";
        case ACTIVATE -> "Files";
        case REFRESH -> "Tree";
        };
    }

    private static String summary(TreeViewCommand command) {
        return switch (command) {
        case MOVE_UP -> "move up";
        case MOVE_DOWN -> "move down";
        case EXPAND -> "expand directory";
        case COLLAPSE -> "collapse directory";
        case ACTIVATE -> "open or toggle";
        case REFRESH -> "refresh";
        };
    }
}
