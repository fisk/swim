package org.fisk.swim.plugins.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitProjectSwimConfigTest {
    @Test
    void savesPullRequestViewsInSwimFileWithoutDroppingOtherSettings(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve(".swim"), """
                -deps
                compile_commands = build/compile_commands.json
                git.pr.filter.old = author:old
                git.pr.view.stale.remote = origin
                git.pr.view.stale.filter.name = stale
                """);

        GitProjectSwimConfig.savePullRequestViews(repo, List.of(
                new GitPullRequestSavedView("mine", "jdk", "compiler", "bug hotspot", "@alice"),
                new GitPullRequestSavedView("review", "loom", "compiler crash", "review", "Bob")));

        String contents = Files.readString(repo.resolve(".swim"));
        assertTrue(contents.contains("-deps"));
        assertTrue(contents.contains("compile_commands = build/compile_commands.json"));
        assertTrue(contents.contains("git.pr.view.mine.remote = jdk"));
        assertTrue(contents.contains("git.pr.view.mine.filter.name = compiler"));
        assertTrue(contents.contains("git.pr.view.mine.filter.labels = bug hotspot"));
        assertTrue(contents.contains("git.pr.view.mine.filter.author = @alice"));
        assertTrue(contents.contains("git.pr.view.review.remote = loom"));
        assertTrue(contents.contains("git.pr.view.review.filter.name = compiler crash"));
        assertTrue(contents.contains("git.pr.view.review.filter.labels = review"));
        assertTrue(contents.contains("git.pr.view.review.filter.author = Bob"));
        assertTrue(!contents.contains("git.pr.filter.old"));
        assertTrue(!contents.contains("git.pr.view.stale"));

        assertEquals(List.of(
                new GitPullRequestSavedView("mine", "jdk", "compiler", "bug hotspot", "@alice"),
                new GitPullRequestSavedView("review", "loom", "compiler crash", "review", "Bob")),
                GitProjectSwimConfig.loadPullRequestViews(repo));
    }

    @Test
    void loadsLegacyPullRequestFiltersAsViews(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve(".swim"), """
                git.pr.filter.mine = title:compiler label:bug author:@alice
                """);

        assertEquals(List.of(new GitPullRequestSavedView("mine", "", "compiler", "bug", "@alice")),
                GitProjectSwimConfig.loadPullRequestViews(repo));
    }
}
