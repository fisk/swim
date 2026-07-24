package org.fisk.swim.nemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.fisk.swim.lsp.NemoLspBackend;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;

/** Owns hidden LSP documents opened on behalf of one Nemo request. */
final class NemoLspAnalysisLeaseManager {
    private record Lease(String owner, Path workspaceRoot, BufferContext context) {
    }

    private final Map<String, Lease> _leases = new ConcurrentHashMap<>();

    String open(String owner, Path workspaceRoot, Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a file: " + path);
        }
        Path realWorkspaceRoot = workspaceRoot.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realWorkspaceRoot)) {
            throw new IOException("Analysis file escapes workspace root: " + path);
        }
        var context = new BufferContext(Rect.create(0, 0, 1, 1), realPath);
        String handle = UUID.randomUUID().toString();
        _leases.put(handle, new Lease(owner, realWorkspaceRoot, context));
        return handle;
    }

    String analyze(String owner, String handle, String command, int line, int column, String query) {
        Lease lease = _leases.get(handle);
        if (lease == null || !lease.owner().equals(owner)) {
            return "Unknown or closed analysis handle.";
        }
        if (!(lease.context().getBuffer().getLanguageMode() instanceof NemoLspBackend backend)) {
            return "No Nemo LSP backend is available for " + lease.context().getBuffer().getPath() + ".";
        }
        return backend.analyze(lease.workspaceRoot(), lease.context(), command, Math.max(1, line), Math.max(1, column),
                query == null ? "" : query);
    }

    boolean close(String owner, String handle) {
        Lease lease = _leases.get(handle);
        if (lease == null || !lease.owner().equals(owner)) {
            return false;
        }
        return close(handle, lease);
    }

    void closeAll(String owner) {
        for (var entry : _leases.entrySet()) {
            if (entry.getValue().owner().equals(owner)) {
                close(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean close(String handle, Lease expectedLease) {
        if (!_leases.remove(handle, expectedLease)) {
            return false;
        }
        expectedLease.context().getBuffer().close();
        return true;
    }

}
