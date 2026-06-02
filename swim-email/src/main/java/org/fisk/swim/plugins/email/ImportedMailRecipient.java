package org.fisk.swim.plugins.email;

record ImportedMailRecipient(
        String type,
        String name,
        String email) {
    String normalizedType() {
        return type == null || type.isBlank() ? "TO" : type.trim().toUpperCase(java.util.Locale.ROOT);
    }

    String normalizedEmail() {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
