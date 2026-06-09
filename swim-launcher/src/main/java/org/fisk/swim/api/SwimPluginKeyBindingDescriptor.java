package org.fisk.swim.api;

import java.util.function.BooleanSupplier;

public record SwimPluginKeyBindingDescriptor(
        String pluginId,
        String key,
        String group,
        String summary,
        String commandName,
        String command,
        BooleanSupplier available,
        Runnable action) {
    public boolean isAvailable() {
        return available == null || available.getAsBoolean();
    }

    public boolean hasAction() {
        return action != null;
    }
}
