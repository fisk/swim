package org.fisk.swim.plugins.git;

record GitHistoryGraphEntry(String objectId, String shortId, String summary, String author, String graphLine) {
    String displayLabel() {
        return graphLine;
    }
}
