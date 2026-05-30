package org.fisk.swim.plugins.git;

record GitDiffHunk(
        String header,
        String patchText,
        int displayStartLine,
        int displayEndLine) {
}
