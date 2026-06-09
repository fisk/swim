package org.fisk.swim.plugins.git;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimKeyBindingHint;

final class GitKeyBindings {
    enum View {
        STATUS,
        ACTIONS,
        HISTORY,
        REBASE,
        DIFF,
        COMMIT,
        HUNK_EDIT,
        RESOLVER,
        PULL_REQUESTS,
        PULL_REVIEW
    }

    private static final List<Binding> BINDINGS = List.of(
            new Binding(View.STATUS, "j", "Navigation", "move down"),
            new Binding(View.STATUS, "k", "Navigation", "move up"),
            new Binding(View.STATUS, "<DOWN>", "Navigation", "move down"),
            new Binding(View.STATUS, "<UP>", "Navigation", "move up"),
            new Binding(View.STATUS, "<TAB>", "Sections", "fold section"),
            new Binding(View.STATUS, "?", "Actions", "show actions"),
            new Binding(View.STATUS, "l", "History", "history"),
            new Binding(View.STATUS, "p", "GitHub", "pull requests"),
            new Binding(View.STATUS, "s", "Changes", "stage"),
            new Binding(View.STATUS, "u", "Changes", "unstage"),
            new Binding(View.STATUS, "x", "Changes", "discard"),
            new Binding(View.STATUS, "d", "Changes", "diff"),
            new Binding(View.STATUS, "c", "Changes", "commit"),
            new Binding(View.STATUS, "r", "Repository", "refresh"),
            new Binding(View.STATUS, "S", "Changes", "stage all"),
            new Binding(View.STATUS, "U", "Changes", "unstage all"),
            new Binding(View.STATUS, "z", "Stash", "create stash"),
            new Binding(View.STATUS, "C", "Operation", "continue"),
            new Binding(View.STATUS, "A", "Operation", "abort"),
            new Binding(View.STATUS, "m", "Conflicts", "resolver"),
            new Binding(View.STATUS, "o", "Conflicts", "ours"),
            new Binding(View.STATUS, "t", "Conflicts", "theirs"),
            new Binding(View.STATUS, "b", "Conflicts", "both"),
            new Binding(View.STATUS, "<ENTER>", "Items", "open"),

            new Binding(View.ACTIONS, "q", "Actions", "close"),
            new Binding(View.ACTIONS, "<ESC>", "Actions", "close"),
            new Binding(View.ACTIONS, "l", "History", "history"),
            new Binding(View.ACTIONS, "p", "GitHub", "pull requests"),
            new Binding(View.ACTIONS, "s", "Changes", "stage"),
            new Binding(View.ACTIONS, "u", "Changes", "unstage"),
            new Binding(View.ACTIONS, "x", "Changes", "discard"),
            new Binding(View.ACTIONS, "d", "Changes", "inspect"),
            new Binding(View.ACTIONS, "c", "Changes", "commit"),
            new Binding(View.ACTIONS, "S", "Changes", "stage all"),
            new Binding(View.ACTIONS, "U", "Changes", "unstage all"),
            new Binding(View.ACTIONS, "z", "Stash", "create stash"),

            new Binding(View.HISTORY, "j", "Navigation", "move down"),
            new Binding(View.HISTORY, "k", "Navigation", "move up"),
            new Binding(View.HISTORY, "<ENTER>", "History", "inspect"),
            new Binding(View.HISTORY, "d", "History", "inspect"),
            new Binding(View.HISTORY, "y", "History", "cherry-pick"),
            new Binding(View.HISTORY, "R", "History", "rebase"),
            new Binding(View.HISTORY, "r", "History", "refresh"),
            new Binding(View.HISTORY, "<LEFT>", "History", "status"),

            new Binding(View.REBASE, "j", "Navigation", "move down"),
            new Binding(View.REBASE, "k", "Navigation", "move up"),
            new Binding(View.REBASE, "J", "Rebase", "move down"),
            new Binding(View.REBASE, "K", "Rebase", "move up"),
            new Binding(View.REBASE, "p", "Rebase", "pick"),
            new Binding(View.REBASE, "s", "Rebase", "squash"),
            new Binding(View.REBASE, "f", "Rebase", "fixup"),
            new Binding(View.REBASE, "d", "Rebase", "drop"),
            new Binding(View.REBASE, "<ENTER>", "Rebase", "apply"),
            new Binding(View.REBASE, "q", "Rebase", "cancel"),

            new Binding(View.DIFF, "j", "Diff", "scroll down"),
            new Binding(View.DIFF, "k", "Diff", "scroll up"),
            new Binding(View.DIFF, "n", "Hunks", "next hunk"),
            new Binding(View.DIFF, "p", "Hunks", "previous hunk"),
            new Binding(View.DIFF, "s", "Hunks", "stage"),
            new Binding(View.DIFF, "u", "Hunks", "unstage"),
            new Binding(View.DIFF, "x", "Hunks", "discard"),
            new Binding(View.DIFF, "e", "Hunks", "edit patch"),
            new Binding(View.DIFF, "<ENTER>", "Diff", "open file"),
            new Binding(View.DIFF, "<LEFT>", "Diff", "status"),

            new Binding(View.COMMIT, "<ENTER>", "Commit", "commit"),
            new Binding(View.COMMIT, "<BACKSPACE>", "Commit", "delete"),
            new Binding(View.COMMIT, "<CHAR>", "Commit", "type message"),
            new Binding(View.COMMIT, "<ESC>", "Commit", "cancel"),

            new Binding(View.HUNK_EDIT, "<CTRL>-s", "Patch", "apply"),
            new Binding(View.HUNK_EDIT, "<CTRL>-g", "Patch", "cancel"),
            new Binding(View.HUNK_EDIT, "<UP>", "Patch", "move up"),
            new Binding(View.HUNK_EDIT, "<DOWN>", "Patch", "move down"),
            new Binding(View.HUNK_EDIT, "<ENTER>", "Patch", "newline"),

            new Binding(View.RESOLVER, "n", "Conflicts", "next block"),
            new Binding(View.RESOLVER, "p", "Conflicts", "previous block"),
            new Binding(View.RESOLVER, "o", "Conflicts", "ours"),
            new Binding(View.RESOLVER, "t", "Conflicts", "theirs"),
            new Binding(View.RESOLVER, "b", "Conflicts", "both"),
            new Binding(View.RESOLVER, "a", "Conflicts", "apply"),
            new Binding(View.RESOLVER, "<LEFT>", "Conflicts", "status"),

            new Binding(View.PULL_REQUESTS, "j", "Pull Requests", "move down"),
            new Binding(View.PULL_REQUESTS, "k", "Pull Requests", "move up"),
            new Binding(View.PULL_REQUESTS, "<RIGHT>", "Repositories", "next repo"),
            new Binding(View.PULL_REQUESTS, "<LEFT>", "Repositories", "previous repo"),
            new Binding(View.PULL_REQUESTS, "/", "Pull Requests", "filter"),
            new Binding(View.PULL_REQUESTS, "r", "Pull Requests", "refresh"),
            new Binding(View.PULL_REQUESTS, "f", "Pull Requests", "fetch"),
            new Binding(View.PULL_REQUESTS, "<ENTER>", "Pull Requests", "review"),
            new Binding(View.PULL_REQUESTS, "q", "Pull Requests", "status"),
            new Binding(View.PULL_REQUESTS, "<ESC>", "Pull Requests", "status"),

            new Binding(View.PULL_REVIEW, "j", "Review", "next file"),
            new Binding(View.PULL_REVIEW, "k", "Review", "previous file"),
            new Binding(View.PULL_REVIEW, "<DOWN>", "Review", "scroll diff down"),
            new Binding(View.PULL_REVIEW, "<UP>", "Review", "scroll diff up"),
            new Binding(View.PULL_REVIEW, "f", "Review", "fetch PR"),
            new Binding(View.PULL_REVIEW, "r", "Review", "refresh files"),
            new Binding(View.PULL_REVIEW, "<ENTER>", "Review", "open file"),
            new Binding(View.PULL_REVIEW, "<LEFT>", "Review", "pull requests"));

    private GitKeyBindings() {
    }

    static List<SwimKeyBindingHint> hints(View view) {
        return BINDINGS.stream()
                .filter(binding -> binding.view() == view)
                .map(binding -> new SwimKeyBindingHint(binding.key(), binding.group(), binding.summary()))
                .toList();
    }

    static String helpLine(View view) {
        var parts = new ArrayList<String>();
        for (var binding : BINDINGS) {
            if (binding.view() == view) {
                parts.add(displayKey(binding.key()) + " " + binding.summary());
            }
        }
        return String.join("  ", parts);
    }

    private static String displayKey(String key) {
        return switch (key) {
        case "<ENTER>" -> "Enter";
        case "<BACKSPACE>" -> "Backspace";
        case "<ESC>" -> "Esc";
        case "<TAB>" -> "Tab";
        case "<LEFT>" -> "Left";
        case "<RIGHT>" -> "Right";
        case "<UP>" -> "Up";
        case "<DOWN>" -> "Down";
        case "<CTRL>-s" -> "C-s";
        case "<CTRL>-g" -> "C-g";
        case "<CHAR>" -> "type";
        default -> key;
        };
    }

    private record Binding(View view, String key, String group, String summary) {
    }
}
