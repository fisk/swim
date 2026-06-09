package org.fisk.swim.event;

public record KeyBindingHint(String key, String group, String summary, String commandName) {
    public KeyBindingHint {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank for " + key);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank for " + key);
        }
        commandName = commandName == null ? "" : commandName;
    }

    public KeyBindingHint(String key, String group, String summary) {
        this(key, group, summary, "");
    }

    public static KeyBindingHint of(String key, String group, String summary) {
        return new KeyBindingHint(key, group, summary, "");
    }

    public static KeyBindingHint of(String key, String group, String summary, String commandName) {
        return new KeyBindingHint(key, group, summary, commandName);
    }

    public KeyBindingHint withKey(String key) {
        return new KeyBindingHint(key, group, summary, commandName);
    }
}
