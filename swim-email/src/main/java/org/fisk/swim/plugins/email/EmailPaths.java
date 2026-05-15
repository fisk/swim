package org.fisk.swim.plugins.email;

import java.nio.file.Path;

record EmailPaths(
        Path swimHome,
        Path emailHome,
        Path accountsPath,
        Path tagRulesPath,
        Path databasePath,
        Path oauthTokensPath) {
    static EmailPaths fromUserHome() {
        Path swimHome = Path.of(System.getProperty("user.home"), ".swim");
        Path emailHome = swimHome.resolve("email");
        return new EmailPaths(
                swimHome,
                emailHome,
                emailHome.resolve("accounts.json"),
                emailHome.resolve("tag-rules.json"),
                emailHome.resolve("mail.db"),
                emailHome.resolve("oauth-tokens.json"));
    }
}
