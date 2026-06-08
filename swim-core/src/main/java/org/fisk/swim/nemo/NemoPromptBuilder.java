package org.fisk.swim.nemo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fisk.swim.text.BufferContext;

final class NemoPromptBuilder {
    private static final int APPROX_CHARS_PER_TOKEN = 3;
    private static final int SUMMARY_LINE_CHARS = 180;
    private static final int MAX_SUMMARY_LINES = 24;
    private static final int MAX_BUDGETED_SKILL_CHARS = 24_000;

    private NemoPromptBuilder() {
    }

    static String buildInput(BufferContext context, List<NemoClient.ChatTurn> turns, NemoClient.Configuration configuration,
            List<NemoSkillDocument> skills) {
        var buffer = context.getBuffer();
        String fullTranscript = transcript(turns);
        String fullFile = buffer.getString();
        int promptBudget = promptBudgetChars(configuration);
        String path = String.valueOf(buffer.getPath());

        String capabilities = capabilitiesSection(configuration);
        String fullSkills = skillsSection(skills);
        String fullPrompt = assemblePrompt(configuration.systemPrompt(), capabilities, fullSkills, fullTranscript,
                path, "File contents", fullFile);
        if (promptBudget == Integer.MAX_VALUE) {
            return fullPrompt;
        }
        if (fullPrompt.length() <= promptBudget) {
            return fullPrompt;
        }

        return buildBudgetedInput(path, fullFile, buffer.getCursor().getPosition(),
                turns, configuration, skills, promptBudget);
    }

    private static String buildBudgetedInput(String path, String fullFile, int cursorPosition,
            List<NemoClient.ChatTurn> turns, NemoClient.Configuration configuration, List<NemoSkillDocument> skills,
            int promptBudget) {
        String systemPrompt = configuration.systemPrompt();
        String capabilities = capabilitiesSection(configuration);
        String conversationHeader = "\n\nConversation:\n";
        String fileHeader = "\n\nCurrent file:\n" + path + "\n\nFile contents (budgeted around cursor):\n";
        int variableBudget = Math.max(0,
                promptBudget - systemPrompt.length() - capabilities.length() - conversationHeader.length() - fileHeader.length());

        int skillBudget = skills.isEmpty() ? 0
                : Math.min(Math.min(variableBudget / 5, MAX_BUDGETED_SKILL_CHARS), skillsSection(skills).length());
        String skillText = fitSkillsSection(skills, skillBudget);
        variableBudget = Math.max(0, variableBudget - skillText.length());

        int transcriptBudget = variableBudget / 2;
        String compactTranscript = compactTranscript(turns, transcriptBudget);
        String fileContents = fitFileAroundCursor(fullFile, cursorPosition,
                Math.max(0, variableBudget - compactTranscript.length()));

        int unused = variableBudget - compactTranscript.length() - fileContents.length();
        if (unused > 0) {
            compactTranscript = compactTranscript(turns, transcriptBudget + unused);
            fileContents = fitFileAroundCursor(fullFile, cursorPosition,
                    Math.max(0, variableBudget - compactTranscript.length()));
        }

        String result = systemPrompt + capabilities + skillText + conversationHeader + compactTranscript + fileHeader + fileContents;
        if (result.length() <= promptBudget) {
            return result;
        }
        int overflow = result.length() - promptBudget;
        fileContents = fitFileAroundCursor(fullFile, cursorPosition, Math.max(0, fileContents.length() - overflow));
        return systemPrompt + capabilities + skillText + conversationHeader + compactTranscript + fileHeader + fileContents;
    }

    private static int promptBudgetChars(NemoClient.Configuration configuration) {
        Integer contextWindowTokens = configuration.contextWindowTokens();
        if (contextWindowTokens == null || contextWindowTokens <= 0) {
            return Integer.MAX_VALUE;
        }
        int reserveTokens = outputReserveTokens(configuration, contextWindowTokens);
        long budget = (long) Math.max(1, contextWindowTokens - reserveTokens) * APPROX_CHARS_PER_TOKEN;
        return (int) Math.min(Integer.MAX_VALUE, budget);
    }

    private static int outputReserveTokens(NemoClient.Configuration configuration, int contextWindowTokens) {
        Integer maxOutputTokens = configuration.maxOutputTokens();
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            return Math.min(contextWindowTokens - 1, maxOutputTokens);
        }
        int minimumReserve = contextWindowTokens >= 4096 ? 2048 : 32;
        return Math.min(contextWindowTokens - 1, Math.max(minimumReserve, contextWindowTokens / 10));
    }

    private static String assemblePrompt(String systemPrompt, String capabilities, String skills, String transcript, String path,
            String fileLabel, String fileContents) {
        return systemPrompt
                + capabilities
                + skills
                + "\n\nConversation:\n"
                + transcript
                + "\n\nCurrent file:\n"
                + path
                + "\n\n"
                + fileLabel
                + ":\n"
                + fileContents;
    }

    private static String capabilitiesSection(NemoClient.Configuration configuration) {
        var lines = new ArrayList<String>();
        lines.add("\n\nNemo runtime capabilities:");
        lines.add("- permission mode: " + configuration.toolPermissionMode().replace('_', '-'));
        if ("read_only".equals(configuration.toolPermissionMode())) {
            lines.add("- mutating tools are disabled; inspect files and explain the change needed.");
        } else {
            var writeTools = new ArrayList<String>();
            if (configuration.toolWriteFile()) {
                writeTools.add("write_file");
            }
            if (configuration.toolApplyPatch()) {
                writeTools.add("apply_patch");
            }
            if (writeTools.isEmpty()) {
                lines.add("- file-writing tools are disabled by configuration.");
            } else {
                lines.add("- " + String.join(" and ", writeTools)
                        + " make real workspace file edits; successful edits are saved to disk and persist across Nemo/editor runs.");
            }
        }
        lines.add("- approval policy: " + configuration.toolApprovalPolicy().replace('_', '-')
                + "; approval prompts pause tool execution but do not remove tool capability.");
        if (!configuration.mcpServers().isEmpty()) {
            lines.add("- MCP stdio servers are configured; tools named mcp__server__tool are discovered dynamically, may access external systems outside Nemo's workspace sandbox, and require approval unless the session is full-access.");
        }
        if (configuration.toolScreenSnapshot() || configuration.toolDriveEditor()) {
            lines.add("- start_editor_control requests host approval for an explicit single-owner editor-control session; call it before screen_snapshot or drive_editor.");
        }
        if (configuration.toolScreenSnapshot()) {
            lines.add("- screen_snapshot can inspect a host-filtered view of the editor screen only during an active editor-control session; host-only approval overlays, Nemo chat contents, and private/non-buffer workspaces are not visible through it.");
        }
        if (configuration.toolDriveEditor() && NemoClient.isToolAllowedByPermission(configuration, "drive_editor")) {
            lines.add("- drive_editor can send bounded key streams to the active editor buffer only while this session holds editor-control; editor actions are opt-in at execution time, allowing workspace-local navigation, editing, search, and saves when permitted while blocking host overlays, shell input, Nemo UI, mail, Slack, Todo, external workspaces, and other boundary-crossing interactions.");
        } else if (configuration.toolDriveEditor()) {
            lines.add("- drive_editor is configured but unavailable in the current permission mode.");
        }
        if (configuration.toolScreenSnapshot() || configuration.toolDriveEditor()) {
            lines.add("- finish_editor_control releases the editor-control lock and reopens the invoking Nemo chat when editor-control work is done so you can report findings.");
        }
        var pluginTools = NemoClient.pluginToolDescriptors(configuration);
        if (!pluginTools.isEmpty()) {
            lines.add("- loaded plugins expose tools named plugin__plugin__tool; plugin tools run plugin code, may access plugin-managed systems, and require approval unless the session is full-access or the plugin marks the tool approval-free.");
        }
        if (configuration.toolDelegateTask()) {
            lines.add("- delegate_task starts focused work in a separate Nemo sub-agent worker so this session can continue; sub-agents inherit these tools, permissions, sandbox, and approval policy.");
            lines.add("- use worker_status/read_worker to check sub-agent progress; use join_worker only when you need the sub-agent result before continuing.");
            lines.add("- use message_worker to send corrections or extra instructions to a running sub-agent; queued messages are delivered at the next safe request boundary.");
        }
        return String.join("\n", lines);
    }

    private static String skillsSection(List<NemoSkillDocument> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        var result = new StringBuilder();
        result.append("\n\nApplicable workspace instructions:");
        for (var skill : skills) {
            result.append("\n\n--- ").append(skill.relativePath()).append(" ---\n");
            result.append(skill.content());
        }
        return result.toString();
    }

    private static String fitSkillsSection(List<NemoSkillDocument> skills, int budget) {
        if (skills.isEmpty() || budget <= 0) {
            return "";
        }
        String full = skillsSection(skills);
        if (full.length() <= budget) {
            return full;
        }

        var result = new StringBuilder();
        result.append("\n\nApplicable workspace instructions (budgeted):");
        for (int i = 0; i < skills.size(); ++i) {
            var skill = skills.get(i);
            String header = "\n\n--- " + skill.relativePath() + " ---\n";
            int remaining = budget - result.length() - header.length();
            if (remaining <= 0) {
                appendIfFits(result, "\n[remaining skill instructions omitted]", budget);
                break;
            }
            result.append(header);
            result.append(fitEnd(skill.content(), remaining));
            if (skill.content().length() > remaining) {
                appendIfFits(result, "\n[remaining skill instructions omitted]", budget);
                break;
            }
        }
        return fitEnd(result.toString(), budget);
    }

    private static void appendIfFits(StringBuilder result, String text, int budget) {
        int remaining = budget - result.length();
        if (remaining > 0) {
            result.append(fitEnd(text, remaining));
        }
    }

    private static String transcript(List<NemoClient.ChatTurn> turns) {
        var transcript = new StringBuilder();
        for (var turn : promptTurns(turns)) {
            if (!transcript.isEmpty()) {
                transcript.append("\n\n");
            }
            transcript.append(renderTurn(turn));
        }
        return transcript.toString();
    }

    private static List<NemoClient.ChatTurn> promptTurns(List<NemoClient.ChatTurn> turns) {
        var result = new ArrayList<NemoClient.ChatTurn>();
        for (var turn : turns) {
            if (turn.includeInPrompt()) {
                result.add(turn);
            }
        }
        return result;
    }

    private static String renderTurn(NemoClient.ChatTurn turn) {
        return turn.speaker() + "> " + turn.text();
    }

    private static String compactTranscript(List<NemoClient.ChatTurn> turns, int budget) {
        var promptTurns = promptTurns(turns);
        String full = transcript(promptTurns);
        if (full.length() <= budget) {
            return full;
        }
        if (budget <= 0) {
            return "";
        }

        var recent = new ArrayList<String>();
        int recentChars = 0;
        int firstIncluded = promptTurns.size();
        for (int i = promptTurns.size() - 1; i >= 0; --i) {
            String rendered = renderTurn(promptTurns.get(i));
            int separatorChars = recent.isEmpty() ? 0 : 2;
            if (recentChars + separatorChars + rendered.length() > budget) {
                if (recent.isEmpty()) {
                    recent.add(fitMiddle(rendered, budget));
                    firstIncluded = i;
                }
                break;
            }
            recent.add(rendered);
            recentChars += separatorChars + rendered.length();
            firstIncluded = i;
        }
        Collections.reverse(recent);

        if (firstIncluded <= 0) {
            return String.join("\n\n", recent);
        }

        String recentText = String.join("\n\n", recent);
        int summaryBudget = Math.max(0, budget - recentText.length() - 2);
        String summary = compactOlderTurns(promptTurns.subList(0, firstIncluded), summaryBudget);
        if (summary.isBlank()) {
            return recentText;
        }
        if (recentText.isBlank()) {
            return summary;
        }
        return summary + "\n\n" + recentText;
    }

    private static String compactOlderTurns(List<NemoClient.ChatTurn> omitted, int budget) {
        if (omitted.isEmpty() || budget <= 0) {
            return "";
        }
        var lines = new ArrayList<String>();
        lines.add("[earlier conversation compacted: " + omitted.size() + " turns omitted]");
        int first = Math.max(0, omitted.size() - MAX_SUMMARY_LINES);
        for (int i = first; i < omitted.size(); ++i) {
            var turn = omitted.get(i);
            lines.add("- " + turn.speaker() + ": " + fitOneLine(turn.text(), SUMMARY_LINE_CHARS));
        }
        if (first > 0) {
            lines.add(1, "- " + first + " older turns omitted before these notes.");
        }
        return fitEnd(String.join("\n", lines), budget);
    }

    private static String fitFileAroundCursor(String text, int cursorPosition, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        if (budget <= 0) {
            return "";
        }

        int cursor = Math.max(0, Math.min(cursorPosition, text.length()));
        int sliceBudget = Math.max(1, budget - omittedMarkerBudget(text.length()));
        if (sliceBudget < 80) {
            return cursorSlice(text, cursor, budget);
        }

        String result = fileSliceWithMarkers(text, cursor, sliceBudget);
        int overflow = result.length() - budget;
        if (overflow > 0) {
            result = fileSliceWithMarkers(text, cursor, Math.max(1, sliceBudget - overflow));
        }
        return result.length() <= budget ? result : cursorSlice(text, cursor, budget);
    }

    private static String fileSliceWithMarkers(String text, int cursor, int sliceBudget) {
        int start = Math.max(0, cursor - sliceBudget / 2);
        int end = Math.min(text.length(), start + sliceBudget);
        start = Math.max(0, end - sliceBudget);

        var result = new StringBuilder();
        if (start > 0) {
            result.append("[... omitted ").append(start).append(" chars before cursor ...]\n");
        }
        result.append(text, start, end);
        if (end < text.length()) {
            if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
                result.append('\n');
            }
            result.append("[... omitted ").append(text.length() - end).append(" chars after cursor ...]");
        }
        return result.toString();
    }

    private static int omittedMarkerBudget(int textLength) {
        int digits = Integer.toString(Math.max(0, textLength)).length();
        return 76 + digits * 2;
    }

    private static String cursorSlice(String text, int cursor, int budget) {
        if (budget <= 0) {
            return "";
        }
        int start = Math.max(0, cursor - budget / 2);
        int end = Math.min(text.length(), start + budget);
        start = Math.max(0, end - budget);
        return text.substring(start, end);
    }

    private static String fitOneLine(String text, int maxChars) {
        return fitEnd(text.replaceAll("\\s+", " ").trim(), maxChars);
    }

    private static String fitEnd(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static String fitMiddle(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }
        String marker = "\n[... compacted ...]\n";
        if (maxChars <= marker.length() + 2) {
            return fitEnd(text, maxChars);
        }
        int remaining = maxChars - marker.length();
        int head = remaining / 2;
        int tail = remaining - head;
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }
}
