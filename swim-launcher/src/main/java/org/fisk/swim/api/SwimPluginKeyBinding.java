package org.fisk.swim.api;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record SwimPluginKeyBinding(
        String key,
        String group,
        String summary,
        String commandName,
        String command,
        BooleanSupplier available,
        Runnable action) {
    public SwimPluginKeyBinding {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank for " + key);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank for " + key);
        }
        commandName = commandName == null ? "" : commandName.strip();
        command = command == null ? "" : command.strip();
        available = available == null ? () -> true : available;
    }

    public SwimPluginKeyBinding(String key, String group, String summary, String command) {
        this(key, group, summary, command, command);
    }

    public SwimPluginKeyBinding(String key, String group, String summary, String commandName, String command) {
        this(key, group, summary, commandName, command, () -> true, null);
    }

    public SwimPluginKeyBinding(String key, String group, String summary, String commandName, Runnable action) {
        this(key, group, summary, commandName, "", () -> true, Objects.requireNonNull(action, "action"));
    }

    public SwimPluginKeyBinding(String key, String group, String summary, String commandName,
            BooleanSupplier available, Runnable action) {
        this(key, group, summary, commandName, "", available, Objects.requireNonNull(action, "action"));
    }

    public boolean isAvailable() {
        return available.getAsBoolean();
    }

    public boolean hasAction() {
        return action != null;
    }
}
