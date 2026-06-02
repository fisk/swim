package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrosoftOAuth2ClientTest {
    @TempDir
    Path tempDir;

    @Test
    void acquireTokenStartsDeviceCodeFlowAndPersistsPendingAuthorization() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            var client = new MicrosoftOAuth2Client(paths, new FakeTransport("""
                    {
                      "device_code": "device-123",
                      "user_code": "CODE123",
                      "verification_uri": "https://microsoft.com/devicelogin",
                      "verification_uri_complete": "https://microsoft.com/devicelogin?code=CODE123",
                      "message": "To sign in, use a web browser to open https://microsoft.com/devicelogin and enter the code CODE123.",
                      "expires_in": 900,
                      "interval": 5
                    }
                    """));

            var result = client.acquireToken(deviceCodeAccount(), "IMAP");

            assertFalse(result.hasToken());
            assertTrue(result.statusMessage().contains("CODE123"));
            assertTrue(Files.readString(paths.oauthTokensPath()).contains("device-123"));
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void acquireTokenRedeemsPendingDeviceCodeAndCachesAccessToken() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            var firstClient = new MicrosoftOAuth2Client(paths, new FakeTransport("""
                    {
                      "device_code": "device-123",
                      "user_code": "CODE123",
                      "verification_uri": "https://microsoft.com/devicelogin",
                      "verification_uri_complete": "https://microsoft.com/devicelogin?code=CODE123",
                      "message": "Sign in with code CODE123",
                      "expires_in": 900,
                      "interval": 5
                    }
                    """));
            firstClient.acquireToken(deviceCodeAccount(), "IMAP");

            var secondClient = new MicrosoftOAuth2Client(paths, new FakeTransport("""
                    {
                      "access_token": "access-token",
                      "refresh_token": "refresh-token",
                      "expires_in": 3600
                    }
                    """));

            var result = secondClient.acquireToken(deviceCodeAccount(), "IMAP");

            assertTrue(result.hasToken());
            assertEquals("access-token", result.accessToken());
            String cacheJson = Files.readString(paths.oauthTokensPath());
            assertTrue(cacheJson.contains("access-token"));
            assertFalse(cacheJson.contains("device-123"));

            var cachedClient = new MicrosoftOAuth2Client(paths, new FakeTransport());
            var cachedResult = cachedClient.acquireToken(deviceCodeAccount(), "IMAP");
            assertTrue(cachedResult.hasToken());
            assertEquals("access-token", cachedResult.accessToken());
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void acquireTokenStartsFreshBrowserFlowAfterCachedBrowserError() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            Files.createDirectories(paths.emailHome());
            Files.writeString(paths.oauthTokensPath(), """
                    {
                      "accounts": {
                        "work": {
                          "pendingBrowserAuthorization": {
                            "state": "old-state",
                            "codeVerifier": "old-verifier",
                            "authorizationUrl": "https://old.example",
                            "redirectUri": "http://localhost:1234",
                            "expiresAt": "2999-01-01T00:00:00Z",
                            "error": "invalid_state",
                            "errorDescription": "OAuth2 callback state did not match",
                            "scopes": "offline_access"
                          }
                        }
                      }
                    }
                    """);

            var launcher = new FakeBrowserLauncher();
            var client = new MicrosoftOAuth2Client(paths, new FakeTransport(), launcher);

            var result = client.acquireToken(browserOAuthAccount(), "IMAP");

            assertFalse(result.hasToken());
            assertTrue(result.statusMessage().contains("press e"));
            assertTrue(launcher.uri != null);
            String cacheJson = Files.readString(paths.oauthTokensPath());
            assertFalse(cacheJson.contains("old-state"));
            assertTrue(cacheJson.contains("pendingBrowserAuthorization"));
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void acquireTokenStartsBrowserPkceFlowAndExchangesCallbackCode() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            var launcher = new FakeBrowserLauncher();
            var firstClient = new MicrosoftOAuth2Client(paths, new FakeTransport(), launcher);

            var firstResult = firstClient.acquireToken(browserOAuthAccount(), "IMAP");

            assertFalse(firstResult.hasToken());
            assertTrue(firstResult.statusMessage().contains("press e"));
            assertTrue(launcher.uri != null);
            OAuthTokenCache cache = readCache(paths);
            PendingBrowserAuthorization pending = cache.accounts().get("work").pendingBrowserAuthorization();
            assertTrue(pending.authorizationUrl().contains("code_challenge="));

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest callbackRequest = HttpRequest.newBuilder(URI.create(
                    pending.redirectUri() + "?code=auth-code-123&state=" + java.net.URLEncoder.encode(pending.state(),
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .GET()
                    .build();
            httpClient.send(callbackRequest, HttpResponse.BodyHandlers.ofString());
            HttpRequest faviconRequest = HttpRequest.newBuilder(URI.create(
                    pending.redirectUri() + "/favicon.ico"))
                    .GET()
                    .build();
            httpClient.send(faviconRequest, HttpResponse.BodyHandlers.discarding());

            var secondClient = new MicrosoftOAuth2Client(paths, new FakeTransport("""
                    {
                      "access_token": "browser-access-token",
                      "refresh_token": "browser-refresh-token",
                      "expires_in": 3600
                    }
                    """), launcher);
            var secondResult = secondClient.acquireToken(browserOAuthAccount(), "IMAP");

            assertTrue(secondResult.hasToken());
            assertEquals("browser-access-token", secondResult.accessToken());
            String cacheJson = Files.readString(paths.oauthTokensPath());
            assertTrue(cacheJson.contains("browser-access-token"));
            assertFalse(cacheJson.contains("auth-code-123"));
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private static EmailAccountConfig deviceCodeAccount() {
        return new EmailAccountConfig(
                "work",
                "Work",
                "IMAP",
                "outlook.office365.com",
                993,
                null,
                null,
                null,
                "me@example.com",
                "IGNORED_FOR_OAUTH",
                "INBOX",
                null,
                null,
                "DEVICE_CODE",
                "organizations",
                "client-id-123",
                null);
    }

    private static EmailAccountConfig browserOAuthAccount() {
        return new EmailAccountConfig(
                "work",
                "Work",
                "IMAP",
                "outlook.office365.com",
                993,
                null,
                null,
                null,
                "me@example.com",
                "IGNORED_FOR_OAUTH",
                "INBOX",
                null,
                null,
                "OAUTH2",
                "organizations",
                "client-id-123",
                null);
    }

    private static OAuthTokenCache readCache(EmailPaths paths) throws Exception {
        try (var reader = Files.newBufferedReader(paths.oauthTokensPath())) {
            return new com.google.gson.Gson().fromJson(reader, OAuthTokenCache.class);
        }
    }

    private static final class FakeTransport implements MicrosoftOAuth2Client.OAuthHttpTransport {
        private final Queue<String> _responses = new ArrayDeque<>();

        private FakeTransport(String... responses) {
            for (String response : responses) {
                _responses.add(response);
            }
        }

        @Override
        public String postForm(String url, Map<String, String> form) {
            if (_responses.isEmpty()) {
                throw new AssertionError("Unexpected transport call to " + url);
            }
            return _responses.remove();
        }
    }

    private static final class FakeBrowserLauncher implements MicrosoftOAuth2Client.BrowserLauncher {
        private URI uri;

        @Override
        public void open(URI uri) {
            this.uri = uri;
        }
    }
}
