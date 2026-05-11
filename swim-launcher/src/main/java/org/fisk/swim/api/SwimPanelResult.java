package org.fisk.swim.api;

import java.nio.file.Path;

public record SwimPanelResult(boolean handled, Path openFile, String message) {
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
}
