package org.fisk.swim.plugins.email;

record OAuthTokenRecord(
        String accessToken,
        String refreshToken,
        String expiresAt,
        PendingDeviceAuthorization pendingDeviceAuthorization,
        PendingBrowserAuthorization pendingBrowserAuthorization) {
}
