package org.fisk.swim.mail;

public record MailThreadFilter(Kind kind, String value) {
    public enum Kind {
        ALL,
        UNSORTED,
        ACCOUNT,
        TAG
    }

    public MailThreadFilter {
        kind = kind == null ? Kind.ALL : kind;
        value = value == null ? "" : value;
    }

    public static MailThreadFilter all() {
        return new MailThreadFilter(Kind.ALL, "");
    }

    public static MailThreadFilter unsorted() {
        return new MailThreadFilter(Kind.UNSORTED, "unsorted");
    }

    public static MailThreadFilter account(String accountId) {
        return new MailThreadFilter(Kind.ACCOUNT, accountId);
    }

    public static MailThreadFilter tag(String tag) {
        return new MailThreadFilter(Kind.TAG, tag);
    }

    public boolean matches(MailThreadSummary thread) {
        if (thread == null) {
            return false;
        }
        return switch (kind) {
        case ALL -> true;
        case UNSORTED -> thread.tags().isEmpty() || thread.addressedToAccount();
        case ACCOUNT -> value.equals(thread.accountId());
        case TAG -> thread.tags().contains(value);
        };
    }
}
