package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TokenFormat;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
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
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        var provider = new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        Assumptions.assumeTrue(provider.isAvailable(), "Oracle Java extension payload not available");
        Assumptions.assumeTrue(
                EmbeddedOracleModuleLayerLspProvider.hasRequiredJvmAccess(),
                "Embedded provider requires the NetBeans-compatible JVM launcher flags");

        Path project = tempDir.resolve("demo");
        Path workspace = workspace("temp-project");
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
            assertNotNull(session.capabilities().getSemanticTokensProvider(),
                    "Expected embedded provider to advertise semantic tokens");
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
        assertTrue(!logText.contains("ClassNotFoundException: org.netbeans.NbExit"),
                () -> "Embedded provider closed the NetBeans classloader during shutdown.\n" + logText);
    }

    @Test
    @Timeout(60)
    void embeddedProviderInitializesAgainstRealSwimCoreModule() throws Exception {
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        var provider = new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        Assumptions.assumeTrue(provider.isAvailable(), "Oracle Java extension payload not available");
        Assumptions.assumeTrue(
                EmbeddedOracleModuleLayerLspProvider.hasRequiredJvmAccess(),
                "Embedded provider requires the NetBeans-compatible JVM launcher flags");

        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(project.resolve("pom.xml")),
                "Expected to run from the swim-core module directory");

        Path workspace = workspace("swim-core");

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
            assertNotNull(session.capabilities().getSemanticTokensProvider(),
                    "Expected embedded provider to advertise semantic tokens for the real swim-core module");
        } finally {
            session.closeable().close();
        }

        Path messagesLog = workspace.resolve("userdir").resolve("var").resolve("log").resolve("messages.log");
        assertTrue(Files.isRegularFile(messagesLog), "Expected NetBeans workspace log at " + messagesLog);
        String logText = Files.readString(messagesLog);
        assertTrue(!logText.contains("ClassNotFoundException: org.netbeans.api.maven.MavenActions"),
                () -> "Embedded provider left SWIM's Maven module support half-initialized.\n" + logText);
        assertTrue(!logText.contains("ClassNotFoundException: maven.actions.override"),
                () -> "Embedded provider failed loading SWIM Maven layer entries.\n" + logText);
        assertTrue(!logText.contains("ClassNotFoundException: org.netbeans.NbExit"),
                () -> "Embedded provider closed the NetBeans classloader during shutdown.\n" + logText);
    }

    @Test
    @Timeout(90)
    void embeddedProviderServesSemanticTokensForRealSwimRuntimeFile() throws Exception {
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        var provider = new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        Assumptions.assumeTrue(provider.isAvailable(), "Oracle Java extension payload not available");
        Assumptions.assumeTrue(
                EmbeddedOracleModuleLayerLspProvider.hasRequiredJvmAccess(),
                "Embedded provider requires the NetBeans-compatible JVM launcher flags");

        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path javaFile = project.resolve("src/main/java/org/fisk/swim/SwimRuntime.java");
        Assumptions.assumeTrue(Files.isRegularFile(project.resolve("pom.xml")),
                "Expected to run from the swim-core module directory");
        Assumptions.assumeTrue(Files.isRegularFile(javaFile),
                "Expected SwimRuntime.java in the swim-core sources");

        Path workspace = workspace("swim-core-semantic");

        var session = provider.start(
                project,
                workspace,
                new SilentLanguageClient(project),
                basicCapabilities(),
                initializationOptions(),
                30);
        try {
            var textDocumentService = session.server().getTextDocumentService();
            String text = Files.readString(javaFile);
            var item = new TextDocumentItem(javaFile.toUri().toString(), "java", 1, text);
            textDocumentService.didOpen(new DidOpenTextDocumentParams(item));

            SemanticTokens tokens = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                tokens = textDocumentService.semanticTokensFull(
                        new SemanticTokensParams(new TextDocumentIdentifier(item.getUri())))
                        .get(10, TimeUnit.SECONDS);
                if (tokens != null && tokens.getData() != null && !tokens.getData().isEmpty()) {
                    break;
                }
                Thread.sleep(250);
            }

            assertNotNull(tokens, "Expected semantic tokens response");
            assertNotNull(tokens.getData(), "Expected semantic token payload");
            assertTrue(!tokens.getData().isEmpty(), "Expected non-empty semantic tokens for SwimRuntime.java");
        } finally {
            session.closeable().close();
        }
    }

    @Test
    void staleWorkspaceDetectionRecognizesRecoverableMavenBootstrapFailure() throws Exception {
        Path workspace = tempDir.resolve("stale-workspace");
        Path log = workspace.resolve("userdir").resolve("var").resolve("log").resolve("messages.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, """
                java.lang.ClassNotFoundException: org.netbeans.api.maven.MavenActions
                java.lang.ClassNotFoundException: maven.actions.override
                """);

        var failure = new java.util.concurrent.ExecutionException(
                new IllegalStateException("Embedded NetBeans bootstrap exited with code -255"));

        assertTrue(EmbeddedOracleModuleLayerLspProvider.shouldResetWorkspace(workspace, failure));
    }

    @Test
    void staleWorkspaceDetectionRecognizesRecoverableLuceneIndexCorruption() throws Exception {
        Path workspace = tempDir.resolve("stale-lucene-workspace");
        Path log = workspace.resolve("userdir").resolve("var").resolve("log").resolve("messages.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, """
                Caused: org.apache.lucene.index.CorruptIndexException: doc counts differ for segment _3:
                fieldsReader shows 3 but segmentInfo shows 245
                """);

        assertTrue(EmbeddedOracleModuleLayerLspProvider.shouldResetWorkspace(workspace, null));
    }

    private static ClientCapabilities basicCapabilities() {
        var workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        workspace.setConfiguration(true);
        var textDocument = new TextDocumentClientCapabilities();
        var semanticTokens = new SemanticTokensCapabilities();
        var requests = new SemanticTokensClientCapabilitiesRequests();
        requests.setFull(true);
        requests.setRange(false);
        semanticTokens.setRequests(requests);
        semanticTokens.setFormats(List.of(TokenFormat.Relative));
        semanticTokens.setTokenTypes(List.of(
                "namespace", "type", "class", "enum", "interface", "struct", "typeParameter",
                "parameter", "variable", "property", "enumMember", "event", "function", "method",
                "macro", "keyword", "modifier", "comment", "string", "number", "regexp", "operator", "decorator"));
        semanticTokens.setTokenModifiers(List.of(
                "declaration", "definition", "readonly", "static", "deprecated",
                "abstract", "async", "modification", "documentation", "defaultLibrary"));
        semanticTokens.setMultilineTokenSupport(false);
        semanticTokens.setOverlappingTokenSupport(false);
        semanticTokens.setAugmentsSyntaxTokens(true);
        textDocument.setSemanticTokens(semanticTokens);
        return new ClientCapabilities(workspace, textDocument, null);
    }

    private Path workspace(String name) throws Exception {
        Path workspace = Path.of("target", "embedded-lsp-it",
                name + "-" + tempDir.getFileName()).toAbsolutePath().normalize();
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
        return workspace;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
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

        @Override
        public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void notifyProgress(ProgressParams params) {
        }
    }
}
