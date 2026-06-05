package org.fisk.swim.nemo;

import java.util.List;

import org.fisk.swim.text.BufferContext;

final class NemoPromptBuilder {
    private NemoPromptBuilder() {
    }

    static String buildInput(BufferContext context, List<NemoClient.ChatTurn> turns, NemoClient.Configuration configuration,
            List<NemoSkillDocument> skills) {
        var buffer = context.getBuffer();
        var transcript = new StringBuilder();
        for (var turn : turns) {
            if (!turn.includeInPrompt()) {
                continue;
            }
            if (!transcript.isEmpty()) {
                transcript.append("\n\n");
            }
            transcript.append(turn.speaker()).append("> ").append(turn.text());
        }

        var prompt = new StringBuilder();
        prompt.append(configuration.systemPrompt());
        if (!skills.isEmpty()) {
            prompt.append("\n\nApplicable SKILLS.md instructions:");
            for (var skill : skills) {
                prompt.append("\n\n--- ").append(skill.relativePath()).append(" ---\n");
                prompt.append(skill.content());
            }
        }
        prompt.append("\n\nConversation:\n");
        prompt.append(transcript);
        prompt.append("\n\nCurrent file:\n");
        prompt.append(buffer.getPath());
        prompt.append("\n\nFile contents:\n");
        prompt.append(buffer.getString());
        return prompt.toString();
    }
}
