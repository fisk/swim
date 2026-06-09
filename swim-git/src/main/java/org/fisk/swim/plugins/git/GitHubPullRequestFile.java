package org.fisk.swim.plugins.git;

import java.util.List;

record GitHubPullRequestFile(
        String path,
        String status,
        int additions,
        int deletions,
        List<String> patchLines) {
    GitHubPullRequestFile {
        patchLines = patchLines == null ? List.of() : List.copyOf(patchLines);
    }

    String displayLabel() {
        return path + "  +" + additions + " -" + deletions + "  " + status;
    }
}
