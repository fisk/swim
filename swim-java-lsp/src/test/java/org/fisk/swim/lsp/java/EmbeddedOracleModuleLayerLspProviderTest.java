package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EmbeddedOracleModuleLayerLspProviderTest {
    @Test
    @Timeout(10)
    void closingListeningSocketDoesNotLogAnExceptionToTheTerminal() throws Exception {
        Logger logger = Logger.getLogger("org.eclipse.lsp4j");
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        var loggedFailure = new AtomicReference<Throwable>();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() != null) {
                    loggedFailure.compareAndSet(null, record.getThrown());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        var executor = Executors.newSingleThreadExecutor();
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var peer = new Socket(listener.getInetAddress(), listener.getLocalPort());
                var socket = listener.accept()) {
            var reading = new CountDownLatch(1);
            var input = new FilterInputStream(socket.getInputStream()) {
                @Override
                public int read() throws IOException {
                    reading.countDown();
                    return super.read();
                }
            };
            var launcher = LSPLauncher.createClientLauncher(
                    new JavaLSPClient().createLanguageClient(), input, socket.getOutputStream(),
                    executor, Function.identity());
            var listening = launcher.startListening();
            assertTrue(reading.await(5, TimeUnit.SECONDS));

            EmbeddedOracleModuleLayerLspProvider.closeBootstrapResources(socket, listener, null, null, listening);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(socket.isClosed());
            assertTrue(listening.isCancelled());
            assertNull(loggedFailure.get(), "Expected local shutdown to stop the listener quietly");
        } finally {
            executor.shutdownNow();
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }
}
