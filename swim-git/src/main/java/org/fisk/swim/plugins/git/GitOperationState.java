package org.fisk.swim.plugins.git;

record GitOperationState(String description, boolean rebaseInProgress, boolean cherryPickInProgress) {
    static GitOperationState idle() {
        return new GitOperationState("", false, false);
    }

    boolean active() {
        return rebaseInProgress || cherryPickInProgress;
    }
}
