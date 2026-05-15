package org.fisk.swim.mail;

public record MailSendResult(boolean success, String message) {
    public static MailSendResult success(String message) {
        return new MailSendResult(true, message == null ? "" : message);
    }

    public static MailSendResult failure(String message) {
        return new MailSendResult(false, message == null ? "" : message);
    }
}
