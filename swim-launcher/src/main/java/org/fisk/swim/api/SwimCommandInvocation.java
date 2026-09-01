package org.fisk.swim.api;

import java.nio.file.Path;

public record SwimCommandInvocation(String command, String argument, Path currentPath, Path workspaceRoot) {
}
