package org.fisk.swim.slack;

public record SlackMessageEntry(
        String ts,
        String userDisplayName,
        String sentAt,
        String text) {
}
