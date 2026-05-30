package org.fisk.swim.plugins.git;

enum GitSection {
    STAGED("Staged"),
    UNSTAGED("Unstaged"),
    UNTRACKED("Untracked"),
    CONFLICTS("Conflicts"),
    STASHES("Stashes"),
    COMMITS("Recent Commits"),
    REFLOG("Reflog");

    private final String _title;

    GitSection(String title) {
        _title = title;
    }

    String title() {
        return _title;
    }
}
