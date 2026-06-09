package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubPullRequestServiceTest {
    @Test
    void parsesHttpsAndSshGitHubRemotes() {
        assertEquals(new GitHubRepository("origin", "openjdk", "jdk"),
                GitHubPullRequestService.parseRepository("origin", "https://github.com/openjdk/jdk.git"));
        assertEquals(new GitHubRepository("loom", "openjdk", "loom"),
                GitHubPullRequestService.parseRepository("loom", "git@github.com:openjdk/loom.git"));
    }

    @Test
    void ignoresNonGitHubRemotes() {
        assertNull(GitHubPullRequestService.parseRepository("origin", "https://example.com/openjdk/jdk.git"));
    }

    @Test
    void listsAllGitHubRemotesFromRepositoryConfig(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo);
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "jdk", "url", "https://github.com/openjdk/jdk.git");
            config.setString("remote", "loom", "url", "git@github.com:openjdk/loom.git");
            config.setString("remote", "local", "url", "file:///tmp/openjdk");
            config.save();
        }

        List<GitHubRepository> repositories = GitHubPullRequestService.repositories(repo);

        assertEquals(2, repositories.size());
        assertTrue(repositories.contains(new GitHubRepository("jdk", "openjdk", "jdk")));
        assertTrue(repositories.contains(new GitHubRepository("loom", "openjdk", "loom")));
    }

    @Test
    void filtersPullRequestsByTitleAuthorNumberAndBranch() {
        var pullRequests = List.of(
                new GitHubPullRequest(12, "Fix compiler crash", "alice", "bugfix", "master",
                        "h1", "b1", "", ""),
                new GitHubPullRequest(42, "Update docs", "bob", "docs", "master",
                        "h2", "b2", "", ""));

        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "compiler"));
        assertEquals(List.of(pullRequests.getLast()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "#42"));
        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "alice"));
    }
}
