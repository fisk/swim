package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class EmailConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private EmailConfigStore() {
    }

    static void ensureDefaultFiles(EmailPaths paths) throws IOException {
        Files.createDirectories(paths.emailHome());
        if (!Files.exists(paths.accountsPath())) {
            writeJson(paths.accountsPath(), EmailAccountsConfig.empty());
        }
        if (!Files.exists(paths.tagRulesPath())) {
            writeJson(paths.tagRulesPath(), EmailTagRulesConfig.empty());
        }
        if (!Files.exists(paths.oauthTokensPath())) {
            writeJson(paths.oauthTokensPath(), OAuthTokenCache.empty());
        }
    }

    static EmailAccountsConfig loadAccounts(EmailPaths paths) throws IOException {
        ensureDefaultFiles(paths);
        if (isBlankJsonFile(paths.accountsPath())) {
            return EmailAccountsConfig.empty();
        }
        try (Reader reader = Files.newBufferedReader(paths.accountsPath())) {
            EmailAccountsConfig config = GSON.fromJson(reader, EmailAccountsConfig.class);
            return config == null ? EmailAccountsConfig.empty() : config;
        }
    }

    static EmailTagRulesConfig loadTagRules(EmailPaths paths) throws IOException {
        ensureDefaultFiles(paths);
        if (isBlankJsonFile(paths.tagRulesPath())) {
            return EmailTagRulesConfig.empty();
        }
        try (Reader reader = Files.newBufferedReader(paths.tagRulesPath())) {
            EmailTagRulesConfig config = GSON.fromJson(reader, EmailTagRulesConfig.class);
            return config == null ? EmailTagRulesConfig.empty() : config;
        }
    }

    private static void writeJson(java.nio.file.Path path, Object value) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(value, writer);
        }
    }

    private static boolean isBlankJsonFile(java.nio.file.Path path) throws IOException {
        return !Files.isRegularFile(path) || Files.readString(path).trim().isEmpty();
    }
}
