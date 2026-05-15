package org.fisk.swim.plugins.email;

import java.util.List;

record EmailAccountConfig(
        String id,
        String name,
        String protocol,
        String host,
        Integer port,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String username,
        String passwordEnv,
        String folder,
        String ewsUrl,
        String domain,
        String authType,
        String tenant,
        String clientId,
        List<String> scopes) {
    String normalizedId() {
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        if (name != null && !name.isBlank()) {
            return name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        }
        if (username != null && !username.isBlank()) {
            return username.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        }
        return "account";
    }

    String displayName() {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return normalizedId();
    }

    String normalizedProtocol() {
        return protocol == null ? "IMAP" : protocol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    String normalizedAuthType() {
        return authType == null ? "PASSWORD" : authType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    String normalizedTenant() {
        return tenant == null || tenant.isBlank() ? "organizations" : tenant.trim();
    }

    boolean usesOAuth2() {
        String auth = normalizedAuthType();
        return "OAUTH2".equals(auth) || "XOAUTH2".equals(auth) || "DEVICE_CODE".equals(auth);
    }

    boolean prefersBrowserOAuth() {
        return "OAUTH2".equals(normalizedAuthType());
    }

    List<String> requestedScopes() {
        return scopes == null ? List.of() : List.copyOf(scopes);
    }

    String effectiveSmtpHost() {
        if (smtpHost != null && !smtpHost.isBlank()) {
            return smtpHost.trim();
        }
        if (host != null && host.equalsIgnoreCase("outlook.office365.com")) {
            return "smtp.office365.com";
        }
        return host;
    }

    int effectiveSmtpPort() {
        if (smtpPort != null && smtpPort > 0) {
            return smtpPort;
        }
        return 587;
    }

    String effectiveSmtpUsername() {
        if (smtpUsername != null && !smtpUsername.isBlank()) {
            return smtpUsername.trim();
        }
        return username;
    }
}
