package org.fisk.swim.plugins.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.treewalk.filter.PathFilter;

final class GitStatusService {
    private static final Pattern FULL_HASH = Pattern.compile("[0-9a-f]{40}");

    private GitStatusService() {
    }

    static GitStatusSnapshot snapshot(Path currentPath) throws IOException, GitAPIException {
        Path root = GitRepositoryLocator.findRepositoryRoot(currentPath);
        if (root == null) {
            return GitStatusSnapshot.noRepository();
        }
        try (var git = Git.open(root.toFile())) {
            Status status = git.status().call();
            return new GitStatusSnapshot(
                    root,
                    safeBranch(git),
                    sorted(tracked(status.getAdded(), root, GitSection.STAGED, "A"),
                            tracked(status.getChanged(), root, GitSection.STAGED, "M"),
                            tracked(status.getRemoved(), root, GitSection.STAGED, "D")),
                    sorted(tracked(status.getModified(), root, GitSection.UNSTAGED, "M"),
                            tracked(status.getMissing(), root, GitSection.UNSTAGED, "D")),
                    sorted(tracked(status.getUntracked(), root, GitSection.UNTRACKED, "?"),
                            tracked(status.getUntrackedFolders(), root, GitSection.UNTRACKED, "?")),
                    sorted(tracked(status.getConflicting(), root, GitSection.CONFLICTS, "U")),
                    stashEntries(git),
                    recentCommits(git),
                    reflogEntries(git),
                    status.isClean() ? "Working tree clean" : "Repository has changes");
        }
    }

    static void stage(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            var add = git.add().addFilepattern(change.relativePath());
            if (!Files.exists(change.absolutePath())) {
                add.setUpdate(true);
            }
            add.call();
        }
    }

    static void stageAll(Path repositoryRoot) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
        }
    }

    static void unstage(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.reset().addPath(change.relativePath()).call();
        }
    }

    static void unstageAll(Path repositoryRoot) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.reset().setRef("HEAD").call();
        }
    }

    static void discard(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        if (change.section() == GitSection.UNTRACKED) {
            deleteRecursively(change.absolutePath());
            return;
        }
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.checkout().addPath(change.relativePath()).call();
        }
    }

    static String diffText(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        if (change.section() == GitSection.UNTRACKED || change.section() == GitSection.CONFLICTS) {
            return safeFileContents(change.absolutePath(), change.relativePath());
        }
        var diffView = buildDiffView(repositoryRoot, change);
        if (!diffView.lines().isEmpty()) {
            return String.join("\n", diffView.lines());
        }
        return "No diff available for " + change.relativePath();
    }

    static GitDiffView buildDiffView(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        if (change.section() == GitSection.UNTRACKED || change.section() == GitSection.CONFLICTS) {
            String text = safeFileContents(change.absolutePath(), change.relativePath());
            return new GitDiffView("", List.of(text.split("\\R", -1)), List.of(), null);
        }
        try (var git = Git.open(repositoryRoot.toFile())) {
            var output = new ByteArrayOutputStream();
            git.diff()
                    .setCached(change.section() == GitSection.STAGED)
                    .setPathFilter(PathFilter.create(change.relativePath()))
                    .setOutputStream(output)
                    .call();
            String patchText = output.toString(StandardCharsets.UTF_8);
            if (patchText.isBlank()) {
                return new GitDiffView("", List.of("No diff available for " + change.relativePath()), List.of(), null);
            }
            var patch = new Patch();
            patch.parse(new ByteArrayInputStream(patchText.getBytes(StandardCharsets.UTF_8)));
            if (patch.getFiles().isEmpty()) {
                return new GitDiffView("", List.of("No diff available for " + change.relativePath()), List.of(), null);
            }
            FileHeader header = patch.getFiles().getFirst();
            byte[] buffer = header.getBuffer();
            int firstHunkStart = header.getHunks().isEmpty() ? header.getEndOffset() : header.getHunks().getFirst().getStartOffset();
            String preamble = new String(buffer, 0, firstHunkStart, StandardCharsets.UTF_8);
            var lines = new ArrayList<>(splitLines(preamble));
            var hunks = new ArrayList<GitDiffHunk>();
            for (var hunk : header.getHunks()) {
                String hunkText = new String(buffer, hunk.getStartOffset(), hunk.getEndOffset() - hunk.getStartOffset(),
                        StandardCharsets.UTF_8);
                int displayStart = lines.size();
                lines.addAll(splitLines(hunkText));
                int displayEnd = Math.max(displayStart, lines.size() - 1);
                String headerLine = lines.get(displayStart);
                hunks.add(new GitDiffHunk(headerLine, preamble + hunkText, displayStart, displayEnd));
            }
            GitPatchOperation operation = switch (change.section()) {
            case UNSTAGED -> GitPatchOperation.STAGE_HUNK;
            case STAGED -> GitPatchOperation.UNSTAGE_HUNK;
            default -> null;
            };
            return new GitDiffView(preamble, List.copyOf(lines), List.copyOf(hunks), operation);
        }
    }

    static void stageHunk(Path repositoryRoot, GitDiffHunk hunk) throws IOException {
        applyPatch(repositoryRoot, hunk.patchText(), GitPatchOperation.STAGE_HUNK);
    }

    static void unstageHunk(Path repositoryRoot, GitDiffHunk hunk) throws IOException {
        applyPatch(repositoryRoot, hunk.patchText(), GitPatchOperation.UNSTAGE_HUNK);
    }

    static void discardHunk(Path repositoryRoot, GitDiffHunk hunk) throws IOException {
        applyPatch(repositoryRoot, hunk.patchText(), GitPatchOperation.DISCARD_HUNK);
    }

    static void applyPatch(Path repositoryRoot, String patchText, GitPatchOperation operation) throws IOException {
        Path patchFile = Files.createTempFile("swim-git-hunk-", ".patch");
        try {
            Files.writeString(patchFile, patchText);
            var command = new ArrayList<String>();
            command.add("git");
            command.add("apply");
            command.add("--whitespace=nowarn");
            switch (operation) {
            case STAGE_HUNK -> command.add("--cached");
            case UNSTAGE_HUNK -> {
                command.add("--cached");
                command.add("-R");
            }
            case DISCARD_HUNK -> command.add("-R");
            }
            command.add(patchFile.toString());
            var process = new ProcessBuilder(command)
                    .directory(repositoryRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = waitFor(process);
            if (exit != 0) {
                throw new IOException(output.isBlank() ? "git apply failed" : output.strip());
            }
        } finally {
            Files.deleteIfExists(patchFile);
        }
    }

    static void commit(Path repositoryRoot, String message) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            var identity = authorIdentity(git);
            git.commit()
                    .setMessage(message)
                    .setAuthor(identity.name(), identity.email())
                    .setCommitter(identity.name(), identity.email())
                    .call();
        }
    }

    static void createStash(Path repositoryRoot, String message) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            var identity = authorIdentity(git);
            git.stashCreate()
                    .setPerson(new org.eclipse.jgit.lib.PersonIdent(identity.name(), identity.email()))
                    .setIncludeUntracked(true)
                    .setWorkingDirectoryMessage(message)
                    .setIndexMessage(message)
                    .call();
        }
    }

    static void applyStash(Path repositoryRoot, GitStashEntry stash) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.stashApply()
                    .setStashRef(stash.refName())
                    .setRestoreIndex(true)
                    .setRestoreUntracked(true)
                    .call();
        }
    }

    static void popStash(Path repositoryRoot, GitStashEntry stash) throws IOException, GitAPIException {
        applyStash(repositoryRoot, stash);
        dropStash(repositoryRoot, stash);
    }

    static void dropStash(Path repositoryRoot, GitStashEntry stash) throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.stashDrop().setStashRef(stash.index()).call();
        }
    }

    static String stashText(Path repositoryRoot, GitStashEntry stash) throws IOException {
        return gitOutput(repositoryRoot, "show", "--stat", "--patch", stash.refName());
    }

    static String commitText(Path repositoryRoot, GitCommitEntry commit) throws IOException {
        return gitOutput(repositoryRoot, "show", "--stat", "--patch", commit.objectId());
    }

    static String reflogText(Path repositoryRoot, GitReflogEntryView entry) throws IOException {
        return gitOutput(repositoryRoot, "show", "--stat", "--patch", entry.newId());
    }

    static List<GitHistoryGraphEntry> historyGraphEntries(Path repositoryRoot, int maxCount) throws IOException {
        String output = gitOutput(repositoryRoot, "log", "--graph", "--decorate",
                "--pretty=format:%H\t%h\t%s\t%an", "-n", Integer.toString(maxCount));
        var result = new ArrayList<GitHistoryGraphEntry>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var matcher = FULL_HASH.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int hashStart = matcher.start();
            String graphPrefix = line.substring(0, hashStart);
            String[] parts = line.substring(hashStart).split("\t", 4);
            if (parts.length < 4) {
                continue;
            }
            result.add(new GitHistoryGraphEntry(
                    parts[0],
                    parts[1],
                    parts[2],
                    parts[3],
                    graphPrefix + parts[1] + " " + parts[2] + " [" + parts[3] + "]"));
        }
        return List.copyOf(result);
    }

    static void resolveConflictWithOurs(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        checkoutConflictStage(repositoryRoot, change.relativePath(), CheckoutCommand.Stage.OURS);
    }

    static void resolveConflictWithTheirs(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        checkoutConflictStage(repositoryRoot, change.relativePath(), CheckoutCommand.Stage.THEIRS);
    }

    static void resolveConflictWithBoth(Path repositoryRoot, GitFileChange change) throws IOException, GitAPIException {
        String merged = keepBothSides(Files.readString(change.absolutePath()));
        Files.writeString(change.absolutePath(), merged);
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.add().addFilepattern(change.relativePath()).call();
        }
    }

    private static void checkoutConflictStage(Path repositoryRoot, String relativePath, CheckoutCommand.Stage stage)
            throws IOException, GitAPIException {
        try (var git = Git.open(repositoryRoot.toFile())) {
            git.checkout()
                    .setStage(stage)
                    .addPath(relativePath)
                    .call();
            git.add().addFilepattern(relativePath).call();
        }
    }

    private static List<GitFileChange> tracked(java.util.Set<String> paths, Path root, GitSection section, String code) {
        var result = new ArrayList<GitFileChange>();
        for (String path : paths) {
            result.add(new GitFileChange(section, path, root.resolve(path).normalize(), code));
        }
        return result;
    }

    private static List<GitStashEntry> stashEntries(Git git) throws GitAPIException {
        var result = new ArrayList<GitStashEntry>();
        int index = 0;
        for (RevCommit commit : git.stashList().call()) {
            result.add(new GitStashEntry(index, "stash@{" + index + "}", commit.getId().name(), commit.getShortMessage()));
            index++;
        }
        return List.copyOf(result);
    }

    private static List<GitCommitEntry> recentCommits(Git git) throws GitAPIException {
        var result = new ArrayList<GitCommitEntry>();
        for (RevCommit commit : git.log().setMaxCount(8).call()) {
            String author = commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getName();
            result.add(new GitCommitEntry(commit.getId().name(), commit.getShortMessage(), author));
        }
        return List.copyOf(result);
    }

    private static List<GitReflogEntryView> reflogEntries(Git git) throws IOException {
        var reader = git.getRepository().getReflogReader("HEAD");
        if (reader == null) {
            return List.of();
        }
        var entries = reader.getReverseEntries(8);
        var result = new ArrayList<GitReflogEntryView>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            result.add(new GitReflogEntryView(i, entry.getNewId().name(), entry.getComment()));
        }
        return List.copyOf(result);
    }

    @SafeVarargs
    private static List<GitFileChange> sorted(List<GitFileChange>... lists) {
        var combined = new ArrayList<GitFileChange>();
        for (var list : lists) {
            combined.addAll(list);
        }
        combined.sort(Comparator.comparing(GitFileChange::relativePath));
        return List.copyOf(combined);
    }

    private static String safeBranch(Git git) {
        try {
            return git.getRepository().getBranch();
        } catch (IOException e) {
            return "";
        }
    }

    private static String safeFileContents(Path path, String relativePath) throws IOException {
        if (!Files.exists(path)) {
            return "No file contents available for " + relativePath;
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "Unable to read " + relativePath;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static AuthorIdentity authorIdentity(Git git) {
        StoredConfig config = git.getRepository().getConfig();
        String name = config.getString("user", null, "name");
        String email = config.getString("user", null, "email");
        if (name == null || name.isBlank()) {
            name = "SWIM";
        }
        if (email == null || email.isBlank()) {
            email = "swim@local";
        }
        return new AuthorIdentity(name, email);
    }

    private static String keepBothSides(String text) {
        var output = new StringBuilder();
        int state = 0;
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("<<<<<<< ")) {
                state = 1;
                continue;
            }
            if (line.equals("=======")) {
                state = 2;
                continue;
            }
            if (line.startsWith(">>>>>>> ")) {
                state = 0;
                continue;
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }

    private record AuthorIdentity(String name, String email) {
    }

    private static List<String> splitLines(String text) {
        var lines = new ArrayList<String>();
        for (String line : text.split("\\R", -1)) {
            if (line.isEmpty() && lines.isEmpty()) {
                continue;
            }
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private static int waitFor(Process process) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for git apply", e);
        }
    }

    private static String gitOutput(Path repositoryRoot, String... args) throws IOException {
        var command = new ArrayList<String>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }
        var process = new ProcessBuilder(command)
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = waitFor(process);
        if (exit != 0) {
            throw new IOException(output.isBlank() ? "git command failed" : output.strip());
        }
        return output;
    }
}
