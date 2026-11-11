package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorCaptureTest {
    @TempDir
    Path tempDir;
    private PrintStream originalErr;
    private PrintStream terminal;
    private Thread.UncaughtExceptionHandler originalHandler;
    private ByteArrayOutputStream terminalBytes;
    private Path log;

    @BeforeEach
    void install() {
        ErrorCapture.shutdown();
        ErrorReports.getInstance().clear();
        originalErr = System.err;
        originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        terminalBytes = new ByteArrayOutputStream();
        terminal = new PrintStream(terminalBytes);
        System.setErr(terminal);
        System.setProperty(SwimLogging.LOG_DIR_PROPERTY, tempDir.toString());
        log = SwimLogging.setup();
    }

    @AfterEach
    void restore() {
        ErrorCapture.shutdown();
        System.setErr(originalErr);
        Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        System.clearProperty(SwimLogging.LOG_DIR_PROPERTY);
        System.clearProperty(SwimLogging.LOG_PATH_PROPERTY);
        ErrorReports.getInstance().clear();
    }

    @Test
    void loggedExceptionsAreReportedAndStillWrittenToTheLog() throws Exception {
        SwimLogging.setup();
        LogManager.getLogger("test.editor").error("Opening Java file", new IllegalStateException("language server failed"));

        assertEquals(1, ErrorReports.getInstance().history().size());
        assertTrue(ErrorReports.getInstance().history().getFirst().operation().contains("Opening Java file"));
        assertTrue(Files.readString(log).contains("IllegalStateException: language server failed"));
        assertTrue(Files.readString(ErrorReports.reportDirectory().resolve("latest.txt")).contains("language server failed"));
        assertEquals("", terminalBytes.toString());
    }

    @Test
    void julRemainsCapturedAfterLibraryLoggingReconfiguration() throws Exception {
        java.util.logging.LogManager.getLogManager().readConfiguration();
        java.util.logging.Logger.getLogger("org.eclipse.lsp4j.jsonrpc.test").log(
                java.util.logging.Level.WARNING, "Connection failed", new java.net.SocketException("Broken pipe"));

        assertTrue(ErrorReports.getInstance().history().getFirst().details().contains("Broken pipe"));
        assertTrue(Files.readString(log).contains("Broken pipe"));
        assertEquals("", terminalBytes.toString());
    }

    @Test
    void rawPrintStackTraceIsCollectedWithoutWritingToTheTerminal() throws Exception {
        new IllegalStateException("raw failure").printStackTrace();
        await(() -> !ErrorReports.getInstance().history().isEmpty());

        assertTrue(ErrorReports.getInstance().history().getFirst().details().contains("ErrorCaptureTest.java"));
        assertTrue(Files.readString(log).contains("raw failure"));
        assertEquals("", terminalBytes.toString());
    }

    @Test
    void uncaughtBackgroundExceptionIsReportedAndRuntimeHooksAreRestored() throws Exception {
        var thread = new Thread(() -> { throw new IllegalArgumentException("background failure"); }, "test-worker");
        thread.start();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertTrue(ErrorReports.getInstance().history().getFirst().details().contains("background failure"));
        assertEquals("test-worker", ErrorReports.getInstance().history().getFirst().thread());
        assertEquals("", terminalBytes.toString());

        ErrorCapture.shutdown();
        assertSame(terminal, System.err);
        assertSame(originalHandler, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    void brokenRendererDoesNotPreventSiblingFromDrawing() {
        org.fisk.swim.terminal.TerminalContextTestSupport.install(100, 30);
        var siblingDrawn = new java.util.concurrent.atomic.AtomicBoolean();
        var bounds = org.fisk.swim.ui.Rect.create(0, 0, 50, 20);
        var root = new org.fisk.swim.ui.View(bounds);
        root.addSubview(new org.fisk.swim.ui.View(bounds) {
            @Override
            public void draw(org.fisk.swim.ui.Rect rect) {
                throw new IllegalStateException("renderer failed");
            }
        });
        root.addSubview(new org.fisk.swim.ui.View(bounds) {
            @Override
            public void draw(org.fisk.swim.ui.Rect rect) {
                siblingDrawn.set(true);
            }
        });

        assertDoesNotThrow(() -> root.update(bounds, true));
        assertTrue(siblingDrawn.get());
        assertTrue(ErrorReports.getInstance().history().getFirst().details().contains("renderer failed"));
        assertEquals("", terminalBytes.toString());
    }

    @Test
    void eventFailureAutomaticallyOpensReportAndEventLoopContinues() throws Exception {
        org.fisk.swim.terminal.TerminalContextTestSupport.install(120, 35);
        try (var harness = org.fisk.swim.ui.HeadlessWindowHarness.create(tempDir.resolve("event.txt"), 120, 35)) {
            var window = harness.getWindow();
            var shown = new java.util.concurrent.CountDownLatch(1);
            var nextAction = new java.util.concurrent.CountDownLatch(1);
            var thread = EventThread.getInstance();
            try {
                thread.addOnEvent(() -> {
                    window.update(true);
                    if (org.fisk.swim.ui.HeadlessWindowHarness.getField(window, "_workspaceView")
                            instanceof org.fisk.swim.ui.SplitView) {
                        shown.countDown();
                    }
                });
                thread.start();
                thread.enqueue(new org.fisk.swim.event.RunnableEvent(() -> {
                    throw new IllegalStateException("editor action failed");
                }));
                assertTrue(shown.await(3, TimeUnit.SECONDS));
                thread.enqueue(new org.fisk.swim.event.RunnableEvent(nextAction::countDown));
                assertTrue(nextAction.await(3, TimeUnit.SECONDS));
                assertEquals("", terminalBytes.toString());
            } finally {
                EventThread.shutdownInstance();
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean());
    }
}
