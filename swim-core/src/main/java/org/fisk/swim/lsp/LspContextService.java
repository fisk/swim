package org.fisk.swim.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.text.BufferContext;

public final class LspContextService {
    private static final LspContextService INSTANCE = new LspContextService();

    private final Object _lock = new Object();
    private final Map<String, DocumentContext> _contextsByKey = new HashMap<>();

    public record Scope(String label, int startLine, int startCharacter, int endLine, int endCharacter) {
        public Scope {
            label = label == null ? "" : label.trim();
            startLine = Math.max(0, startLine);
            startCharacter = Math.max(0, startCharacter);
            endLine = Math.max(startLine, endLine);
            endCharacter = Math.max(0, endCharacter);
        }

        private boolean contains(int line, int character) {
            return !label.isBlank()
                    && compare(line, character, startLine, startCharacter) >= 0
                    && compare(line, character, endLine, endCharacter) <= 0;
        }

        private long span() {
            return (long) (endLine - startLine) * 1_000_000L + Math.max(0, endCharacter - startCharacter);
        }
    }

    public record DocumentContext(
            String providerId,
            String uri,
            Path path,
            int version,
            List<Scope> scopes) {
        public DocumentContext {
            providerId = providerId == null || providerId.isBlank() ? "lsp" : providerId;
            path = normalize(path);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    private LspContextService() {
    }

    public static LspContextService getInstance() {
        return INSTANCE;
    }

    public void publish(String providerId, String uri, Path path, int version, List<Scope> scopes) {
        if ((uri == null || uri.isBlank()) && path == null) {
            return;
        }
        providerId = providerId == null || providerId.isBlank() ? "lsp" : providerId;
        path = normalize(path == null ? pathForUri(uri) : path);
        String key = key(providerId, uri, path);
        synchronized (_lock) {
            if (scopes == null || scopes.isEmpty()) {
                _contextsByKey.remove(key);
            } else {
                _contextsByKey.put(key, new DocumentContext(providerId, uri, path, version, scopes));
            }
        }
    }

    public void clear(String providerId, String uri) {
        Path path = pathForUri(uri);
        providerId = providerId == null || providerId.isBlank() ? "lsp" : providerId;
        String normalizedProviderId = providerId;
        synchronized (_lock) {
            _contextsByKey.remove(key(providerId, uri, path));
            if (path != null) {
                _contextsByKey.entrySet().removeIf(entry -> normalizedProviderId.equals(entry.getValue().providerId())
                        && path.equals(entry.getValue().path()));
            }
        }
    }

    public void clearProvider(String providerId) {
        providerId = providerId == null || providerId.isBlank() ? "lsp" : providerId;
        String normalizedProviderId = providerId;
        synchronized (_lock) {
            _contextsByKey.entrySet().removeIf(entry -> normalizedProviderId.equals(entry.getValue().providerId()));
        }
    }

    public String contextFor(BufferContext context) {
        if (context == null || context.getBuffer() == null) {
            return "";
        }
        String uri = context.getBuffer().getURI().toString();
        Path path = normalize(context.getBuffer().getPath());
        int cursor = context.getBuffer().getCursor().getPosition();
        var line = context.getTextLayout().getPhysicalLineAt(cursor);
        int lineIndex = line.getY();
        int character = Math.max(0, cursor - line.getStartPosition());
        List<DocumentContext> contexts = matchingContexts(uri, path);
        Scope best = null;
        for (var documentContext : contexts) {
            for (var scope : documentContext.scopes()) {
                if (!scope.contains(lineIndex, character)) {
                    continue;
                }
                if (best == null
                        || scope.span() < best.span()
                        || scope.span() == best.span() && scope.label().length() > best.label().length()) {
                    best = scope;
                }
            }
        }
        return best == null ? "" : best.label();
    }

    private List<DocumentContext> matchingContexts(String uri, Path path) {
        var result = new ArrayList<DocumentContext>();
        synchronized (_lock) {
            for (var context : _contextsByKey.values()) {
                if (matches(context, uri, path)) {
                    result.add(context);
                }
            }
        }
        return result;
    }

    private static boolean matches(DocumentContext context, String uri, Path path) {
        return uri != null && uri.equals(context.uri())
                || path != null && path.equals(context.path());
    }

    private static String key(String providerId, String uri, Path path) {
        String documentKey = uri == null || uri.isBlank()
                ? path == null ? "" : path.toString()
                : uri;
        return providerId + "\u0000" + documentKey;
    }

    private static Path pathForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            return normalize(Paths.get(parsed));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static int compare(int leftLine, int leftCharacter, int rightLine, int rightCharacter) {
        int line = Integer.compare(leftLine, rightLine);
        return line != 0 ? line : Integer.compare(leftCharacter, rightCharacter);
    }
}
