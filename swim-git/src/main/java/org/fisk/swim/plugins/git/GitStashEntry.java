package org.fisk.swim.plugins.git;

record GitStashEntry(int index, String refName, String objectId, String summary) {
    String displayLabel() {
        return refName + " " + summary;
    }
}
