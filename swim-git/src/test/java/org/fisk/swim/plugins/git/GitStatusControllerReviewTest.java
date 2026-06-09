package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimTextSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStatusControllerReviewTest {
    @TempDir
    Path tempDir;

    @Test
    void pullReviewRendersFileSidebarAndColoredUnifiedDiff() throws Exception {
        GitStatusController controller = reviewController(tempDir);

        List<String> lines = controller.render(110, 12);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Files (2)")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("src/Foo.java")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Unified diff")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("+new")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("-old")));

        List<SwimPanelLine> rich = controller.renderRich(110, 12);
        assertTrue(rich.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.text().contains("+new") && "#173d22".equals(span.background())));
        assertTrue(rich.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.text().contains("-old") && "#4a2020".equals(span.background())));
    }

    @Test
    void pullReviewUsesBoxDrawingSeparators() throws Exception {
        GitStatusController controller = reviewController(tempDir);

        List<SwimPanelLine> rich = controller.renderRich(110, 12);

        assertTrue(rich.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> "│".equals(span.text())));

        controller.handleInput("3", 110, 12);
        List<String> splitLines = controller.render(110, 12);
        assertTrue(splitLines.stream().anyMatch(line -> line.contains(" │ ")));
        assertTrue(splitLines.stream().noneMatch(line -> line.contains(" | ")));
    }

    @Test
    void statusRendersColoredChangeCategories() throws Exception {
        Path repo = tempDir.resolve("status-repo");
        Files.createDirectories(repo);
        Path tracked = Files.writeString(repo.resolve("Tracked.java"), "old\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            Files.writeString(tracked, "new\n");
            Files.writeString(repo.resolve("Staged.java"), "staged\n");
            git.add().addFilepattern("Staged.java").call();
            Files.writeString(repo.resolve("Untracked.java"), "untracked\n");
        }
        GitStatusController controller = new GitStatusController();
        controller.syncToPath(tracked);

        List<SwimTextSpan> spans = controller.renderRich(120, 18).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(spans.stream().anyMatch(span -> span.text().contains("Repository has changes")
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("Staged")
                && "#7ee787".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "A".equals(span.text())
                && "#7ee787".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("Unstaged")
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "M".equals(span.text())
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("Untracked")
                && "#5ec4ff".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "?".equals(span.text())
                && "#5ec4ff".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().matches("[0-9a-f]{7,8}")
                && "#f7a94b".equals(span.foreground())));
    }

    @Test
    void commitHashesRenderOrangeInHistoryAndRebaseViews() throws Exception {
        GitStatusController controller = new GitStatusController();
        set(controller, "_mode", enumValue("Mode", "HISTORY"));
        set(controller, "_historyEntries", List.of(
                new GitHistoryGraphEntry("0123456789abcdef", "0123456", "Fix parser", "Alice",
                        "* 0123456 Fix parser [Alice]")));

        List<SwimTextSpan> historySpans = controller.renderRich(100, 8).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(historySpans.stream().anyMatch(span -> "0123456".equals(span.text())
                && "#f7a94b".equals(span.foreground())));

        set(controller, "_mode", enumValue("Mode", "REBASE"));
        set(controller, "_rebaseUpstreamLabel", "fedcba9 Upstream");
        set(controller, "_rebaseEntries", List.of(
                new GitRebaseEntry("abcdef1234567890", "abcdef1", "Reword summary", "Bob")));

        List<SwimTextSpan> rebaseSpans = controller.renderRich(100, 8).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(rebaseSpans.stream().anyMatch(span -> "fedcba9".equals(span.text())
                && "#f7a94b".equals(span.foreground())));
        assertTrue(rebaseSpans.stream().anyMatch(span -> "abcdef1".equals(span.text())
                && "#f7a94b".equals(span.foreground())));
    }

    @Test
    void pullRequestNumbersRenderTealInListAndReviewHeader() throws Exception {
        GitStatusController controller = new GitStatusController();
        set(controller, "_mode", enumValue("Mode", "PULL_REQUESTS"));
        set(controller, "_pullRepositories", List.of(new GitHubRepository("origin", "openjdk", "jdk")));
        set(controller, "_pullRequests", List.of(new GitHubPullRequest(42, "Improve review", "alice", "",
                "feature", "master", "head", "base", "", "2026-06-09T00:00:00Z", List.of("compiler"))));

        List<SwimTextSpan> listSpans = controller.renderRich(100, 8).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(listSpans.stream().anyMatch(span -> "#42".equals(span.text())
                && "#3ddbd9".equals(span.foreground())));
        assertTrue(listSpans.stream().anyMatch(span -> " compiler ".equals(span.text())
                && "#1b4f72".equals(span.background())));

        List<String> listLines = controller.render(100, 8);
        int titleLine = indexOfLineContaining(listLines, "#42 Improve review");
        assertTrue(titleLine >= 0);
        assertTrue(listLines.get(titleLine).contains("feature -> master"));
        assertTrue(!listLines.get(titleLine).contains("@alice"));
        assertTrue(titleLine + 1 < listLines.size());
        assertTrue(listLines.get(titleLine + 1).contains("@alice"));
        assertTrue(listLines.get(titleLine + 1).contains("compiler"));
        assertTrue(!listLines.get(titleLine + 1).contains("feature -> master"));

        GitStatusController reviewController = reviewController(tempDir);
        List<SwimTextSpan> reviewSpans = reviewController.renderRich(110, 12).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(reviewSpans.stream().anyMatch(span -> "#17".equals(span.text())
                && "#3ddbd9".equals(span.foreground())));
    }

    @Test
    void pullRequestFilterAndLabelsRenderWithFieldColors() throws Exception {
        GitStatusController controller = new GitStatusController();
        set(controller, "_mode", enumValue("Mode", "PULL_REQUESTS"));
        set(controller, "_pullRepositories", List.of(new GitHubRepository("origin", "openjdk", "jdk")));
        set(controller, "_pullFilterName", "Improve review");
        set(controller, "_pullFilterLabels", "compiler review");
        set(controller, "_pullFilterAuthor", "@alice");
        set(controller, "_pullActiveViewName", "mine");
        set(controller, "_pullSavedViews", List.of(
                new GitPullRequestSavedView("mine", "origin", "Improve review", "compiler", "@alice"),
                new GitPullRequestSavedView("docs", "origin", "", "docs", "")));
        set(controller, "_pullRequests", List.of(new GitHubPullRequest(42, "Improve review", "alice", "",
                "feature", "master", "head", "base", "", "2026-06-09T00:00:00Z",
                List.of("compiler", "review"))));

        List<SwimTextSpan> spans = controller.renderRich(130, 10).stream()
                .flatMap(line -> line.spans().stream())
                .toList();

        assertTrue(spans.stream().anyMatch(span -> span.text().equals("Filter Name: ")
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "Improve review".equals(span.text())
                && "#dce6ef".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("Filter Labels: ")
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "compiler review".equals(span.text())
                && "#a6e3a1".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("Filter Author: ")
                && "#ffb454".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> "@alice".equals(span.text())
                && "#d2a8ff".equals(span.foreground())));
        assertTrue(spans.stream().anyMatch(span -> " compiler ".equals(span.text())
                && "#1b4f72".equals(span.background())));
        assertTrue(spans.stream().anyMatch(span -> " review ".equals(span.text())
                && "#1b4f72".equals(span.background())));
        assertTrue(spans.stream().anyMatch(span -> "[mine@origin]".equals(span.text())
                && "#5ec4ff".equals(span.foreground())));
    }

    @Test
    void pullRequestViewsCanBeNamedPersistedAndApplied() throws Exception {
        Path repo = tempDir.resolve("filter-repo");
        Files.createDirectories(repo);
        Path file = Files.writeString(repo.resolve("Main.java"), "class Main {}\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }
        GitStatusController controller = new GitStatusController();
        controller.syncToPath(file);
        set(controller, "_mode", enumValue("Mode", "PULL_REQUESTS"));
        set(controller, "_pullRepositories", List.of(new GitHubRepository("origin", "openjdk", "jdk")));
        set(controller, "_allPullRequests", List.of(
                new GitHubPullRequest(12, "Fix compiler", "alice", "", "fix", "master",
                        "h1", "b1", "", "", List.of("compiler")),
                new GitHubPullRequest(42, "Docs", "bob", "", "docs", "master",
                        "h2", "b2", "", "", List.of("docs"))));
        set(controller, "_pullRequests", List.of(
                new GitHubPullRequest(12, "Fix compiler", "alice", "", "fix", "master",
                        "h1", "b1", "", "", List.of("compiler")),
                new GitHubPullRequest(42, "Docs", "bob", "", "docs", "master",
                        "h2", "b2", "", "", List.of("docs"))));
        set(controller, "_pullFilterName", "Fix");
        set(controller, "_pullFilterLabels", "compiler");
        set(controller, "_pullFilterAuthor", "@alice");

        controller.handleInput("S", 120, 12);
        for (char c : "mine".toCharArray()) {
            controller.handleInput(String.valueOf(c), 120, 12);
        }
        controller.handleInput("enter", 120, 12);
        controller.handleInput("0", 120, 12);
        controller.handleInput("]", 120, 12);

        assertEquals(List.of(new GitPullRequestSavedView("mine", "origin", "Fix", "compiler", "@alice")),
                GitProjectSwimConfig.loadPullRequestViews(repo));
        List<String> lines = controller.render(120, 12);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Fix compiler")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Docs")));
    }

    @Test
    void pullReviewNavigatesFilesModesAndHunks() throws Exception {
        GitStatusController controller = reviewController(tempDir);

        controller.handleInput("j", 110, 12);
        assertTrue(controller.render(110, 12).stream().anyMatch(line -> line.contains("src/Bar.java")));

        controller.handleInput("3", 110, 12);
        assertTrue(controller.render(110, 12).stream().anyMatch(line -> line.contains("Split diff")));

        controller.handleInput("4", 110, 12);
        assertTrue(controller.render(110, 12).stream().anyMatch(line -> line.contains("Please rename")));

        controller.handleInput("1", 110, 12);
        assertTrue(controller.render(110, 12).stream().anyMatch(line -> line.contains("Files changed: 2")));

        controller.handleInput("k", 110, 12);
        controller.handleInput("2", 110, 12);
        controller.handleInput("n", 110, 12);
        assertTrue(controller.render(110, 12).stream().anyMatch(line -> line.contains("@@ -10,2 +10,3 @@")));
    }

    @Test
    void pullReviewEnterOpensSelectedWorktreeFile() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Path file = Files.createDirectories(repo.resolve("src")).resolve("Foo.java");
        Files.writeString(file, "new\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }
        GitStatusController controller = reviewController(repo, file);

        SwimPanelResult result = controller.handleInput("enter", 110, 12);

        assertEquals(file.toAbsolutePath().normalize(), result.openFile().toAbsolutePath().normalize());
    }

    private static GitStatusController reviewController(Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Path file = Files.createDirectories(repo.resolve("src")).resolve("Foo.java");
        Files.writeString(file, "new\n");
        try (var git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
        }
        return reviewController(repo, file);
    }

    private static GitStatusController reviewController(Path repo, Path currentFile) throws Exception {
        GitStatusController controller = new GitStatusController();
        controller.syncToPath(currentFile);
        set(controller, "_mode", enumValue("Mode", "PULL_REVIEW"));
        set(controller, "_reviewRepository", new GitHubRepository("origin", "openjdk", "jdk"));
        set(controller, "_reviewPullRequest", new GitHubPullRequest(17, "Improve review", "alice",
                "feature", "master", "head", "base", "", "2026-06-09T00:00:00Z"));
        set(controller, "_reviewFiles", List.of(
                new GitHubPullRequestFile("src/Foo.java", "modified", 2, 1,
                        List.of("@@ -1,2 +1,3 @@", "-old", "+new", " context",
                                "@@ -10,2 +10,3 @@", "+tail")),
                new GitHubPullRequestFile("src/Bar.java", "added", 1, 0,
                        List.of("@@ -0,0 +1 @@", "+bar"))));
        set(controller, "_reviewComments", List.of(
                new GitHubReviewComment("src/Bar.java", 1, 1, "reviewer", "Please rename",
                        "@@ -0,0 +1 @@\n+bar", "2026-06-09T10:00:00Z", false),
                new GitHubReviewComment("", 0, 0, "lead", "General note",
                        "", "2026-06-09T11:00:00Z", true)));
        return controller;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object enumValue(String nestedName, String value) throws Exception {
        Class<?> type = Class.forName("org.fisk.swim.plugins.git.GitStatusController$" + nestedName);
        return Enum.valueOf((Class<Enum>) type, value);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = GitStatusController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
