package org.fisk.swim.slack;

public record SlackWorkspaceSummary(
        String id,
        String label,
        String teamName,
        String userDisplayName,
        boolean connected,
        String statusMessage) {
}
