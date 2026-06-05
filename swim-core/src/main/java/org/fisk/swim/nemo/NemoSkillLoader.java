package org.fisk.swim.nemo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.fisk.swim.text.BufferContext;

final class NemoSkillLoader {
    private NemoSkillLoader() {
    }

    static List<NemoSkillDocument> loadApplicableSkills(BufferContext context, Path workspaceRoot,
            NemoClient.Configuration configuration) {
        if (!configuration.skillsEnabled() || workspaceRoot == null) {
            return List.of();
        }

        var candidates = new LinkedHashSet<Path>();
        candidates.add(workspaceRoot.resolve("SKILLS.md"));

        Path bufferPath = context.getBuffer().getPath();
        if (bufferPath != null) {
            Path normalizedPath = bufferPath.toAbsolutePath().normalize();
            Path currentDirectory = Files.isDirectory(normalizedPath) ? normalizedPath : normalizedPath.getParent();
            while (currentDirectory != null && currentDirectory.startsWith(workspaceRoot)) {
                candidates.add(currentDirectory.resolve("SKILLS.md"));
                if (currentDirectory.equals(workspaceRoot)) {
                    break;
                }
                currentDirectory = currentDirectory.getParent();
            }
        }

        var skillDocuments = new ArrayList<NemoSkillDocument>();
        for (Path skillPath : candidates) {
            if (skillDocuments.size() >= configuration.skillsMaxFiles() || !Files.isRegularFile(skillPath)) {
                continue;
            }
            try {
                String content = Files.readString(skillPath, StandardCharsets.UTF_8);
                if (content.length() > configuration.skillsMaxChars()) {
                    content = content.substring(0, configuration.skillsMaxChars())
                            + "\n...[truncated]";
                }
                String relativePath = workspaceRoot.relativize(skillPath).toString();
                skillDocuments.add(new NemoSkillDocument(relativePath.isBlank() ? "SKILLS.md" : relativePath, content));
            } catch (IOException ignored) {
            }
        }
        return skillDocuments;
    }
}
