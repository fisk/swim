package org.fisk.swim.slack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeSlackClient implements SlackClient {
    private final Path _dataPath;
    private final AtomicInteger _refreshCount = new AtomicInteger();
    private final List<SlackDraft> _sentDrafts = new ArrayList<>();
    private SlackSnapshot _snapshot;
    private final Map<String, List<SlackChannelSummary>> _channelsByWorkspace = new LinkedHashMap<>();
    private final Map<String, List<SlackMessageSummary>> _messagesByConversation = new LinkedHashMap<>();
    private final Map<String, SlackThreadDetail> _threadsByConversationAndTs = new LinkedHashMap<>();

    public FakeSlackClient(Path dataPath) {
        _dataPath = dataPath;
        _snapshot = new SlackSnapshot(
                List.of(new SlackWorkspaceSummary("work", "Work Slack", "Work", "me", true, "Connected")),
                "Connected to 1 Slack workspace");
        _channelsByWorkspace.put("work", List.of(
                new SlackChannelSummary("work", "C1", "general", "#general", "channel", "", "2026-06-04 09:00", false),
                new SlackChannelSummary("work", "D1", "alice", "@alice", "im", "", "2026-06-04 08:00", false)));
        _messagesByConversation.put(key("work", "C1"), List.of(
                new SlackMessageSummary("work", "C1", "200.000200", "200.000200", "Alice", "2026-06-04 09:00",
                        "Latest thread opener", 2),
                new SlackMessageSummary("work", "C1", "100.000100", "100.000100", "Bob", "2026-06-04 08:00",
                        "Earlier message", 0)));
        _messagesByConversation.put(key("work", "D1"), List.of(
                new SlackMessageSummary("work", "D1", "150.000150", "150.000150", "Alice", "2026-06-04 08:30",
                        "Ping me when you are free", 0)));
        _threadsByConversationAndTs.put(key("C1", "200.000200"), new SlackThreadDetail(
                "work",
                "C1",
                "#general",
                "200.000200",
                List.of(
                        new SlackMessageEntry("200.000200", "Alice", "2026-06-04 09:00", "Latest thread opener"),
                        new SlackMessageEntry("201.000201", "me", "2026-06-04 09:01", "First reply"),
                        new SlackMessageEntry("202.000202", "Alice", "2026-06-04 09:02", "Second reply"))));
        _threadsByConversationAndTs.put(key("C1", "100.000100"), new SlackThreadDetail(
                "work",
                "C1",
                "#general",
                "100.000100",
                List.of(new SlackMessageEntry("100.000100", "Bob", "2026-06-04 08:00", "Earlier message"))));
        _threadsByConversationAndTs.put(key("D1", "150.000150"), new SlackThreadDetail(
                "work",
                "D1",
                "@alice",
                "150.000150",
                List.of(new SlackMessageEntry("150.000150", "Alice", "2026-06-04 08:30", "Ping me when you are free"))));
    }

    public int refreshCount() {
        return _refreshCount.get();
    }

    public List<SlackDraft> sentDrafts() {
        return List.copyOf(_sentDrafts);
    }

    @Override
    public SlackSnapshot snapshot() {
        return _snapshot;
    }

    @Override
    public List<SlackChannelSummary> loadChannels(String workspaceId) {
        return _channelsByWorkspace.getOrDefault(workspaceId, List.of());
    }

    @Override
    public SlackMessagePage loadMessages(String workspaceId, String conversationId, String query, int offset, int limit) {
        List<SlackMessageSummary> messages = _messagesByConversation.getOrDefault(key(workspaceId, conversationId), List.of());
        int safeOffset = Math.max(0, offset);
        int endExclusive = Math.min(messages.size(), safeOffset + Math.max(0, limit));
        if (safeOffset >= messages.size()) {
            return new SlackMessagePage(List.of(), messages.size(), false);
        }
        return new SlackMessagePage(List.copyOf(messages.subList(safeOffset, endExclusive)), messages.size(), false);
    }

    @Override
    public SlackThreadDetail loadThread(String workspaceId, String conversationId, String threadTs) {
        return _threadsByConversationAndTs.getOrDefault(key(conversationId, threadTs),
                new SlackThreadDetail(workspaceId, conversationId, conversationId, threadTs, List.of()));
    }

    @Override
    public void refresh() {
        _refreshCount.incrementAndGet();
    }

    @Override
    public SlackSendResult sendMessage(SlackDraft draft) {
        _sentDrafts.add(draft);
        String mapKey = key(draft.workspaceId(), draft.conversationId());
        var updated = new ArrayList<>(_messagesByConversation.getOrDefault(mapKey, List.of()));
        updated.addFirst(new SlackMessageSummary(
                draft.workspaceId(),
                draft.conversationId(),
                "999.000999",
                draft.threadTs() == null || draft.threadTs().isBlank() ? "999.000999" : draft.threadTs(),
                "me",
                "2026-06-04 10:00",
                draft.text(),
                0));
        _messagesByConversation.put(mapKey, List.copyOf(updated));
        return SlackSendResult.success("Slack message sent");
    }

    @Override
    public Path getDataPath() {
        return _dataPath;
    }

    private static String key(String left, String right) {
        return left + "\0" + right;
    }
}
