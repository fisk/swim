package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

class NemoMcpClientTest {
    @TempDir
    Path tempDir;

    private NemoMcpServerConfig config(String name, String marker, boolean fail) {
        var args = new ArrayList<>(List.of("-cp", System.getProperty("java.class.path"),
                FakeMcpServer.class.getName(), tempDir.resolve(marker).toString()));
        if (fail) {
            args.add("fail");
        }
        return new NemoMcpServerConfig(name, true,
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                args, Map.of(), null, 5);
    }

    private String call(NemoMcpClient client, List<NemoMcpServerConfig> configs, String name) throws Exception {
        var arguments = new JsonObject();
        arguments.addProperty("message", "hello");
        return client.callTool(configs, name, arguments, 12000);
    }

    @Test
    void brokenEarlierServerDoesNotBlockDiscoveredToolOrRestartForUnknownTools() throws Exception {
        var client = new NemoMcpClient();
        var configs = List.of(config("broken", "broken", true), config("rr", "rr", false));
        try {
            assertEquals(1, client.listTools(configs).size());
            assertEquals("echo: hello", call(client, configs, "mcp__rr__echo"));
            assertEquals("Unknown MCP tool: absent", call(client, configs, "absent"));
            assertEquals(1, Files.readAllLines(tempDir.resolve("broken")).size());
            assertEquals(1, Files.readAllLines(tempDir.resolve("rr")).size());
        } finally {
            client.shutdownAll();
        }
    }

    @Test
    void unavailableServerIsDiagnosedWithoutPublishingAnEditorException() {
        var client = new NemoMcpClient();
        var unavailable = new NemoMcpServerConfig("unavailable-fixture", true,
                tempDir.resolve("missing-mcp-command").toString(), List.of(), Map.of(), null, 1);
        var events = new java.util.concurrent.CopyOnWriteArrayList<org.apache.logging.log4j.core.LogEvent>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(NemoMcpClient.class);
        var previousLevel = logger.getLevel();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
                "mcp-discovery-fixture", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(org.apache.logging.log4j.Level.WARN);
        try {
            assertTrue(client.listTools(List.of(unavailable)).isEmpty());
            assertTrue(events.stream().anyMatch(event -> event.getMessage().getFormattedMessage().contains("unavailable-fixture")));
            assertTrue(events.stream().allMatch(event -> event.getThrown() == null));
            assertTrue(client.status(List.of(unavailable)).contains("unavailable-fixture | failed:"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            client.shutdownAll();
        }
    }

    @Test
    void coldCallsAndStatusIsolateFailedServers() throws Exception {
        var client = new NemoMcpClient();
        var configs = List.of(config("broken", "broken", true), config("rr", "rr", false));
        try {
            assertEquals("echo: hello", call(client, configs, "mcp__rr__echo"));
            assertTrue(client.status(configs).contains("broken | failed:"));
            assertEquals("echo: hello", call(client, configs, "mcp__rr__echo"));
            assertEquals(2, Files.readAllLines(tempDir.resolve("broken")).size());
        } finally {
            client.shutdownAll();
        }
    }

    @Test
    void changedRemovedAndDisabledServersCannotUseStaleRoutes() throws Exception {
        var client = new NemoMcpClient();
        var original = config("rr", "original", false);
        try {
            assertEquals("echo: hello", call(client, List.of(original), "mcp__rr__echo"));
            var replacement = config("rr", "replacement", false);
            assertEquals("echo: hello", call(client, List.of(replacement), "mcp__rr__echo"));
            assertTrue(Files.exists(tempDir.resolve("replacement")));
            var disabled = new NemoMcpServerConfig(replacement.name(), false, replacement.command(),
                    replacement.args(), replacement.env(), replacement.cwd(), 5);
            assertTrue(call(client, List.of(disabled), "mcp__rr__echo").startsWith("Unknown MCP tool:"));
            assertTrue(call(client, List.of(), "mcp__rr__echo").startsWith("Unknown MCP tool:"));
            client.shutdownAll();
            assertEquals("echo: hello", call(client, List.of(original), "mcp__rr__echo"));
            assertEquals(2, Files.readAllLines(tempDir.resolve("original")).size());
        } finally {
            client.shutdownAll();
        }
    }

    @Test
    void sanitizedNameCollisionsKeepActualOwners() throws Exception {
        var client = new NemoMcpClient();
        var configs = List.of(config("same.name", "first", false), config("same name", "second", false));
        try {
            var tools = client.listTools(configs);
            assertEquals("same.name", tools.get(0).serverName());
            assertEquals("same name", tools.get(1).serverName());
            assertEquals("mcp__same_name__echo_2", tools.get(1).exposedName());
            assertEquals("echo: hello", call(client, configs, tools.get(1).exposedName()));
            assertFalse(Files.exists(tempDir.resolve("first.calls")));
            assertEquals(1, Files.readAllLines(tempDir.resolve("second.calls")).size());
        } finally {
            client.shutdownAll();
        }
    }
}
