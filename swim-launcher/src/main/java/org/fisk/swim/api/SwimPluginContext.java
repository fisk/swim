package org.fisk.swim.api;

import java.nio.file.Path;

public interface SwimPluginContext {
    SwimHost getHost();
    Path getInitialPath();
    Path getCurrentPath();
}
