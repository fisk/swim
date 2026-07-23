package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStatusServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotSeparatesStagedUnstagedAndUntrackedChanges() throws Exception {
        Path repo = initRepository();
        Path staged = Files.writeString(repo.resolve("staged.txt"), "staged\n");
        Path unstaged = Files.writeString(repo.resolve("unstaged.txt"), "base\n");
        commitAll(repo, "initial");

        Files.writeString(staged, "staged changed\n");
        try (var git = Git.open(repo.toFile())) {
            git.add().addFilepattern("staged.txt").call();
        }
        Files.writeString(unstaged, "base changed\n");
        Files.writeString(repo.resolve("untracked.txt"), "new\n");

        GitStatusSnapshot snapshot = GitStatusService.snapshot(staged);

        assertTrue(snapshot.hasRepository());
        assertEquals(List.of("staged.txt"), snapshot.staged().stream().map(GitFileChange::relativePath).toList());
        assertEquals(List.of("unstaged.txt"), snapshot.unstaged().stream().map(GitFileChange::relativePath).toList());
        assertEquals(List.of("untracked.txt"), snapshot.untracked().stream().map(GitFileChange::relativePath).toList());
    }

    @Test
    void stageUnstageDiscardAndCommitRoundTrip() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("note.txt"), "one\n");
        commitAll(repo, "initial");

        Files.writeString(file, "two\n");
        GitFileChange unstaged = changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "note.txt");
        GitStatusService.stage(repo, unstaged);
        assertEquals(List.of("note.txt"), GitStatusService.snapshot(file).staged().stream()
                .map(GitFileChange::relativePath).toList());

        GitFileChange staged = changeFor(GitStatusService.snapshot(file), GitSection.STAGED, "note.txt");
        GitStatusService.unstage(repo, staged);
        assertEquals(List.of("note.txt"), GitStatusService.snapshot(file).unstaged().stream()
                .map(GitFileChange::relativePath).toList());

        GitStatusService.discard(repo, changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "note.txt"));
        assertTrue(GitStatusService.snapshot(file).isClean());
        assertEquals("one\n", Files.readString(file));

        Files.writeString(file, "three\n");
        GitStatusService.stage(repo, changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "note.txt"));
        GitStatusService.commit(repo, "update note");

        GitStatusSnapshot committed = GitStatusService.snapshot(file);
        assertTrue(committed.isClean());
        try (var git = Git.open(repo.toFile())) {
            String message = git.log().setMaxCount(1).call().iterator().next().getFullMessage();
            assertEquals("update note", message);
        }
    }

    @Test
    void diffTextShowsTrackedFilePatch() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("diff.txt"), "alpha\n");
        commitAll(repo, "initial");

        Files.writeString(file, "beta\n");
        GitFileChange change = changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "diff.txt");
        String diff = GitStatusService.diffText(repo, change);

        assertTrue(diff.contains("---"));
        assertTrue(diff.contains("+++"));
        assertTrue(diff.contains("-alpha"));
        assertTrue(diff.contains("+beta"));
    }

    @Test
    void resolveConflictWithBothRemovesMarkersAndStagesFile() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("conflict.txt"), """
                <<<<<<< HEAD
                ours
                =======
                theirs
                >>>>>>> branch
                """);
        commitAll(repo, "initial");

        Files.writeString(file, """
                <<<<<<< HEAD
                left
                =======
                right
                >>>>>>> branch
                """);
        GitStatusService.resolveConflictWithBoth(repo, new GitFileChange(GitSection.CONFLICTS, "conflict.txt", file, "U"));

        assertFalse(Files.readString(file).contains("<<<<<<<"));
        assertTrue(Files.readString(file).contains("left"));
        assertTrue(Files.readString(file).contains("right"));
    }

    @Test
    void canStageAndDiscardSingleHunksFromUnstagedDiff() throws Exception {
        assumeGitExecutable();
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("hunks.txt"), """
                one
                two
                three
                four
                five
                six
                seven
                eight
                nine
                ten
                eleven
                twelve
                thirteen
                fourteen
                fifteen
                sixteen
                seventeen
                eighteen
                nineteen
                twenty
                """);
        commitAll(repo, "initial");

        Files.writeString(file, """
                ONE
                two
                three
                FOUR
                five
                six
                seven
                eight
                nine
                ten
                eleven
                twelve
                thirteen
                fourteen
                fifteen
                sixteen
                seventeen
                eighteen
                nineteen
                TWENTY
                """);

        GitDiffView diffView = GitStatusService.buildDiffView(repo,
                changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "hunks.txt"));
        assertEquals(2, diffView.hunks().size());

        GitStatusService.stageHunk(repo, diffView.hunks().getFirst());
        GitStatusSnapshot snapshot = GitStatusService.snapshot(file);
        assertEquals(List.of("hunks.txt"), snapshot.staged().stream().map(GitFileChange::relativePath).toList());
        assertEquals(List.of("hunks.txt"), snapshot.unstaged().stream().map(GitFileChange::relativePath).toList());

        GitDiffView remaining = GitStatusService.buildDiffView(repo,
                changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "hunks.txt"));
        GitStatusService.discardHunk(repo, remaining.hunks().getFirst());

        assertEquals("""
                ONE
                two
                three
                FOUR
                five
                six
                seven
                eight
                nine
                ten
                eleven
                twelve
                thirteen
                fourteen
                fifteen
                sixteen
                seventeen
                eighteen
                nineteen
                twenty
                """, Files.readString(file));
    }

    @Test
    void canApplyEditedHunkPatchToIndex() throws Exception {
        assumeGitExecutable();
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("patch-edit.txt"), """
                alpha
                beta
                gamma
                """);
        commitAll(repo, "initial");

        Files.writeString(file, """
                ALPHA
                beta
                gamma
                """);

        GitDiffView diffView = GitStatusService.buildDiffView(repo,
                changeFor(GitStatusService.snapshot(file), GitSection.UNSTAGED, "patch-edit.txt"));
        String editedPatch = diffView.hunks().getFirst().patchText().replace("ALPHA", "EDITED");
        GitStatusService.applyPatch(repo, editedPatch, GitPatchOperation.STAGE_HUNK);

        GitFileChange staged = changeFor(GitStatusService.snapshot(file), GitSection.STAGED, "patch-edit.txt");
        String stagedDiff = GitStatusService.diffText(repo, staged);
        assertTrue(stagedDiff.contains("+EDITED"));
    }

    @Test
    void snapshotIncludesStashCommitAndReflogEntries() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("history.txt"), "one\n");
        commitAll(repo, "initial");
        Files.writeString(file, "two\n");
        GitStatusService.createStash(repo, "swim stash");

        GitStatusSnapshot snapshot = GitStatusService.snapshot(file);

        assertFalse(snapshot.stashes().isEmpty());
        assertFalse(snapshot.commits().isEmpty());
        assertFalse(snapshot.reflogEntries().isEmpty());
        assertTrue(GitStatusService.stashText(repo, snapshot.stashes().getFirst()).contains("stash"));
        assertTrue(GitStatusService.commitText(repo, snapshot.commits().getFirst()).contains("initial"));
        assertTrue(!GitStatusService.reflogText(repo, snapshot.reflogEntries().getFirst()).isBlank());
    }

    @Test
    void canStageAllUnstageAllAndPopStash() throws Exception {
        Path repo = initRepository();
        Path tracked = Files.writeString(repo.resolve("tracked.txt"), "one\n");
        commitAll(repo, "initial");

        Files.writeString(tracked, "two\n");
        Path untracked = Files.writeString(repo.resolve("new.txt"), "new\n");
        GitStatusService.stageAll(repo);
        GitStatusSnapshot staged = GitStatusService.snapshot(tracked);
        assertTrue(staged.staged().stream().anyMatch(change -> "tracked.txt".equals(change.relativePath())));
        assertFalse(staged.staged().stream().anyMatch(change -> "new.txt".equals(change.relativePath())));
        assertTrue(staged.untracked().stream().anyMatch(change -> "new.txt".equals(change.relativePath())));

        GitStatusService.unstageAll(repo);
        GitStatusSnapshot unstaged = GitStatusService.snapshot(tracked);
        assertTrue(unstaged.unstaged().stream().anyMatch(change -> "tracked.txt".equals(change.relativePath())));
        assertTrue(unstaged.untracked().stream().anyMatch(change -> "new.txt".equals(change.relativePath())));

        GitStatusService.createStash(repo, "stash everything");
        GitStatusSnapshot stashed = GitStatusService.snapshot(tracked);
        assertTrue(stashed.stashes().size() == 1);
        assertEquals("one\n", Files.readString(tracked));
        assertFalse(Files.exists(untracked));

        GitStatusService.popStash(repo, stashed.stashes().getFirst());
        assertEquals("two\n", Files.readString(tracked));
        assertTrue(Files.exists(untracked));
        assertTrue(GitStatusService.snapshot(tracked).stashes().isEmpty());
    }

    @Test
    void canCherryPickCommitFromAnotherBranch() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("note.txt"), "base\n");
        commitAll(repo, "initial");

        try (var git = Git.open(repo.toFile())) {
            String main = git.getRepository().getBranch();
            git.branchCreate().setName("side").call();
            git.checkout().setName("side").call();
            Files.writeString(file, "picked\n");
            git.add().addFilepattern("note.txt").call();
            git.commit().setMessage("picked change")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            var commit = git.log().setMaxCount(1).call().iterator().next();
            git.checkout().setName(main).call();

            GitStatusService.cherryPick(repo,
                    new GitCommitEntry(commit.getId().name(), commit.getShortMessage(), "Test"));
        }

        assertEquals("picked\n", Files.readString(file));
    }

    @Test
    void canRunInteractiveRebaseDroppingCommit() throws Exception {
        Path repo = initRepository();
        Path file = Files.writeString(repo.resolve("second.txt"), "base\n");
        commitAll(repo, "initial");

        try (var git = Git.open(repo.toFile())) {
            Files.writeString(repo.resolve("first.txt"), "first\n");
            git.add().addFilepattern("first.txt").call();
            git.commit().setMessage("first")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            Files.writeString(file, "second\n");
            git.add().addFilepattern("second.txt").call();
            git.commit().setMessage("second")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        GitHistoryGraphEntry upstream;
        try (var git = Git.open(repo.toFile())) {
            var commits = GitStatusService.historyGraphEntries(repo, 10);
            upstream = commits.get(2);
        }

        List<GitRebaseEntry> entries = GitStatusService.interactiveRebaseEntries(repo, upstream.objectId());
        assertEquals(2, entries.size());
        entries.get(0).setAction(GitRebaseEntry.Action.DROP);
        GitStatusService.runInteractiveRebase(repo, upstream.objectId(), entries);

        assertEquals("second\n", Files.readString(file));
        assertFalse(Files.exists(repo.resolve("first.txt")));
        try (var git = Git.open(repo.toFile())) {
            var log = git.log().setMaxCount(3).call().iterator();
            assertEquals("second", log.next().getShortMessage());
            assertEquals("initial", log.next().getShortMessage());
        }
    }

    private Path initRepository() throws Exception {
        Path repo = tempDir.resolve("repo-" + System.nanoTime());
        Files.createDirectories(repo);
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            return repo;
        }
    }

    private void commitAll(Path repo, String message) throws Exception {
        try (var git = Git.open(repo.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage(message)
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }
    }

    private GitFileChange changeFor(GitStatusSnapshot snapshot, GitSection section, String relativePath) {
        return (switch (section) {
        case STAGED -> snapshot.staged().stream();
        case UNSTAGED -> snapshot.unstaged().stream();
        case UNTRACKED -> snapshot.untracked().stream();
        case CONFLICTS -> snapshot.conflicts().stream();
        case STASHES, COMMITS, REFLOG -> throw new IllegalArgumentException("Not a file section: " + section);
        }).filter(change -> relativePath.equals(change.relativePath()))
                .findFirst()
                .orElseThrow();
    }

    private void assumeGitExecutable() {
        try {
            var process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            Assumptions.assumeTrue(process.waitFor() == 0, "git executable is required for hunk apply tests");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "git executable is required for hunk apply tests");
        }
    }
}
