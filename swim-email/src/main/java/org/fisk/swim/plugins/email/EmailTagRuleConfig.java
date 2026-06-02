package org.fisk.swim.plugins.email;

record EmailTagRuleConfig(String tag, String field, String contains, String match) {
    String normalizedField() {
        return field == null ? "sender" : field.trim().toLowerCase(java.util.Locale.ROOT);
    }

    String normalizedMatch() {
        return match == null || match.isBlank() ? "contains" : match.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
