package org.fisk.swim.plugins.git;

import java.util.List;

record GitHubPullRequest(
        int number,
        String title,
        String author,
        String authorName,
        String headRef,
        String baseRef,
        String headSha,
        String baseSha,
        String htmlUrl,
        String updatedAt,
        List<String> labels) {
    GitHubPullRequest {
        title = title == null ? "" : title;
        author = author == null ? "" : author;
        authorName = authorName == null ? "" : authorName;
        headRef = headRef == null ? "" : headRef;
        baseRef = baseRef == null ? "" : baseRef;
        headSha = headSha == null ? "" : headSha;
        baseSha = baseSha == null ? "" : baseSha;
        htmlUrl = htmlUrl == null ? "" : htmlUrl;
        updatedAt = updatedAt == null ? "" : updatedAt;
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    GitHubPullRequest(
            int number,
            String title,
            String author,
            String headRef,
            String baseRef,
            String headSha,
            String baseSha,
            String htmlUrl,
            String updatedAt) {
        this(number, title, author, "", headRef, baseRef, headSha, baseSha, htmlUrl, updatedAt, List.of());
    }

    String displayLabel() {
        String labelText = labels.isEmpty() ? "" : "  [" + String.join(", ", labels) + "]";
        return "#" + number + " " + title + "  " + headRef + " -> " + baseRef + "  @" + author + labelText;
    }
}
