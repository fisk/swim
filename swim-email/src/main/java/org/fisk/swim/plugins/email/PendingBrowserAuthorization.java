package org.fisk.swim.plugins.email;

record PendingBrowserAuthorization(
        String state,
        String codeVerifier,
        String authorizationUrl,
        String redirectUri,
        String expiresAt,
        String authorizationCode,
        String error,
        String errorDescription,
        String scopes) {
}
