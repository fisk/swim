package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class MicrosoftOAuth2Client {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int EXPIRY_SKEW_SECONDS = 60;
    private static final Object CACHE_LOCK = new Object();
    private static final ConcurrentHashMap<String, BrowserCallbackServer> ACTIVE_BROWSER_AUTH = new ConcurrentHashMap<>();

    private final EmailPaths _paths;
    private final OAuthHttpTransport _transport;
    private final BrowserLauncher _browserLauncher;

    MicrosoftOAuth2Client(EmailPaths paths) {
        this(paths, new HttpOAuthTransport(), new SystemBrowserLauncher());
    }

    MicrosoftOAuth2Client(EmailPaths paths, OAuthHttpTransport transport) {
        this(paths, transport, new SystemBrowserLauncher());
    }

    MicrosoftOAuth2Client(EmailPaths paths, OAuthHttpTransport transport, BrowserLauncher browserLauncher) {
        _paths = paths;
        _transport = transport;
        _browserLauncher = browserLauncher;
    }

    AcquireResult acquireToken(EmailAccountConfig account, String protocol) throws IOException {
        if (account.clientId() == null || account.clientId().isBlank()) {
            return AcquireResult.failure("OAuth2 account requires clientId");
        }

        OAuthTokenCache cache = loadCache();
        OAuthTokenRecord record = cache.accounts().get(account.normalizedId());
        if (record != null && isValid(record.accessToken(), record.expiresAt())) {
            return AcquireResult.success(record.accessToken());
        }

        if (record != null && record.refreshToken() != null && !record.refreshToken().isBlank()) {
            try {
                TokenResponse refreshed = refreshToken(account, record.refreshToken(), scopes(account, protocol));
                saveToken(cache, account.normalizedId(), refreshed, null);
                return AcquireResult.success(refreshed.access_token);
            } catch (IOException e) {
            }
        }

        if (account.prefersBrowserOAuth()
                && record != null
                && record.pendingBrowserAuthorization() != null
                && !isExpired(record.pendingBrowserAuthorization().expiresAt())) {
            PendingBrowserAuthorization pending = record.pendingBrowserAuthorization();
            if (pending.authorizationCode() != null && !pending.authorizationCode().isBlank()) {
                try {
                    TokenResponse exchanged = exchangeAuthorizationCode(account, pending, scopes(account, protocol));
                    saveToken(cache, account.normalizedId(), exchanged, null);
                    stopBrowserSession(account.normalizedId());
                    return AcquireResult.success(exchanged.access_token);
                } catch (IOException e) {
                    return AcquireResult.failure("OAuth2 token exchange failed: " + e.getMessage());
                }
            }
            if (pending.error() != null && !pending.error().isBlank()) {
                stopBrowserSession(account.normalizedId());
                clearPendingBrowserAuthorization(cache, account.normalizedId());
            } else {
                return AcquireResult.failure(browserMessage(pending));
            }
        }

        if (record != null && record.pendingDeviceAuthorization() != null
                && !isExpired(record.pendingDeviceAuthorization().expiresAt())) {
            try {
                TokenResponse pending = pollPendingToken(account, record.pendingDeviceAuthorization());
                if (pending.pendingReason != null) {
                    return AcquireResult.failure(deviceCodeMessage(record.pendingDeviceAuthorization()));
                }
                saveToken(cache, account.normalizedId(), pending, null);
                return AcquireResult.success(pending.access_token);
            } catch (IOException e) {
                return AcquireResult.failure("OAuth2 token exchange failed: " + e.getMessage());
            }
        }

        if (account.prefersBrowserOAuth()) {
            try {
                PendingBrowserAuthorization pending = requestBrowserAuthorization(account, scopes(account, protocol));
                saveBrowserPending(cache, account.normalizedId(), pending);
                return AcquireResult.failure(browserMessage(pending));
            } catch (IOException e) {
            }
        }

        PendingDeviceAuthorization pending = requestDeviceCode(account, scopes(account, protocol));
        savePending(cache, account.normalizedId(), pending);
        return AcquireResult.failure(deviceCodeMessage(pending));
    }

    private String browserMessage(PendingBrowserAuthorization pending) {
        return "Complete browser sign-in at " + pending.authorizationUrl()
                + " and wait for the callback, then press r in the mail panel.";
    }

    private String deviceCodeMessage(PendingDeviceAuthorization pending) {
        String message = pending.message();
        if (message != null && !message.isBlank()) {
            return message + " Then press r in the mail panel.";
        }
        String uri = pending.verificationUriComplete() != null && !pending.verificationUriComplete().isBlank()
                ? pending.verificationUriComplete()
                : pending.verificationUri();
        return "Authorize mail at " + uri + " with code " + pending.userCode()
                + ", then press r in the mail panel.";
    }

    private PendingBrowserAuthorization requestBrowserAuthorization(EmailAccountConfig account, String scopes) throws IOException {
        stopBrowserSession(account.normalizedId());
        BrowserCallbackServer callbackServer = BrowserCallbackServer.start(_paths, account.normalizedId());
        ACTIVE_BROWSER_AUTH.put(account.normalizedId(), callbackServer);
        String state = randomToken();
        String verifier = randomToken();
        String challenge = pkceChallenge(verifier);
        String redirectUri = callbackServer.redirectUri();
        String authorizationUrl = authorizationEndpoint(account)
                + "?client_id=" + urlEncode(account.clientId().trim())
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + urlEncode(scopes)
                + "&code_challenge=" + urlEncode(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);
        try {
            _browserLauncher.open(URI.create(authorizationUrl));
        } catch (IOException e) {
        }
        return new PendingBrowserAuthorization(
                state,
                verifier,
                authorizationUrl,
                redirectUri,
                Instant.now().plusSeconds(900).toString(),
                null,
                null,
                null,
                scopes);
    }

    private PendingDeviceAuthorization requestDeviceCode(EmailAccountConfig account, String scopes) throws IOException {
        Map<String, String> form = Map.of(
                "client_id", account.clientId().trim(),
                "scope", scopes);
        String body = _transport.postForm(deviceCodeEndpoint(account), form);
        DeviceCodeResponse response = GSON.fromJson(body, DeviceCodeResponse.class);
        if (response == null || response.device_code == null || response.device_code.isBlank()) {
            throw new IOException("Device code request did not return a device_code");
        }
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60, response.expires_in));
        return new PendingDeviceAuthorization(
                response.device_code,
                response.user_code,
                response.verification_uri,
                response.verification_uri_complete,
                response.message,
                expiresAt.toString(),
                response.interval,
                scopes);
    }

    private TokenResponse pollPendingToken(EmailAccountConfig account, PendingDeviceAuthorization pending) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        form.put("client_id", account.clientId().trim());
        form.put("device_code", pending.deviceCode());
        String body = _transport.postForm(tokenEndpoint(account), form);
        TokenResponse response = GSON.fromJson(body, TokenResponse.class);
        if (response == null) {
            throw new IOException("Token endpoint returned no body");
        }
        if (response.error != null && !response.error.isBlank()) {
            if ("authorization_pending".equals(response.error) || "slow_down".equals(response.error)) {
                response.pendingReason = response.error;
                return response;
            }
            throw new IOException(response.error_description == null ? response.error : response.error_description);
        }
        return response;
    }

    private TokenResponse exchangeAuthorizationCode(
            EmailAccountConfig account,
            PendingBrowserAuthorization pending,
            String scopes) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", account.clientId().trim());
        form.put("code", pending.authorizationCode());
        form.put("redirect_uri", pending.redirectUri());
        form.put("code_verifier", pending.codeVerifier());
        form.put("scope", scopes);
        String body = _transport.postForm(tokenEndpoint(account), form);
        TokenResponse response = GSON.fromJson(body, TokenResponse.class);
        if (response == null) {
            throw new IOException("Authorization code exchange returned no body");
        }
        if (response.error != null && !response.error.isBlank()) {
            throw new IOException(response.error_description == null ? response.error : response.error_description);
        }
        return response;
    }

    private TokenResponse refreshToken(EmailAccountConfig account, String refreshToken, String scopes) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", account.clientId().trim());
        form.put("refresh_token", refreshToken);
        form.put("scope", scopes);
        String body = _transport.postForm(tokenEndpoint(account), form);
        TokenResponse response = GSON.fromJson(body, TokenResponse.class);
        if (response == null) {
            throw new IOException("Refresh token endpoint returned no body");
        }
        if (response.error != null && !response.error.isBlank()) {
            throw new IOException(response.error_description == null ? response.error : response.error_description);
        }
        return response;
    }

    private OAuthTokenCache loadCache() throws IOException {
        EmailConfigStore.ensureDefaultFiles(_paths);
        if (!Files.isRegularFile(_paths.oauthTokensPath()) || Files.readString(_paths.oauthTokensPath()).trim().isEmpty()) {
            return OAuthTokenCache.empty();
        }
        try (Reader reader = Files.newBufferedReader(_paths.oauthTokensPath())) {
            OAuthTokenCache cache = GSON.fromJson(reader, OAuthTokenCache.class);
            return cache == null ? OAuthTokenCache.empty() : cache;
        }
    }

    private void savePending(OAuthTokenCache cache, String accountId, PendingDeviceAuthorization pending) throws IOException {
        var accounts = new LinkedHashMap<>(cache.accounts());
        OAuthTokenRecord existing = accounts.get(accountId);
        accounts.put(accountId, new OAuthTokenRecord(
                existing == null ? null : existing.accessToken(),
                existing == null ? null : existing.refreshToken(),
                existing == null ? null : existing.expiresAt(),
                pending,
                existing == null ? null : existing.pendingBrowserAuthorization()));
        writeCache(new OAuthTokenCache(accounts));
    }

    private void saveBrowserPending(OAuthTokenCache cache, String accountId, PendingBrowserAuthorization pending) throws IOException {
        var accounts = new LinkedHashMap<>(cache.accounts());
        OAuthTokenRecord existing = accounts.get(accountId);
        accounts.put(accountId, new OAuthTokenRecord(
                existing == null ? null : existing.accessToken(),
                existing == null ? null : existing.refreshToken(),
                existing == null ? null : existing.expiresAt(),
                null,
                pending));
        writeCache(new OAuthTokenCache(accounts));
    }

    private void clearPendingBrowserAuthorization(OAuthTokenCache cache, String accountId) throws IOException {
        var accounts = new LinkedHashMap<>(cache.accounts());
        OAuthTokenRecord existing = accounts.get(accountId);
        if (existing == null) {
            return;
        }
        accounts.put(accountId, new OAuthTokenRecord(
                existing.accessToken(),
                existing.refreshToken(),
                existing.expiresAt(),
                existing.pendingDeviceAuthorization(),
                null));
        writeCache(new OAuthTokenCache(accounts));
    }

    private void saveToken(
            OAuthTokenCache cache,
            String accountId,
            TokenResponse token,
            PendingDeviceAuthorization fallbackPending) throws IOException {
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60, token.expires_in));
        var accounts = new LinkedHashMap<>(cache.accounts());
        accounts.put(accountId, new OAuthTokenRecord(
                token.access_token,
                token.refresh_token,
                expiresAt.toString(),
                fallbackPending,
                null));
        writeCache(new OAuthTokenCache(accounts));
    }

    private void writeCache(OAuthTokenCache cache) throws IOException {
        synchronized (CACHE_LOCK) {
            try (Writer writer = Files.newBufferedWriter(_paths.oauthTokensPath())) {
                GSON.toJson(cache, writer);
            }
        }
    }

    private static boolean isValid(String accessToken, String expiresAt) {
        return accessToken != null && !accessToken.isBlank() && !isExpired(expiresAt);
    }

    private static boolean isExpired(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return true;
        }
        return Instant.parse(expiresAt).minusSeconds(EXPIRY_SKEW_SECONDS).isBefore(Instant.now());
    }

    private static String scopes(EmailAccountConfig account, String protocol) {
        List<String> configured = account.requestedScopes();
        if (!configured.isEmpty()) {
            return configured.stream().filter(scope -> scope != null && !scope.isBlank())
                    .collect(Collectors.joining(" "));
        }
        List<String> scopes = new java.util.ArrayList<>();
        scopes.add("offline_access");
        scopes.add(switch (protocol.toUpperCase(Locale.ROOT)) {
        case "POP3", "POP", "POP3S" -> "https://outlook.office.com/POP.AccessAsUser.All";
        default -> "https://outlook.office.com/IMAP.AccessAsUser.All";
        });
        scopes.add("https://outlook.office.com/SMTP.Send");
        return String.join(" ", scopes);
    }

    private static String deviceCodeEndpoint(EmailAccountConfig account) {
        return "https://login.microsoftonline.com/" + account.normalizedTenant() + "/oauth2/v2.0/devicecode";
    }

    private static String authorizationEndpoint(EmailAccountConfig account) {
        return "https://login.microsoftonline.com/" + account.normalizedTenant() + "/oauth2/v2.0/authorize";
    }

    private static String tokenEndpoint(EmailAccountConfig account) {
        return "https://login.microsoftonline.com/" + account.normalizedTenant() + "/oauth2/v2.0/token";
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String pkceChallenge(String verifier) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unable to create PKCE challenge", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void stopBrowserSession(String accountId) {
        BrowserCallbackServer server = ACTIVE_BROWSER_AUTH.remove(accountId);
        if (server != null) {
            server.close();
        }
    }

    record AcquireResult(String accessToken, String statusMessage) {
        static AcquireResult success(String accessToken) {
            return new AcquireResult(accessToken, "");
        }

        static AcquireResult failure(String statusMessage) {
            return new AcquireResult(null, statusMessage);
        }

        boolean hasToken() {
            return accessToken != null && !accessToken.isBlank();
        }
    }

    interface OAuthHttpTransport {
        String postForm(String url, Map<String, String> form) throws IOException;
    }

    interface BrowserLauncher {
        void open(URI uri) throws IOException;
    }

    private static final class HttpOAuthTransport implements OAuthHttpTransport {
        private final HttpClient _client = HttpClient.newBuilder().build();

        @Override
        public String postForm(String url, Map<String, String> form) throws IOException {
            String body = form.entrySet().stream()
                    .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = _client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling OAuth endpoint", e);
            }
        }

        private static String urlEncode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static final class SystemBrowserLauncher implements BrowserLauncher {
        @Override
        public void open(URI uri) throws IOException {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder builder;
            if (osName.contains("mac")) {
                builder = new ProcessBuilder("open", uri.toString());
            } else if (osName.contains("win")) {
                builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString());
            } else {
                builder = new ProcessBuilder("xdg-open", uri.toString());
            }
            builder.start();
        }
    }

    private static final class BrowserCallbackServer implements AutoCloseable {
        private final HttpServer _server;

        private BrowserCallbackServer(HttpServer server) {
            _server = server;
        }

        static BrowserCallbackServer start(EmailPaths paths, String accountId) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> handleCallback(paths, accountId, exchange));
            server.start();
            return new BrowserCallbackServer(server);
        }

        String redirectUri() {
            return "http://localhost:" + _server.getAddress().getPort();
        }

        @Override
        public void close() {
            _server.stop(0);
        }

        private static void handleCallback(EmailPaths paths, String accountId, HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            if (!looksLikeOAuthCallback(params)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            updatePendingBrowserAuthorization(paths, accountId, params);
            byte[] response = """
                    <html><body><h2>SWIM mail sign-in complete</h2><p>You can return to SWIM and press r in the mail panel.</p></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            var params = new LinkedHashMap<String, String>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return params;
            }
            for (String part : rawQuery.split("&")) {
                int index = part.indexOf('=');
                String key = index >= 0 ? part.substring(0, index) : part;
                String value = index >= 0 ? part.substring(index + 1) : "";
                params.put(
                        java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
            return params;
        }

        private static boolean looksLikeOAuthCallback(Map<String, String> params) {
            return params.containsKey("code") || params.containsKey("error") || params.containsKey("state");
        }
    }

    private static void updatePendingBrowserAuthorization(
            EmailPaths paths,
            String accountId,
            Map<String, String> params) throws IOException {
        synchronized (CACHE_LOCK) {
            EmailConfigStore.ensureDefaultFiles(paths);
            OAuthTokenCache cache;
            if (!Files.isRegularFile(paths.oauthTokensPath()) || Files.readString(paths.oauthTokensPath()).trim().isEmpty()) {
                cache = OAuthTokenCache.empty();
            } else {
                try (Reader reader = Files.newBufferedReader(paths.oauthTokensPath())) {
                    cache = GSON.fromJson(reader, OAuthTokenCache.class);
                }
            }
            if (cache == null) {
                cache = OAuthTokenCache.empty();
            }
            OAuthTokenRecord existing = cache.accounts().get(accountId);
            if (existing == null || existing.pendingBrowserAuthorization() == null) {
                return;
            }
            PendingBrowserAuthorization pending = existing.pendingBrowserAuthorization();
            if (!Objects.equals(pending.state(), params.get("state"))) {
                pending = new PendingBrowserAuthorization(
                        pending.state(),
                        pending.codeVerifier(),
                        pending.authorizationUrl(),
                        pending.redirectUri(),
                        pending.expiresAt(),
                        null,
                        "invalid_state",
                        "OAuth2 callback state did not match",
                        pending.scopes());
            } else {
                pending = new PendingBrowserAuthorization(
                        pending.state(),
                        pending.codeVerifier(),
                        pending.authorizationUrl(),
                        pending.redirectUri(),
                        pending.expiresAt(),
                        params.get("code"),
                        params.get("error"),
                        params.get("error_description"),
                        pending.scopes());
            }
            var accounts = new LinkedHashMap<>(cache.accounts());
            accounts.put(accountId, new OAuthTokenRecord(
                    existing.accessToken(),
                    existing.refreshToken(),
                    existing.expiresAt(),
                    existing.pendingDeviceAuthorization(),
                    pending));
            try (Writer writer = Files.newBufferedWriter(paths.oauthTokensPath())) {
                GSON.toJson(new OAuthTokenCache(accounts), writer);
            }
        }
    }

    private static final class DeviceCodeResponse {
        String device_code;
        String user_code;
        String verification_uri;
        String verification_uri_complete;
        String message;
        int expires_in;
        Integer interval;
    }

    private static final class TokenResponse {
        String access_token;
        String refresh_token;
        int expires_in;
        String error;
        String error_description;
        String pendingReason;
    }
}
