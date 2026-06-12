package org.fisk.swim.lsp.java;

import java.nio.file.Path;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

interface JavaLspProvider {
    record Session(LanguageServer server, ServerCapabilities capabilities, AutoCloseable closeable, String description) {
    }

    boolean isAvailable();

    Session start(
            Path projectPath,
            Path workspacePath,
            LanguageClient client,
            ClientCapabilities clientCapabilities,
            Object initializationOptions,
            long timeoutSeconds) throws Exception;
}
