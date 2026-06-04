package org.fisk.swim.slack;

public record SlackSendResult(
        boolean success,
        String message) {
    public static SlackSendResult success(String message) {
        return new SlackSendResult(true, message);
    }

    public static SlackSendResult failure(String message) {
        return new SlackSendResult(false, message);
    }
}
