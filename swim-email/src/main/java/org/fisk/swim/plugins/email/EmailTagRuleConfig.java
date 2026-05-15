package org.fisk.swim.plugins.email;

record EmailTagRuleConfig(String tag, String field, String contains) {
    String normalizedField() {
        return field == null ? "sender" : field.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
