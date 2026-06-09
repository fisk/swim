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
    void filtersPullRequestsByTitleAuthorNumberBranchAndLabels() {
        var pullRequests = List.of(
                new GitHubPullRequest(12, "Fix compiler crash", "alice", "Alice Andersson", "bugfix", "master",
                        "h1", "b1", "", "", List.of("bug", "compiler")),
                new GitHubPullRequest(42, "Update docs", "bob", "Bob Berg", "docs", "master",
                        "h2", "b2", "", "", List.of("docs")));

        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "compiler"));
        assertEquals(List.of(pullRequests.getLast()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "#42"));
        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "author:alice"));
        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "author:andersson"));
        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "title:\"compiler crash\" label:bug"));
        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "-label:docs"));
        assertEquals(List.of(pullRequests.getLast()),
                GitHubPullRequestService.filterPullRequests(pullRequests, "branch:docs"));
    }

    @Test
    void fieldFiltersSearchAuthorUsernameOnlyWhenAtSignIsUsed() {
        var pullRequests = List.of(
                new GitHubPullRequest(12, "Fix compiler crash", "alice", "Alice Andersson", "bugfix", "master",
                        "h1", "b1", "", "", List.of("bug", "compiler")),
                new GitHubPullRequest(42, "Update docs", "openjdk-bot", "Alice Robot", "docs", "master",
                        "h2", "b2", "", "", List.of("docs")));

        assertEquals(List.of(pullRequests.getFirst()),
                GitHubPullRequestService.filterPullRequests(pullRequests,
                        new GitPullRequestFilters("compiler", "bug", "@alice")));
        assertEquals(List.of(pullRequests.getLast()),
                GitHubPullRequestService.filterPullRequests(pullRequests,
                        new GitPullRequestFilters("docs", "docs", "@openjdk-bot")));
        assertEquals(pullRequests,
                GitHubPullRequestService.filterPullRequests(pullRequests,
                        new GitPullRequestFilters("", "", "Alice")));
        assertEquals(List.of(),
                GitHubPullRequestService.filterPullRequests(pullRequests,
                        new GitPullRequestFilters("", "", "@Andersson")));
    }

    @Test
    void parsesPullRequestFilesAndReviewComments() {
        List<GitHubPullRequest> pullRequests = GitHubPullRequestService.parsePullRequests("""
                [
                  {"number":12,"title":"Fix compiler crash","html_url":"","updated_at":"2026-06-09T10:00:00Z",
                   "user":{"login":"alice","name":"Alice Andersson"},
                   "head":{"ref":"bugfix","sha":"h1"},"base":{"ref":"master","sha":"b1"},
                   "labels":[{"name":"bug"},{"name":"compiler"}]}
                ]
                """);
        assertEquals(List.of("bug", "compiler"), pullRequests.getFirst().labels());
        assertEquals("Alice Andersson", pullRequests.getFirst().authorName());

        List<GitHubPullRequestFile> files = GitHubPullRequestService.parsePullRequestFiles("""
                [
                  {"filename":"src/Foo.java","status":"modified","additions":2,"deletions":1,
                   "patch":"@@ -1,2 +1,3 @@\\n-old\\n+new\\n context"}
                ]
                """);
        assertEquals(1, files.size());
        assertEquals("src/Foo.java", files.getFirst().path());
        assertEquals(List.of("@@ -1,2 +1,3 @@", "-old", "+new", " context"), files.getFirst().patchLines());

        List<GitHubReviewComment> reviewComments = GitHubPullRequestService.parseReviewComments("""
                [
                  {"path":"src/Foo.java","line":12,"original_line":10,"body":"Please rename",
                   "diff_hunk":"@@ -1 +1 @@\\n-old\\n+new","created_at":"2026-06-09T10:00:00Z",
                   "user":{"login":"reviewer"}}
                ]
                """);
        assertEquals(1, reviewComments.size());
        assertEquals("src/Foo.java:12", reviewComments.getFirst().locationLabel());
        assertEquals("Please rename", reviewComments.getFirst().body());

        List<GitHubReviewComment> issueComments = GitHubPullRequestService.parseIssueComments("""
                [
                  {"body":"General note","created_at":"2026-06-09T11:00:00Z","user":{"login":"lead"}}
                ]
                """);
        assertEquals(1, issueComments.size());
        assertTrue(issueComments.getFirst().issueComment());
        assertEquals("conversation", issueComments.getFirst().locationLabel());
    }
}
