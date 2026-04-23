package org.fisk.swim.api;

import java.nio.file.Path;

public interface SwimHost {
    void requestReload(Path path);
    void requestRebuildAndReload(Path path);
    void requestExit();
    Path getBuildRoot();
}
