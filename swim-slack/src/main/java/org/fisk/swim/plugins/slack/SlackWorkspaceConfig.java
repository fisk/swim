package org.fisk.swim.plugins.slack;

public record SlackWorkspaceConfig(
        String id,
        String label,
        String token,
        String tokenEnv,
        String tokenCommand) {
}
