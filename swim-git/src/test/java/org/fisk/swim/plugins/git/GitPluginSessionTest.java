package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitPluginSessionTest {
    @TempDir
    Path tempDir;

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
