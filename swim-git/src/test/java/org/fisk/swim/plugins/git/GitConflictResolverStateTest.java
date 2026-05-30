package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitConflictResolverStateTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesConflictBlocksAndWritesResolvedResult() throws Exception {
        Path file = Files.writeString(tempDir.resolve("conflict.txt"), """
                before
                <<<<<<< HEAD
                ours
                ||||||| base
                base-line
                =======
                theirs
                >>>>>>> branch
                after
                """);

        GitConflictResolverState state = GitConflictResolverState.parse(file);
        assertEquals(1, state.blockCount());
        assertEquals(List.of("base-line"), state.selectedBlock().base());
        assertEquals(List.of("ours"), state.selectedBlock().ours());
        assertEquals(List.of("theirs"), state.selectedBlock().theirs());

        state.chooseBoth();
        state.writeResolvedFile();

        String resolved = Files.readString(file);
        assertTrue(!resolved.contains("<<<<<<<"));
        assertEquals("""
                before
                ours
                theirs
                after
                """, resolved);
    }
}
