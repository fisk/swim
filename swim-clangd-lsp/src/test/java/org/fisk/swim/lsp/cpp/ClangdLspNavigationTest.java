package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.LspFeaturePopupView;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClangdLspNavigationTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ClangdLspClient.shutdownInstalledInstance();
        if (org.fisk.swim.ui.Window.getInstance() != null) {
            org.fisk.swim.ui.Window.getInstance().dispose();
        }
    }

    @Test
    void goToDefinitionShowsPopupWhenThereAreMultipleTargets() throws Exception {
        Path current = tempDir.resolve("current.cpp");
        Path targetOne = tempDir.resolve("target-one.cpp");
        Path targetTwo = tempDir.resolve("target-two.cpp");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first\n");
        Files.writeString(targetTwo, "second\nline\n");

        var client = new TestClangdLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new DefinitionLanguageServer(Either.forRight(List.of(
                new LocationLink(targetOne.toUri().toString(),
                        new Range(new Position(0, 0), new Position(0, 5)),
                        new Range(new Position(0, 0), new Position(0, 5))),
                new LocationLink(targetTwo.toUri().toString(),
                        new Range(new Position(1, 1), new Position(1, 4)),
                        new Range(new Position(1, 1), new Position(1, 4)))))));
        setField(client, "_projectPath", tempDir);

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();

            client.goToDefinition(window.getBufferContext());

            var popup = waitForLspPopup(window);
            assertEquals("Definitions", popup.getTitle());
            assertEquals(2, popup.getEntries().size());
            assertTrue(popup.getEntries().get(0).label().contains("target-one.cpp:1:1"));
        }
    }

    @Test
    void referencesPopupEnterJumpsToSelectedTarget() throws Exception {
        Path current = tempDir.resolve("current-ref.cpp");
        Path targetOne = tempDir.resolve("ref-one.cpp");
        Path targetTwo = tempDir.resolve("ref-two.cpp");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first\n");
        Files.writeString(targetTwo, "zero\nchosen\n");

        var client = new TestClangdLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new ReferencesLanguageServer(List.of(
                new Location(targetOne.toUri().toString(), new Range(new Position(0, 0), new Position(0, 3))),
                new Location(targetTwo.toUri().toString(), new Range(new Position(1, 2), new Position(1, 6))))));
        setField(client, "_projectPath", tempDir);

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();
            client.findReferences(window.getBufferContext());

            var popup = waitForLspPopup(window);
            assertEquals("References", popup.getTitle());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.enter());
            waitForLocation(window, targetTwo, 1, 2);

            assertEquals(targetTwo.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(window.getBufferContext().getTextLayout().getIndexForPhysicalLineCharacter(1, 2),
                    window.getBufferContext().getBuffer().getCursor().getPosition());
            assertSame(window.getBufferContext().getBufferView(), window.getRootView().getFirstResponder());
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static LspFeaturePopupView waitForLspPopup(Window window) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (window.getRootView().getFirstResponder() instanceof LspFeaturePopupView popup) {
                return popup;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for LSP popup");
    }

    private static void waitForLocation(Window window, Path path, int line, int character) throws Exception {
        Path expectedPath = path.toAbsolutePath().normalize();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Path actualPath = window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize();
            if (expectedPath.equals(actualPath)) {
                int expectedCursor = window.getBufferContext().getTextLayout()
                        .getIndexForPhysicalLineCharacter(line, character);
                if (window.getBufferContext().getBuffer().getCursor().getPosition() == expectedCursor) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for location " + expectedPath + ":" + (line + 1) + ":" + (character + 1));
    }

    private static final class TestClangdLspClient extends ClangdLspClient {
        private TestClangdLspClient() {
            super(new ClangdLspProvider(Path.of("/usr/bin/clangd")));
        }

        @Override
        public boolean hasStarted() {
            return true;
        }

        @Override
        public synchronized void startServer(Path filePath) {
        }

        @Override
        public void ensureInit() {
        }
    }

    private static final class DefinitionLanguageServer implements LanguageServer {
        private final Either<List<? extends Location>, List<? extends LocationLink>> _response;

        private DefinitionLanguageServer(Either<List<? extends Location>, List<? extends LocationLink>> response) {
            _response = response;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override
                public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
                }

                @Override
                public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
                }

                @Override
                public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
                }

                @Override
                public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
                }

                @Override
                public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                        org.eclipse.lsp4j.DefinitionParams params) {
                    return CompletableFuture.completedFuture(_response);
                }
            };
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ReferencesLanguageServer implements LanguageServer {
        private final List<Location> _locations;

        private ReferencesLanguageServer(List<Location> locations) {
            _locations = locations;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override
                public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
                }

                @Override
                public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
                }

                @Override
                public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
                }

                @Override
                public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
                }

                @Override
                public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
                    return CompletableFuture.completedFuture(_locations);
                }
            };
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }
}
