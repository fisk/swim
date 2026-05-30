package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

final class GitRepositoryLocator {
    private GitRepositoryLocator() {
    }

    static Path findRepositoryRoot(Path path) {
        if (path == null) {
            return null;
        }
        Path start = Files.isDirectory(path) ? path : path.getParent();
        if (start == null) {
            return null;
        }
        var builder = new FileRepositoryBuilder().findGitDir(start.toFile());
        if (builder.getGitDir() == null) {
            return null;
        }
        try (var repository = builder.build()) {
            var workTree = repository.getWorkTree();
            return workTree == null ? null : workTree.toPath().toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        }
    }
}
