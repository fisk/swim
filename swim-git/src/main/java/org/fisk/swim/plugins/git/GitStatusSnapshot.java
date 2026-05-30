package org.fisk.swim.plugins.git;

import java.nio.file.Path;
import java.util.List;

record GitStatusSnapshot(
        Path repositoryRoot,
        String branch,
        List<GitFileChange> staged,
        List<GitFileChange> unstaged,
        List<GitFileChange> untracked,
        List<GitFileChange> conflicts,
        List<GitStashEntry> stashes,
        List<GitCommitEntry> commits,
        List<GitReflogEntryView> reflogEntries,
        GitOperationState operationState,
        String statusMessage) {
    static GitStatusSnapshot noRepository() {
        return new GitStatusSnapshot(null, "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                GitOperationState.idle(), "No Git repository");
    }

    boolean hasRepository() {
        return repositoryRoot != null;
    }

    boolean isClean() {
        return staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicts.isEmpty();
    }
}
