package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class EmbeddedOracleModuleLayerLspProviderIT {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void embeddedProviderInitializesAgainstTempProject() throws Exception {
        Path extensionPath = OracleNbcodeLspProvider.resolveOracleExtensionPath();
        var provider = new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        Assumptions.assumeTrue(provider.isAvailable(), "Oracle Java extension payload not available");
        Assumptions.assumeTrue(
                EmbeddedOracleModuleLayerLspProvider.hasRequiredJvmAccess(),
                "Embedded provider requires the NetBeans-compatible JVM launcher flags");

        Path project = tempDir.resolve("demo");
        Path workspace = JavaLSPClient.getWorkspacePath(tempDir, project);
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Files.writeString(project.resolve("src/main/java/demo/Main.java"), """
                package demo;
                class Main {}
                """);

        var session = provider.start(
                project,
                workspace,
                new SilentLanguageClient(project),
                basicCapabilities(),
                initializationOptions(),
                30);
        try {
            assertNotNull(session.server());
            assertNotNull(session.capabilities());
            assertTrue(session.description().contains("embedded"));
        } finally {
            session.closeable().close();
        }

        Path messagesLog = workspace.resolve("userdir").resolve("var").resolve("log").resolve("messages.log");
        assertTrue(Files.isRegularFile(messagesLog), "Expected NetBeans workspace log at " + messagesLog);
        String logText = Files.readString(messagesLog);
        assertTrue(!logText.contains("ClassNotFoundException: org.netbeans.api.maven.MavenActions"),
                () -> "Embedded provider left Maven project support half-initialized.\n" + logText);
        assertTrue(!logText.contains("ClassNotFoundException: maven.actions.override"),
                () -> "Embedded provider failed loading Maven layer entries.\n" + logText);
    }

    private static ClientCapabilities basicCapabilities() {
        var workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        workspace.setConfiguration(true);
        var textDocument = new TextDocumentClientCapabilities();
        return new ClientCapabilities(workspace, textDocument, null);
    }

    private static Object initializationOptions() {
        return java.util.Map.of(
                "nbcodeCapabilities", java.util.Map.of(
                        "statusBarMessageSupport", false,
                        "testResultsSupport", false,
                        "showHtmlPageSupport", false,
                        "wantsJavaSupport", true,
                        "wantsGroovySupport", false,
                        "wantsTelemetryEnabled", false,
                        "wantsNotebookSupport", false));
    }

    private static final class SilentLanguageClient implements LanguageClient {
        private final Path _project;

        private SilentLanguageClient(Path project) {
            _project = project;
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        }

        @Override
        public void showMessage(MessageParams message) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }

        @Override
        public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
            return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(
                    _project.toUri().toString(),
                    _project.getFileName().toString())));
        }

        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
            return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
