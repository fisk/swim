package org.fisk.swim.session;

import java.util.List;

public record SwimServerSession(
        String name,
        boolean current,
        boolean attached,
        boolean running,
        long pid,
        List<String> launchArgs) {
    public SwimServerSession {
        launchArgs = List.copyOf(launchArgs == null ? List.of() : launchArgs);
    }
}
