package org.fisk.swim.plugins.git;

import java.util.List;

record GitDiffView(
        String preamble,
        List<String> lines,
        List<GitDiffHunk> hunks,
        GitPatchOperation defaultEditOperation) {
    boolean hasHunks() {
        return !hunks.isEmpty();
    }
}
