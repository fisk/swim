package org.fisk.swim.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class EditorConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private EditorConfigStore() {
    }

    public static EditorConfig load(EditorPaths paths) {
        try {
            ensureDirectories(paths);
            if (!Files.isRegularFile(paths.configPath()) || Files.readString(paths.configPath()).trim().isEmpty()) {
                return EditorConfig.empty();
            }
            try (Reader reader = Files.newBufferedReader(paths.configPath())) {
                EditorConfig config = GSON.fromJson(reader, EditorConfig.class);
                return config == null ? EditorConfig.empty() : config;
            }
        } catch (IOException e) {
            return EditorConfig.empty();
        }
    }

    public static EditorSession loadSession(EditorPaths paths) {
        try {
            ensureDirectories(paths);
            if (!Files.isRegularFile(paths.sessionPath()) || Files.readString(paths.sessionPath()).trim().isEmpty()) {
                return EditorSession.empty();
            }
            try (Reader reader = Files.newBufferedReader(paths.sessionPath())) {
                EditorSession session = GSON.fromJson(reader, EditorSession.class);
                return session == null ? EditorSession.empty() : session;
            }
        } catch (IOException e) {
            return EditorSession.empty();
        }
    }

    public static void saveSession(EditorPaths paths, EditorSession session) {
        try {
            ensureDirectories(paths);
            try (Writer writer = Files.newBufferedWriter(paths.sessionPath())) {
                GSON.toJson(session == null ? EditorSession.empty() : session, writer);
            }
        } catch (IOException e) {
        }
    }

    private static void ensureDirectories(EditorPaths paths) throws IOException {
        Files.createDirectories(paths.swimHome());
    }
}
