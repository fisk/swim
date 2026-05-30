package org.fisk.swim.debug;

import java.nio.file.Path;

public record DebugSourceLocation(Path path, int line, int column, String function) {
}
