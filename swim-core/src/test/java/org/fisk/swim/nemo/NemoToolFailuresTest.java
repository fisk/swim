package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.fisk.swim.ErrorReports;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NemoToolFailuresTest {
    @TempDir Path tempDir;
    private Level previousLevel;
    private Path project;
    private BufferContext context;
    private NemoClient.Configuration configuration;

    @BeforeEach
    void captureErrors() throws Exception {
        errorCapture("shutdown");
        project = Files.createDirectory(tempDir.resolve("project"));
        Path source = project.resolve("note.txt");
        Files.writeString(source, "project-local fixture\n");
        context = new BufferContext(Rect.create(0, 0, 80, 20), source);
        configuration = NemoClient.Configuration.builder().workspaceRoot(project).build();
        previousLevel = LogManager.getLogger(NemoClient.class).getLevel();
        errorCapture("install");
        Configurator.setLevel(NemoClient.class.getName(), Level.WARN);
    }

    @AfterEach
    void restore() throws Exception {
        errorCapture("shutdown");
        if (previousLevel != null) {
            Configurator.setLevel(NemoClient.class.getName(), previousLevel);
        }
        NemoClient.getInstance().resetForTests();
    }

    @Test
    void projectLocalToolsStillSucceedWithErrorReportingEnabled() throws Exception {
        assertTrue(call("list_files", Map.of("path", ".")).contains("note.txt"));
        assertTrue(call("read_file", Map.of("path", "note.txt")).contains("project-local fixture"));
        assertTrue(ErrorReports.getInstance().history().isEmpty());
        assertTrue(ErrorReports.getInstance().takePending().isEmpty());
    }

    @Test
    void outsideWorkspaceToolsReturnRecoveryGuidanceWithoutOpeningAnErrorReport() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path secret = outside.resolve("private.txt");
        Files.writeString(secret, "OUTSIDE-CONTENT-MUST-STAY-PRIVATE\n");
        for (Map<String, String> arguments : java.util.List.of(
                Map.of("path", outside.toString()), Map.of("directory", "../outside"))) {
            String output = call("list_files", arguments);
            assertTrue(output.contains("Path escapes workspace root"));
            assertTrue(output.contains(project.toString()));
            assertTrue(output.contains("request_directory_access"));
            assertFalse(output.contains("private.txt"));
        }
        for (Map<String, String> arguments : java.util.List.of(
                Map.of("path", "../outside/private.txt"),
                Map.of("directory", ".", "path", "../outside/private.txt"))) {
            String output = call("read_file", arguments);
            assertTrue(output.contains("Path escapes workspace root"));
            assertFalse(output.contains("OUTSIDE-CONTENT-MUST-STAY-PRIVATE"));
        }
        String write = call("write_file", Map.of("path", secret.toString(), "content", "replacement"));
        assertTrue(write.contains("Path escapes workspace root"));
        assertEquals("OUTSIDE-CONTENT-MUST-STAY-PRIVATE\n", Files.readString(secret));
        assertTrue(ErrorReports.getInstance().history().isEmpty());
        assertTrue(ErrorReports.getInstance().takePending().isEmpty());
    }

    @Test
    void privateReportsStayBlockedWithoutSuggestingTheyCanBeGranted() throws Exception {
        String previousDirectory = System.getProperty(ErrorReports.DIRECTORY_PROPERTY);
        Path reports = Files.createDirectory(project.resolve("reports"));
        Files.writeString(reports.resolve("latest.txt"), "PRIVATE-REPORT-FIXTURE\n");
        try {
            System.setProperty(ErrorReports.DIRECTORY_PROPERTY, reports.toString());
            for (String path : java.util.List.of("reports/latest.txt", "project/reports/latest.txt")) {
                String output = call("read_file", Map.of("path", path));
                assertTrue(output.contains("Saved error reports are private"));
                assertFalse(output.contains("PRIVATE-REPORT-FIXTURE"));
                assertFalse(output.contains("request_directory_access"));
            }
            assertTrue(ErrorReports.getInstance().history().isEmpty());
        } finally {
            if (previousDirectory == null) {
                System.clearProperty(ErrorReports.DIRECTORY_PROPERTY);
            } else {
                System.setProperty(ErrorReports.DIRECTORY_PROPERTY, previousDirectory);
            }
        }
    }

    private String call(String tool, Map<String, String> arguments) throws Exception {
        var json = new JsonObject();
        arguments.forEach(json::addProperty);
        return NemoClient.executeToolSafely(configuration, context, new NemoClient.ToolCall("fixture", tool, json));
    }

    private static void errorCapture(String action) throws Exception {
        var method = Class.forName("org.fisk.swim.ErrorCapture").getDeclaredMethod(action);
        method.setAccessible(true);
        method.invoke(null);
    }
}
