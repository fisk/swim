package org.fisk.swim.slack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface SlackClient extends AutoCloseable {
    SlackSnapshot snapshot();

    List<SlackChannelSummary> loadChannels(String workspaceId);

    default SlackMessagePage loadMessages(String workspaceId, String conversationId, int offset, int limit) {
        return loadMessages(workspaceId, conversationId, "", offset, limit);
    }

    default SlackMessagePage loadMessages(String workspaceId, String conversationId, String query, int offset, int limit) {
        List<SlackMessageSummary> filtered = filterMessages(loadAllMessages(workspaceId, conversationId), query);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        if (safeOffset >= filtered.size() || safeLimit == 0) {
            return new SlackMessagePage(List.of(), filtered.size(), false);
        }
        int endExclusive = Math.min(filtered.size(), safeOffset + safeLimit);
        return new SlackMessagePage(filtered.subList(safeOffset, endExclusive), filtered.size(), false);
    }

    default List<SlackMessageSummary> loadAllMessages(String workspaceId, String conversationId) {
        return List.of();
    }

    SlackThreadDetail loadThread(String workspaceId, String conversationId, String threadTs);

    void refresh();

    default SlackSendResult sendMessage(SlackDraft draft) {
        return SlackSendResult.failure("Sending is not available");
    }

    Path getDataPath();

    @Override
    default void close() {
    }

    private static List<SlackMessageSummary> filterMessages(List<SlackMessageSummary> messages, String query) {
        if (query == null || query.isBlank()) {
            return messages;
        }
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        var filtered = new ArrayList<SlackMessageSummary>();
        for (SlackMessageSummary message : messages) {
            if (contains(message.userDisplayName(), normalized)
                    || contains(message.sentAt(), normalized)
                    || contains(message.text(), normalized)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
