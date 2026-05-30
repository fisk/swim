package org.fisk.swim.plugins.git;

record GitCommitEntry(String objectId, String summary, String author) {
    String shortId() {
        return objectId.length() <= 8 ? objectId : objectId.substring(0, 8);
    }

    String displayLabel() {
        return shortId() + " " + summary + (author == null || author.isBlank() ? "" : " [" + author + "]");
    }
}
