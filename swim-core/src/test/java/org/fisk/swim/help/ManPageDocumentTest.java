package org.fisk.swim.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManPageDocumentTest {
    @TempDir
    Path tempDir;

    @Test
    void manPageIncludesGeneratedEditorHelpContent() {
        String manPage = ManPageDocument.render();

        assertTrue(manPage.contains(".SH EDITOR HELP"));
        assertTrue(manPage.contains("This section is generated from the in-editor :help document."));
        assertTrue(manPage.contains("Start Here"));
        assertTrue(manPage.contains("SWIM starts in NORMAL mode"));
        assertTrue(manPage.contains("Files, Buffers, and Panes"));
        assertTrue(manPage.contains("Ctrl-b opens SWIM's tmux-style prefix layer"));
        assertTrue(manPage.contains("Server sessions"));
        assertTrue(manPage.contains("swim --attach scratch"));
    }

    @Test
    void installerWritesGeneratedManPageToPairedShareManPath() throws Exception {
        Path target = ManPageInstaller.install(tempDir);

        assertEquals(tempDir.resolve("share").resolve("man").resolve("man1").resolve("swim.1"), target);
        assertTrue(Files.isRegularFile(target));
        String content = Files.readString(target);
        assertTrue(content.contains(".TH SWIM 1"));
        assertTrue(content.contains("SWIM starts in NORMAL mode"));
    }
}
