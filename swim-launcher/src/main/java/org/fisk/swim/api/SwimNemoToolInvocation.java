package org.fisk.swim.api;

import java.nio.file.Path;

public record SwimNemoToolInvocation(
        String exposedName,
        String pluginId,
        String toolName,
        String argumentsJson,
        Path currentPath,
        Path workspaceRoot) {
}
