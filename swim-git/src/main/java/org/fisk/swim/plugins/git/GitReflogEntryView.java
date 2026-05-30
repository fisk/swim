package org.fisk.swim.plugins.git;

record GitReflogEntryView(int index, String newId, String comment) {
    String shortId() {
        return newId.length() <= 8 ? newId : newId.substring(0, 8);
    }

    String displayLabel() {
        return "HEAD@{" + index + "} " + shortId() + " " + comment;
    }
}
