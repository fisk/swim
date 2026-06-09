package org.fisk.swim.plugins.git;

record GitHubReviewComment(
        String path,
        int line,
        int originalLine,
        String author,
        String body,
        String diffHunk,
        String createdAt,
        boolean issueComment) {
    String locationLabel() {
        if (issueComment) {
            return "conversation";
        }
        int displayLine = line > 0 ? line : originalLine;
        return path == null || path.isBlank()
                ? "review"
                : path + (displayLine > 0 ? ":" + displayLine : "");
    }
}
