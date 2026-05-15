package org.fisk.swim.plugins.email;

import java.util.List;

record EmailAccountsConfig(List<EmailAccountConfig> accounts) {
    EmailAccountsConfig {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    static EmailAccountsConfig empty() {
        return new EmailAccountsConfig(List.of());
    }
}
