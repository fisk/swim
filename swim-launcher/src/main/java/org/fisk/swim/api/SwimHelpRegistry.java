package org.fisk.swim.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SwimHelpRegistry {
    private static final Object LOCK = new Object();
    private static final Map<String, RegisteredChapter> CHAPTERS_BY_ID = new LinkedHashMap<>();
    private static final Map<String, List<String>> CHAPTER_IDS_BY_PLUGIN = new LinkedHashMap<>();

    private SwimHelpRegistry() {
    }

    public static AutoCloseable register(String pluginId, SwimHelpChapter chapter) {
        Objects.requireNonNull(chapter, "chapter");
        String owner = normalizePluginId(pluginId);
        String id = requireChapterId(chapter.id());
        RegisteredChapter registered = new RegisteredChapter(owner, id, chapter);
        synchronized (LOCK) {
            RegisteredChapter previous = CHAPTERS_BY_ID.get(id);
            if (previous != null && !previous.pluginId().equals(owner)) {
                throw new IllegalArgumentException("Duplicate SWIM help chapter: " + id);
            }
            CHAPTERS_BY_ID.put(id, registered);
            CHAPTER_IDS_BY_PLUGIN.computeIfAbsent(owner, ignored -> new ArrayList<>());
            List<String> ids = CHAPTER_IDS_BY_PLUGIN.get(owner);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return () -> unregister(id, registered);
    }

    public static List<SwimHelpChapter> chapters() {
        synchronized (LOCK) {
            return CHAPTERS_BY_ID.values().stream()
                    .map(RegisteredChapter::chapter)
                    .toList();
        }
    }

    public static void unregisterPlugin(String pluginId) {
        String owner = normalizePluginId(pluginId);
        synchronized (LOCK) {
            List<String> ids = CHAPTER_IDS_BY_PLUGIN.remove(owner);
            if (ids == null) {
                return;
            }
            for (String id : ids) {
                RegisteredChapter registered = CHAPTERS_BY_ID.get(id);
                if (registered != null && registered.pluginId().equals(owner)) {
                    CHAPTERS_BY_ID.remove(id);
                }
            }
        }
    }

    public static void clearForTests() {
        clear();
    }

    public static void clear() {
        synchronized (LOCK) {
            CHAPTERS_BY_ID.clear();
            CHAPTER_IDS_BY_PLUGIN.clear();
        }
    }

    private static void unregister(String id, RegisteredChapter registered) {
        synchronized (LOCK) {
            RegisteredChapter current = CHAPTERS_BY_ID.get(id);
            if (!registered.equals(current)) {
                return;
            }
            CHAPTERS_BY_ID.remove(id);
            List<String> ids = CHAPTER_IDS_BY_PLUGIN.get(registered.pluginId());
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    CHAPTER_IDS_BY_PLUGIN.remove(registered.pluginId());
                }
            }
        }
    }

    private static String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return "plugin";
        }
        return pluginId.trim();
    }

    private static String requireChapterId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SWIM help chapter id is blank");
        }
        return id.strip();
    }

    private record RegisteredChapter(String pluginId, String id, SwimHelpChapter chapter) {
    }
}
