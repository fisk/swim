package org.fisk.swim.plugins.email;

import java.nio.file.Path;

record EmailPaths(
        Path swimHome,
        Path emailHome,
        Path accountsPath,
        Path tagRulesPath,
        Path databasePath,
        Path oauthTokensPath) {
    Path databaseBasePath() {
        String fileName = databasePath.getFileName().toString();
        if (fileName.endsWith(".mv.db")) {
            return databasePath.resolveSibling(fileName.substring(0, fileName.length() - ".mv.db".length()));
        }
        return databasePath;
    }

    Path databaseFilePath() {
        String fileName = databasePath.getFileName().toString();
        if (fileName.endsWith(".mv.db")) {
            return databasePath;
        }
        return databasePath.resolveSibling(fileName + ".mv.db");
    }

    String databaseJdbcUrl() {
        return "jdbc:h2:file:" + databaseBasePath().toAbsolutePath() + ";AUTO_SERVER=FALSE";
    }

    static EmailPaths fromUserHome() {
        Path swimHome = Path.of(System.getProperty("user.home"), ".swim");
        Path emailHome = swimHome.resolve("email");
        return new EmailPaths(
                swimHome,
                emailHome,
                emailHome.resolve("accounts.json"),
                emailHome.resolve("tag-rules.json"),
                emailHome.resolve("mail.mv.db"),
                emailHome.resolve("oauth-tokens.json"));
    }
}
