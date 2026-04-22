package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLSPClientTest {
    @TempDir
    Path tempDir;

    @Test
    void choosesPlatformSpecificConfigurationDirectory() {
        assertEquals("config_mac_arm", JavaLSPClient.getConfigurationDirectoryName("Darwin", "aarch64"));
        assertEquals("config_mac", JavaLSPClient.getConfigurationDirectoryName("Mac OS X", "x86_64"));
        assertEquals("config_linux", JavaLSPClient.getConfigurationDirectoryName("Linux", "x86_64"));
    }

    @Test
    void findsVersionedLauncherJar() throws IOException {
        Path repo = tempDir.resolve("repo");
        Path plugins = repo.resolve("plugins");
        Files.createDirectories(plugins);
        Path launcher = plugins.resolve("org.eclipse.equinox.launcher_9.9.9.jar");
        Files.writeString(launcher, "launcher");

        assertEquals(launcher, JavaLSPClient.findLauncherJar(repo));
    }

    @Test
    void workspacePathIsStableForProjectRoot() {
        Path swimHome = tempDir.resolve(".swim");
        Path project = tempDir.resolve("demo-project");

        Path workspacePath = JavaLSPClient.getWorkspacePath(swimHome, project);

        assertTrue(workspacePath.startsWith(swimHome.resolve("workspace")));
        assertTrue(workspacePath.getFileName().toString().startsWith("demo-project-"));
    }

    @Test
    void appliesWorkspaceEditWithoutShiftingLaterRanges() throws IOException {
        Path file = tempDir.resolve("Example.txt");
        Files.writeString(file, "alpha beta gamma");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        var edit = new WorkspaceEdit();
        edit.setChanges(java.util.Map.of(
                file.toUri().toString(),
                List.of(
                        new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "A"),
                        new TextEdit(new Range(new Position(0, 11), new Position(0, 16)), "G"))));

        JavaLSPClient.applyWorkspaceEdit(context, edit);

        assertEquals("A beta G", context.getBuffer().getString());
    }

    @Test
    void openedTextDocumentUsesCurrentBufferVersion() throws IOException {
        Path file = tempDir.resolve("Versioned.txt");
        Files.writeString(file, "class Versioned {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();

        context.getBuffer().insert("x");

        assertEquals(context.getBuffer().getVersionedTextDocumentID().getVersion(),
                client.getTextDocument(context).getVersion());
    }
}
