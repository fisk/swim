package org.fisk.swim.api;

public record SwimKeyBindingHint(String key, String group, String summary) {
    public SwimKeyBindingHint {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank for " + key);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank for " + key);
        }
    }
}
