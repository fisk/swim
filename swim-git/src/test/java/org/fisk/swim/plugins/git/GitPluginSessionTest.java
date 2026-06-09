package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitPluginSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void preloadRegistersHelpPagesWithoutActivatingPlugin() {
        SwimPluginPreloadRegistry.clearForTests();
        SwimHelpRegistry.clearForTests();
        SwimPluginKeyBindingRegistry.clearForTests();
        try {
            GitPlugin plugin = new GitPlugin();
            plugin.preload(() -> GitPluginSupport.PLUGIN_ID);

            List<String> ids = SwimHelpRegistry.chapters().stream()
                    .map(chapter -> chapter.id())
                    .toList();
            assertTrue(ids.contains("git-workflow"));
            assertTrue(ids.contains("git-swim-config"));
            assertTrue(SwimHelpRegistry.chapters().stream()
                    .anyMatch(chapter -> chapter.summary().contains(".swim config")));
            assertTrue(SwimHelpRegistry.chapters().stream()
                    .flatMap(chapter -> chapter.sections().stream())
                    .flatMap(section -> section.paragraphs().stream())
                    .anyMatch(paragraph -> paragraph.contains("git.pr.view")));
            assertTrue(SwimPluginKeyBindingRegistry.listBindings().stream()
                    .anyMatch(binding -> "<SPACE> g".equals(binding.key())
                            && "swim-git".equals(binding.pluginId())
                            && "git".equals(binding.command())));
            assertTrue(GitPluginSupport.getSession().isEmpty());
        } finally {
            GitPluginSupport.shutdown();
            SwimPluginPreloadRegistry.clearForTests();
            SwimHelpRegistry.clearForTests();
            SwimPluginKeyBindingRegistry.clearForTests();
        }
    }

    @Test
    void rendersStatusAndSupportsStageDiffAndCommitInput() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Path file = Files.writeString(repo.resolve("note.txt"), "one\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        Files.writeString(file, "two\n");
        GitPluginSession session = new GitPluginSession(new TestPluginContext(repo, file));

        List<String> lines = session.render(80, 12);
        assertTrue(lines.get(0).contains("Git:"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Unstaged")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("note.txt")));

        session.handleInput("j", 80, 12);
        session.handleInput("j", 80, 12);
        session.handleInput("s", 80, 12);
        lines = session.render(80, 12);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Staged")));

        session.handleInput("k", 80, 12);
        session.handleInput("d", 80, 12);
        lines = session.render(80, 12);
        assertTrue(lines.stream().anyMatch(line -> line.contains("---")));

        session.handleInput("left", 80, 12);
        session.handleInput("c", 80, 12);
        session.handleInput("u", 80, 12);
        session.handleInput("p", 80, 12);
        session.handleInput("d", 80, 12);
        session.handleInput("a", 80, 12);
        session.handleInput("t", 80, 12);
        session.handleInput("e", 80, 12);
        session.handleInput("enter", 80, 12);

        try (var git = Git.open(repo.toFile())) {
            assertEquals("update", git.log().setMaxCount(1).call().iterator().next().getFullMessage());
        }
    }

    @Test
    void syncWithinSameRepositoryKeepsSnapshotUntilManualRefresh() throws Exception {
        Path repo = tempDir.resolve("cached-repo");
        Files.createDirectories(repo);
        Path first = Files.writeString(repo.resolve("first.txt"), "first\n");
        Path second = Files.writeString(repo.resolve("second.txt"), "second\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        GitPluginSession session = new GitPluginSession(new TestPluginContext(repo, first));
        assertTrue(session.render(80, 12).stream().noneMatch(line -> line.contains("second.txt")));

        Files.writeString(second, "updated\n");
        session.syncToPath(second);
        assertTrue(session.render(80, 12).stream().noneMatch(line -> line.contains("second.txt")));

        session.handleInput("r", 80, 12);
        assertTrue(session.render(80, 12).stream().anyMatch(line -> line.contains("second.txt")));
    }

    @Test
    void syncToDifferentRepositoryRefreshesImmediately() throws Exception {
        Path firstRepo = tempDir.resolve("first-repo");
        Files.createDirectories(firstRepo);
        Path firstFile = Files.writeString(firstRepo.resolve("first.txt"), "first\n");
        try (var git = Git.init().setDirectory(firstRepo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        Path secondRepo = tempDir.resolve("second-repo");
        Files.createDirectories(secondRepo);
        Path secondFile = Files.writeString(secondRepo.resolve("second.txt"), "second\n");
        try (var git = Git.init().setDirectory(secondRepo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        GitPluginSession session = new GitPluginSession(new TestPluginContext(firstRepo, firstFile));

        Files.writeString(secondFile, "updated\n");
        session.syncToPath(secondFile);
        List<String> lines = session.render(80, 12);
        assertTrue(lines.getFirst().contains("second-repo"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("second.txt")));
    }

    @Test
    void exposesProgrammaticKeyHintsForCurrentGitView() throws Exception {
        Path repo = tempDir.resolve("hint-repo");
        Files.createDirectories(repo);
        Path file = Files.writeString(repo.resolve("note.txt"), "one\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }

        GitPluginSession session = new GitPluginSession(new TestPluginContext(repo, file));

        assertTrue(session.keyBindingHints().stream()
                .anyMatch(hint -> "p".equals(hint.key()) && hint.summary().contains("pull requests")));
        assertTrue(session.keyBindingHints().stream()
                .anyMatch(hint -> "r".equals(hint.key()) && hint.summary().contains("refresh")));
    }

    private record TestPluginContext(Path initialPath, Path currentPath) implements SwimPluginContext {
        @Override
        public Path getInitialPath() {
            return initialPath;
        }

        @Override
        public Path getCurrentPath() {
            return currentPath;
        }

        @Override
        public SwimHost getHost() {
            return new SwimHost() {
                @Override
                public void requestReload(Path path) {
                }

                @Override
                public void requestRebuildAndReload(Path path) {
                }

                @Override
                public void requestLoadPlugin(String pluginId, Path path) {
                }

                @Override
                public void requestExit() {
                }

                @Override
                public Path getBuildRoot() {
                    return initialPath;
                }
            };
        }
    }
}
