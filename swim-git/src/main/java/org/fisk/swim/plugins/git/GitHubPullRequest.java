package org.fisk.swim.plugins.git;

record GitHubPullRequest(
        int number,
        String title,
        String author,
        String headRef,
        String baseRef,
        String headSha,
        String baseSha,
        String htmlUrl,
        String updatedAt) {
    String displayLabel() {
        return "#" + number + " " + title + "  " + headRef + " -> " + baseRef + "  @" + author;
    }
}
