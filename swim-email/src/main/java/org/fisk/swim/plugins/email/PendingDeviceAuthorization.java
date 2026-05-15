package org.fisk.swim.plugins.email;

record PendingDeviceAuthorization(
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        String message,
        String expiresAt,
        Integer intervalSeconds,
        String scopes) {
}
