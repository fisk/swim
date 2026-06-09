package org.fisk.swim.plugins.git;

record GitPullRequestSavedView(String name, String remoteName, GitPullRequestFilters filters) {
    GitPullRequestSavedView {
        name = name == null ? "" : name.strip();
        remoteName = remoteName == null ? "" : remoteName.strip();
        filters = filters == null ? GitPullRequestFilters.empty() : filters;
    }

    GitPullRequestSavedView(String name, String remoteName, String filterName, String filterLabels, String filterAuthor) {
        this(name, remoteName, new GitPullRequestFilters(filterName, filterLabels, filterAuthor));
    }

    boolean isUsable() {
        return !name.isBlank() && (!remoteName.isBlank() || !filters.isBlank());
    }

    boolean pinsRemote() {
        return !remoteName.isBlank();
    }

    String displayLabel() {
        return pinsRemote() ? name + "@" + remoteName : name;
    }
}
