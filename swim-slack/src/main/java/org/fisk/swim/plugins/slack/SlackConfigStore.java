package org.fisk.swim.plugins.slack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class SlackConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SlackConfigStore() {
    }

    static void ensureDefaultFiles(SlackPaths paths) throws IOException {
        Files.createDirectories(paths.slackHome());
        if (!Files.exists(paths.workspacesPath())) {
            writeJson(paths.workspacesPath(), SlackWorkspacesConfig.empty());
        }
    }

    static SlackWorkspacesConfig loadWorkspaces(SlackPaths paths) throws IOException {
        ensureDefaultFiles(paths);
        if (!Files.isRegularFile(paths.workspacesPath()) || Files.readString(paths.workspacesPath()).trim().isEmpty()) {
            return SlackWorkspacesConfig.empty();
        }
        try (Reader reader = Files.newBufferedReader(paths.workspacesPath())) {
            SlackWorkspacesConfig config = GSON.fromJson(reader, SlackWorkspacesConfig.class);
            return config == null ? SlackWorkspacesConfig.empty() : config;
        }
    }

    private static void writeJson(java.nio.file.Path path, Object value) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(value, writer);
        }
    }
}
