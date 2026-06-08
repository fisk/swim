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

        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        var directories = applicableDirectories(context, normalizedRoot);
        var candidates = new LinkedHashSet<Path>();
        for (Path directory : directories) {
            Path override = directory.resolve("AGENTS.override.md");
            candidates.add(Files.isRegularFile(override) ? override : directory.resolve("AGENTS.md"));
        }
        for (Path directory : directories) {
            candidates.add(directory.resolve("SKILLS.md"));
        }

        var skillDocuments = new ArrayList<NemoSkillDocument>();
        if (configuration.toolScreenSnapshot() || configuration.toolDriveEditor()) {
            skillDocuments.add(NemoEditorControlSkill.document());
        }
        int loadedWorkspaceSkills = 0;
        for (Path skillPath : candidates) {
            if (loadedWorkspaceSkills >= configuration.skillsMaxFiles() || !Files.isRegularFile(skillPath)) {
                continue;
            }
            try {
                String content = Files.readString(skillPath, StandardCharsets.UTF_8);
                if (content.length() > configuration.skillsMaxChars()) {
                    content = content.substring(0, configuration.skillsMaxChars())
                            + "\n...[truncated]";
                }
                String relativePath = normalizedRoot.relativize(skillPath).toString();
                skillDocuments.add(new NemoSkillDocument(relativePath.isBlank() ? "SKILLS.md" : relativePath, content));
                loadedWorkspaceSkills++;
            } catch (IOException ignored) {
            }
        }
        return skillDocuments;
    }

    private static List<Path> applicableDirectories(BufferContext context, Path workspaceRoot) {
        var directories = new ArrayList<Path>();
        Path bufferPath = context.getBuffer().getPath();
        Path currentDirectory = workspaceRoot;
        if (bufferPath != null) {
            Path normalizedPath = bufferPath.toAbsolutePath().normalize();
            currentDirectory = Files.isDirectory(normalizedPath) ? normalizedPath : normalizedPath.getParent();
        }
        if (currentDirectory == null || !currentDirectory.startsWith(workspaceRoot)) {
            directories.add(workspaceRoot);
            return directories;
        }

        var reversed = new ArrayList<Path>();
        while (currentDirectory != null && currentDirectory.startsWith(workspaceRoot)) {
            reversed.add(currentDirectory);
            if (currentDirectory.equals(workspaceRoot)) {
                break;
            }
            currentDirectory = currentDirectory.getParent();
        }
        for (int i = reversed.size() - 1; i >= 0; --i) {
            directories.add(reversed.get(i));
        }
        return directories;
    }
}
