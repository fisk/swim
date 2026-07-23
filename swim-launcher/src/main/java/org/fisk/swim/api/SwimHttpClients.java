package org.fisk.swim.api;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Creates HTTP clients that honor the conventional proxy environment variables. */
public final class SwimHttpClients {
    private SwimHttpClients() {}

    public static HttpClient.Builder newBuilder() {
        return configure(HttpClient.newBuilder(), System.getenv());
    }

    static HttpClient.Builder configure(HttpClient.Builder builder, Map<String, String> environment) {
        ProxySelector selector = proxySelector(environment);
        if (selector != null) builder.proxy(selector);
        return builder.connectTimeout(Duration.ofSeconds(30));
    }

    static ProxySelector proxySelector(Map<String, String> environment) {
        String raw = first(environment, "https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY");
        if (raw == null) return null;
        URI proxy = URI.create(raw.contains("://") ? raw : "http://" + raw);
        if (proxy.getHost() == null) return null;
        int port = proxy.getPort() > 0 ? proxy.getPort() : 80;
        Proxy value = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), port));
        String bypassValue = first(environment, "no_proxy", "NO_PROXY");
        List<String> bypass = List.of((bypassValue == null ? "" : bypassValue).split(","));
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                String host = uri == null ? "" : uri.getHost();
                if (host != null && bypass.stream().map(String::trim).anyMatch(rule -> matches(host, rule))) return List.of(Proxy.NO_PROXY);
                return List.of(value);
            }
            @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) {}
        };
    }

    private static boolean matches(String host, String rule) {
        if (rule.equals("*")) return true;
        String normalized = rule.startsWith(".") ? rule.substring(1) : rule;
        return !normalized.isBlank() && (host.equalsIgnoreCase(normalized) || host.toLowerCase().endsWith("." + normalized.toLowerCase()));
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) { String value = env.get(name); if (value != null && !value.isBlank()) return value.trim(); }
        return null;
    }
}
