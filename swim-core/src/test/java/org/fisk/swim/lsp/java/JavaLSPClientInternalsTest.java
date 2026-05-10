package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLSPClientInternalsTest {
    @TempDir
    Path tempDir;

    @Test
    void languageClientCallbacksReturnStableDefaultValues() throws Exception {
        var client = new JavaLSPClient();
        setField(client, "_projectPath", tempDir.resolve("demo-project"));
        LanguageClient languageClient = client.createLanguageClient();

        languageClient.telemetryEvent("ping");
        languageClient.publishDiagnostics(new PublishDiagnosticsParams());
        languageClient.showMessage(new MessageParams());
        languageClient.logMessage(new MessageParams());

        assertNull(languageClient.showMessageRequest(new ShowMessageRequestParams()).get());
        assertEquals(List.of(), languageClient.configuration(new ConfigurationParams()).get());
        assertFalse(languageClient.applyEdit(new ApplyWorkspaceEditParams()).get().isApplied());
        assertNull(languageClient.registerCapability(new RegistrationParams()).get());
        assertNull(languageClient.unregisterCapability(new UnregistrationParams()).get());
        assertNull(languageClient.createProgress(new WorkDoneProgressCreateParams()).get());
        languageClient.notifyProgress(new ProgressParams());

        List<WorkspaceFolder> folders = languageClient.workspaceFolders().get();
        assertEquals(1, folders.size());
        assertEquals("demo-project", folders.get(0).getName());
        assertEquals(tempDir.resolve("demo-project").toUri().toString(), folders.get(0).getUri());
    }

    @Test
    void shutdownHookDestroysProcessBeforeStartupCompletes() throws Exception {
        var client = new JavaLSPClient();
        var process = new RecordingProcess();
        setField(client, "_process", process);
        setField(client, "_started", false);

        client.createShutdownHook().run();

        assertTrue(process.destroyed);
        assertFalse(process.destroyForciblyCalled);
    }

    @Test
    void shutdownHookShutsServerDownAfterStartup() throws Exception {
        var client = new JavaLSPClient();
        var process = new RecordingProcess();
        var server = new RecordingLanguageServer();
        setField(client, "_process", process);
        setField(client, "_server", server);
        setField(client, "_started", true);

        client.createShutdownHook().run();

        assertTrue(server.shutdownCalled);
        assertTrue(server.exitCalled);
        assertFalse(process.destroyed);
    }

    @Test
    void customOracleRequestsAreHandled() throws Exception {
        var client = new JavaLSPClient();
        Endpoint endpoint = ServiceEndpoints.toEndpoint(client.createLanguageClient());

        endpoint.request("output/write", Map.of(
                "outputName", "Oracle Java",
                "message", "hello",
                "stdIO", false)).get();
        endpoint.request("output/show", "Oracle Java").get();
        endpoint.request("output/reset", "Oracle Java").get();
        endpoint.request("output/close", "Oracle Java").get();
        endpoint.request("window/showHtmlPage", Map.of("text", "<p>hello</p>")).get();

        @SuppressWarnings("unchecked")
        var outputBuffers = (Map<String, StringBuilder>) getField(client, "_outputBuffers");
        assertFalse(outputBuffers.containsKey("Oracle Java"));
    }

    @Test
    void javaLspPackageIsOpenToLsp4jJsonRpc() {
        Module clientModule = JavaLSPClient.class.getModule();
        Module jsonRpcModule = Endpoint.class.getModule();

        assertTrue(clientModule.isOpen("org.fisk.swim.lsp.java", jsonRpcModule));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class RecordingProcess extends Process {
        private boolean destroyed;
        private boolean destroyForciblyCalled;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class RecordingLanguageServer implements LanguageServer {
        private boolean shutdownCalled;
        private boolean exitCalled;

        @Override
        public CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(org.eclipse.lsp4j.InitializeParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            shutdownCalled = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            exitCalled = true;
        }

        @Override
        public org.eclipse.lsp4j.services.TextDocumentService getTextDocumentService() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }
}
