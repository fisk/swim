package org.fisk.swim.debug;

import java.nio.file.Path;
import java.util.List;

public record DebugLaunchRequest(Path currentPath, List<String> args) {
}
