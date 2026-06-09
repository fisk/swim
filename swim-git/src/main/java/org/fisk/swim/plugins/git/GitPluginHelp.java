package org.fisk.swim.plugins.git;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class GitPluginHelp {
    private GitPluginHelp() {
    }

    static List<SwimHelpChapter> chapters() {
        return List.of(workflow(), config());
    }

    private static SwimHelpChapter workflow() {
        return new SwimHelpChapter("git-workflow", "Git Workflow",
                "How to use SWIM's Git workspace for status, history, pull requests, and review.",
                List.of(
                        section("Opening the Git workspace",
                                "Use :git from the command line, or Space-g with the default leader key, to load and open the Git plugin. The status view is the home screen: it groups staged, unstaged, untracked, stash, and conflict items. Use j/k to move, Tab to fold a section, r to refresh, Enter to open the selected file, and ? to see the action menu.",
                                ":git\n<Space> g"),
                        section("Working with local changes",
                                "The status view keeps the common staging loop close to the keyboard. Press s to stage the selected file or hunk, u to unstage, d to inspect a diff, and c to commit staged changes. In a diff, n and p move between hunks, s/u stage or unstage a hunk, x discards a hunk, and e opens the editable patch view for a more precise change.",
                                "s\nd\nn\ns\n<LEFT>\nc"),
                        section("History, rebase, and conflicts",
                                "Press l from status to browse history. Enter or d inspects a commit, y cherry-picks it, and R opens the interactive rebase view. In rebase, J/K move entries and p/s/f/d choose pick, squash, fixup, or drop before Enter applies the todo list. Conflict files can be resolved from status with o for ours, t for theirs, b for both, or m for the block resolver.",
                                "l\nR\nJ\ns\n<ENTER>"),
                        section("Browsing GitHub pull requests",
                                "Press p from status to open the pull request browser. SWIM discovers GitHub repositories from git remotes, including related remotes in large multi-repository checkouts. Left and Right choose the remote, / edits the title filter, L edits labels, A edits author, and r refreshes the list. If the author filter contains @, it matches the GitHub user name; otherwise it matches the display name.",
                                "p\n/\ncompiler\n<ENTER>\nL\nbug hotspot\n<ENTER>"),
                        section("Reviewing a pull request",
                                "Select a pull request and press Enter to open the dedicated review view. The left side lists changed files, while the right side shows summary, unified diff, split diff, or review comments. Use j/k for files, n/p for hunks, 1/2/3/4 or m for display modes, f to fetch the PR branch locally, and Enter or o to open the selected file for suggesting changes.",
                                "<ENTER>\n2\nn\n3\nf\no")));
    }

    private static SwimHelpChapter config() {
        return new SwimHelpChapter("git-swim-config", "Git .swim Configuration",
                "How the Git plugin persists pull request saved views in project-local .swim config.",
                List.of(
                        section("Where Git stores saved views",
                                "Saved pull request views are project state and live in the repository-local .swim config, similar to other SWIM project settings. If .swim is a directory, Git stores views in .swim/git-pr-views. If .swim is a file, the view keys are written into that file. This keeps PR filters with the repository instead of tying them to one editor session.",
                                ".swim\ngit.pr.view.mine.remote = origin"),
                        section("Saved view fields",
                                "A saved view contains a display name, an optional remote, and the three pull request filters shown in the browser: Filter Name, Filter Labels, and Filter Author. The remote pins the view to one discovered GitHub remote. The labels value is space-separated, so a view can require labels such as bug and hotspot together. Author values with @ match user names, while plain values match author display names.",
                                "git.pr.view.mine.remote = jdk\ngit.pr.view.mine.filter.name = compiler\ngit.pr.view.mine.filter.labels = bug hotspot\ngit.pr.view.mine.filter.author = @alice"),
                        section("Editing and migrating config",
                                "Use S in the pull request browser to save the current remote and filters as a named view, [ and ] to cycle saved views, 0 to clear the active view, and D to delete one. SWIM still reads older git.pr.filter.<name> and git.pr.view.<name>.filter keys, then saves new data back using the structured git.pr.view.<name>.filter.name, filter.labels, and filter.author fields.",
                                "git.pr.filter.mine = compiler label:bug author:@alice\ngit.pr.view.mine.filter.name = compiler")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
