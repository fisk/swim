package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NemoMcpTimeoutTest {
    @Test
    void unlimitedWaitAcceptsDelayedResponseAndRemainsInterruptible() throws Exception {
        Object session = session(0);
        var wait = session.getClass().getDeclaredMethod("waitForResponse", long.class);
        wait.setAccessible(true);
        var field = session.getClass().getDeclaredField("_messages");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var messages = (BlockingQueue<JsonObject>) field.get(session);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> wait.invoke(session, 1L));
            Thread.sleep(1200);
            assertFalse(pending.isDone(), "Zero must not be clamped to a one-second timeout");
            var response = new JsonObject();
            response.addProperty("id", 1);
            response.add("result", new JsonObject());
            messages.add(response);
            assertEquals(new JsonObject(), pending.get(2, TimeUnit.SECONDS));
            var interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                return assertThrows(InvocationTargetException.class, () -> wait.invoke(session, 2L)).getCause();
            });
            assertInstanceOf(InterruptedException.class, interrupted.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void explicitPositiveTimeoutStillApplies() throws Exception {
        Object session = session(1);
        var wait = session.getClass().getDeclaredMethod("waitForResponse", long.class);
        wait.setAccessible(true);
        var error = assertThrows(InvocationTargetException.class, () -> wait.invoke(session, 1L));
        assertTrue(error.getCause().getMessage().contains("Timed out waiting for MCP response"));
    }

    private Object session(int seconds) throws Exception {
        var config = new NemoMcpServerConfig("rr", true, "unused", List.of(), Map.of(), null, seconds);
        assertEquals(seconds, config.timeoutSeconds());
        var type = Class.forName("org.fisk.swim.nemo.NemoMcpClient$Session");
        var constructor = type.getDeclaredConstructor(NemoMcpServerConfig.class);
        constructor.setAccessible(true);
        return constructor.newInstance(config);
    }
}
