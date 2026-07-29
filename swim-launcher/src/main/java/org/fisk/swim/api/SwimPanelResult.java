package org.fisk.swim.api;

import java.nio.file.Path;

public record SwimPanelResult(boolean handled, Path openFile, String message, SwimMergeResolution mergeResolution) {
    public SwimPanelResult(boolean handled, Path openFile, String message) {
        this(handled, openFile, message, null);
    }
    public static SwimPanelResult ignored() {
        return new SwimPanelResult(false, null, null);
    }

    public static SwimPanelResult success() {
        return new SwimPanelResult(true, null, null);
    }

    public static SwimPanelResult success(Path openFile) {
        return new SwimPanelResult(true, openFile, null);
    }

    public static SwimPanelResult successMessage(String message) {
        return new SwimPanelResult(true, null, message);
    }

    public static SwimPanelResult openMergeResolution(SwimMergeResolution mergeResolution) {
        return new SwimPanelResult(true, null, null, mergeResolution);
    }
}
