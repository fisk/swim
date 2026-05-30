package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jgit.api.Git;
import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorGitIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanOpenGitWorkspaceStageAndCommit() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("commit-project");
        Path file = Files.writeString(repo.resolve("note.txt"), "one\n");
        commitAll(repo, "initial");
        Files.writeString(file, "two\n");

        try (var session = InstalledSwimDriver.start(tempDir, repo, "note.txt")) {
            session.waitForText("two", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Git:", UI_TIMEOUT);
            session.waitForText("note.txt", UI_TIMEOUT);

            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("s");
            session.waitForText("Staged note.txt", UI_TIMEOUT);

            session.sendLiteral("c");
            session.sendLiteral("update");
            session.sendEnter();
            session.waitForText("Committed changes", UI_TIMEOUT);

            session.sendLiteral("q");
            session.waitForText("two", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        try (var git = Git.open(repo.toFile())) {
            assertEquals("update", git.log().setMaxCount(1).call().iterator().next().getFullMessage());
            assertTrue(git.status().call().isClean());
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanDiscardUntrackedFileFromGitWorkspace() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("discard-project");
        Path current = Files.writeString(repo.resolve("current.txt"), "current\n");
        commitAll(repo, "initial");
        Path extra = Files.writeString(repo.resolve("extra.txt"), "extra changed\n");

        try (var session = InstalledSwimDriver.start(tempDir, repo, "current.txt")) {
            session.waitForText("current", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("extra.txt", UI_TIMEOUT);

            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("x");
            session.waitForText("Discarded extra.txt", UI_TIMEOUT);
            session.waitForText("Working tree clean", UI_TIMEOUT);

            session.sendLiteral("q");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertFalse(Files.exists(extra));
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanResolveConflictWithOursFromGitWorkspace() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("conflict-project");
        Path file = Files.writeString(repo.resolve("conflict.txt"), "base\n");
        commitAll(repo, "initial");

        try (var git = Git.open(repo.toFile())) {
            String mainBranch = git.getRepository().getBranch();
            git.branchCreate().setName("side").call();
            Files.writeString(file, "ours\n");
            git.add().addFilepattern("conflict.txt").call();
            git.commit().setMessage("ours")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            git.checkout().setName("side").call();
            Files.writeString(file, "theirs\n");
            git.add().addFilepattern("conflict.txt").call();
            git.commit().setMessage("theirs")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            git.checkout().setName(mainBranch).call();
            git.merge().include(git.getRepository().findRef("side")).call();
        }

        try (var session = InstalledSwimDriver.start(tempDir, repo, "conflict.txt")) {
            session.waitForText("<<<<<<<", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Conflicts", UI_TIMEOUT);
            session.waitForText("conflict.txt", UI_TIMEOUT);

            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("o");
            session.waitForText("Resolved conflict.txt", UI_TIMEOUT);

            session.sendLiteral("q");
            session.waitForText("ours", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("ours\n", Files.readString(file));
        try (var git = Git.open(repo.toFile())) {
            var status = git.status().call();
            assertTrue(status.getConflicting().isEmpty());
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanStageSingleHunkFromDiffView() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("hunk-project");
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

        try (var session = InstalledSwimDriver.start(tempDir, repo, "hunks.txt")) {
            session.waitForText("TWENTY", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("hunks.txt", UI_TIMEOUT);
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("d");
            session.waitForText("@@", UI_TIMEOUT);
            session.sendLiteral("s");
            session.waitForText("Staged current hunk", UI_TIMEOUT);
            session.sendLiteral("q");
            session.sendLiteral("q");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        try (var git = Git.open(repo.toFile())) {
            var status = git.status().call();
            assertTrue(status.getChanged().contains("hunks.txt") || status.getAdded().contains("hunks.txt"));
            assertTrue(status.getModified().contains("hunks.txt"));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanOpenResolverModeAndApplyBothSides() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("resolver-project");
        Path file = Files.writeString(repo.resolve("conflict.txt"), "base\n");
        commitAll(repo, "initial");

        try (var git = Git.open(repo.toFile())) {
            String mainBranch = git.getRepository().getBranch();
            git.branchCreate().setName("side").call();
            Files.writeString(file, "ours\n");
            git.add().addFilepattern("conflict.txt").call();
            git.commit().setMessage("ours")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            git.checkout().setName("side").call();
            Files.writeString(file, "theirs\n");
            git.add().addFilepattern("conflict.txt").call();
            git.commit().setMessage("theirs")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            git.checkout().setName(mainBranch).call();
            git.merge().include(git.getRepository().findRef("side")).call();
        }

        try (var session = InstalledSwimDriver.start(tempDir, repo, "conflict.txt")) {
            session.waitForText("<<<<<<<", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Conflicts", UI_TIMEOUT);
            session.waitForText("conflict.txt", UI_TIMEOUT);
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("m");
            session.waitForText("Git Merge Resolver:", UI_TIMEOUT);
            session.waitForText("OURS", UI_TIMEOUT);
            session.sendLiteral("b");
            session.sendLiteral("a");
            session.waitForText("Applied resolver result", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("ours", UI_TIMEOUT);
            session.waitForText("theirs", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                ours
                theirs
                
                """, Files.readString(file));
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanCreateInspectApplyAndDropStashFromGitWorkspace() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("stash-project");
        Path file = Files.writeString(repo.resolve("stash.txt"), "one\n");
        commitAll(repo, "initial");
        Files.writeString(file, "two\n");

        try (var session = InstalledSwimDriver.start(tempDir, repo, "stash.txt")) {
            session.waitForText("two", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Git:", UI_TIMEOUT);
            session.sendLiteral("z");
            session.waitForText("Created stash", UI_TIMEOUT);
            session.waitForText("Stashes (1)", UI_TIMEOUT);

            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("d");
            session.waitForText("stash@{0}", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("Stashes (1)", UI_TIMEOUT);

            session.sendLiteral("P");
            session.waitForText("Popped stash@{0}", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("two", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("two\n", Files.readString(file));
        try (var git = Git.open(repo.toFile())) {
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanCherryPickFromHistoryBrowser() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("history-pick-project");
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
            git.checkout().setName(main).call();
        }

        try (var session = InstalledSwimDriver.start(tempDir, repo, "note.txt")) {
            session.waitForText("base", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Git:", UI_TIMEOUT);
            session.sendLiteral("l");
            session.waitForText("Git History", UI_TIMEOUT);
            session.waitForText("picked change", UI_TIMEOUT);
            session.sendLiteral("y");
            session.waitForText("Cherry-picked", UI_TIMEOUT);
            session.sendLiteral("q");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("picked\n", Files.readString(file));
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanRunInteractiveRebaseFromHistoryBrowser() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-git-0.0.1-SNAPSHOT.jar");

        Path repo = initRepository("history-rebase-project");
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

        try (var session = InstalledSwimDriver.start(tempDir, repo, "second.txt")) {
            session.waitForText("second", STARTUP_TIMEOUT);

            session.runCommand("git");
            session.waitForText("Git:", UI_TIMEOUT);
            session.sendLiteral("l");
            session.waitForText("Git History", UI_TIMEOUT);
            session.waitForText("initial", UI_TIMEOUT);
            session.sendLiteral("j");
            session.sendLiteral("j");
            session.sendLiteral("R");
            session.waitForText("Git Interactive Rebase", UI_TIMEOUT);
            session.waitForText("pick", UI_TIMEOUT);
            session.sendLiteral("d");
            session.sendEnter();
            session.waitForText("Completed interactive rebase", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("second", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("second\n", Files.readString(file));
        assertFalse(Files.exists(repo.resolve("first.txt")));
        try (var git = Git.open(repo.toFile())) {
            var log = git.log().setMaxCount(3).call().iterator();
            assertEquals("second", log.next().getShortMessage());
            assertEquals("initial", log.next().getShortMessage());
        }
    }

    private Path initRepository(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo);
        Git.init().setDirectory(repo.toFile()).call().close();
        return repo;
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
}
