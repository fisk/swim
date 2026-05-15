package org.fisk.swim.plugins.email;

final class DefaultMailSyncAdapterFactory implements MailSyncAdapterFactory {
    @Override
    public MailSyncAdapter create(EmailAccountConfig account) {
        return switch (account.normalizedProtocol()) {
        case "IMAP", "IMAPS" -> new ImapMailSyncAdapter();
        case "POP", "POP3", "POP3S" -> new Pop3MailSyncAdapter();
        case "EXCHANGE", "EWS" -> new ExchangeEwsMailSyncAdapter();
        default -> unsupported("Unsupported protocol '" + account.normalizedProtocol() + "'");
        };
    }

    private static MailSyncAdapter unsupported(String message) {
        return account -> MailSyncBatch.failure(message);
    }
}
