package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.fisk.swim.api.SwimPanelResult;

final class GitStatusController {
    private enum Mode {
        STATUS,
        ACTIONS,
        HISTORY,
        DIFF,
        COMMIT,
        HUNK_EDIT,
        RESOLVER
    }

    private record StatusRow(GitSection section, Object value) {
        boolean isHeader() {
            return value == null;
        }

        GitFileChange fileChange() {
            return value instanceof GitFileChange change ? change : null;
        }

        GitStashEntry stashEntry() {
            return value instanceof GitStashEntry entry ? entry : null;
        }

        GitCommitEntry commitEntry() {
            return value instanceof GitCommitEntry entry ? entry : null;
        }

        GitReflogEntryView reflogEntry() {
            return value instanceof GitReflogEntryView entry ? entry : null;
        }
    }

    private Path _currentPath;
    private GitStatusSnapshot _snapshot = GitStatusSnapshot.noRepository();
    private Mode _mode = Mode.STATUS;
    private final EnumSet<GitSection> _collapsed = EnumSet.noneOf(GitSection.class);
    private int _selection;
    private int _scroll;
    private GitFileChange _diffChange;
    private GitDiffView _diffView;
    private String _diffTitle;
    private int _currentHunkIndex;
    private List<String> _diffLines = List.of();
    private List<GitHistoryGraphEntry> _historyEntries = List.of();
    private int _historySelection;
    private String _message;
    private final StringBuilder _commitMessage = new StringBuilder();
    private List<String> _editLines = List.of();
    private int _editRow;
    private int _editColumn;
    private GitPatchOperation _editOperation;
    private GitConflictResolverState _resolverState;

    void syncToPath(Path path) {
        _currentPath = path;
        refresh();
    }

    String title() {
        return switch (_mode) {
        case ACTIONS -> "Git Actions";
        case HISTORY -> "Git History";
        case DIFF -> _diffChange != null ? "Git Diff: " + _diffChange.relativePath()
                : _diffTitle == null ? "Git Diff" : _diffTitle;
        case COMMIT -> "Git Commit";
        case HUNK_EDIT -> _diffChange == null ? "Git Hunk Edit" : "Git Hunk Edit: " + _diffChange.relativePath();
        case RESOLVER -> _resolverState == null ? "Git Merge Resolver"
                : "Git Merge Resolver: " + _resolverState.path().getFileName();
        case STATUS -> _snapshot.repositoryRoot() == null ? "Git" : "Git: " + _snapshot.repositoryRoot().getFileName();
        };
    }

    List<String> render(int width, int height) {
        return switch (_mode) {
        case STATUS -> renderStatus(height);
        case ACTIONS -> renderActions(height);
        case HISTORY -> renderHistory(height);
        case DIFF -> renderDiff(height);
        case COMMIT -> renderCommit(height);
        case HUNK_EDIT -> renderHunkEdit(height);
        case RESOLVER -> renderResolver(height);
        };
    }

    SwimPanelResult handleInput(String input, int width, int height) {
        return switch (_mode) {
        case STATUS -> handleStatusInput(input);
        case ACTIONS -> handleActionsInput(input);
        case HISTORY -> handleHistoryInput(input);
        case DIFF -> handleDiffInput(input);
        case COMMIT -> handleCommitInput(input);
        case HUNK_EDIT -> handleHunkEditInput(input);
        case RESOLVER -> handleResolverInput(input);
        };
    }

    private List<String> renderStatus(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        if (!_snapshot.hasRepository()) {
            lines.add(" Open :git inside a repository-backed buffer. ");
            lines.add(" j/k move  r refresh ");
            appendBlankLines(lines, height);
            return lines;
        }
        var rows = statusRows();
        StatusRow selected = rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(_selection, rows.size() - 1)));
        lines.add(" " + _snapshot.branch() + "  " + (_message == null ? _snapshot.statusMessage() : _message));
        lines.add(" " + statusHelp(selected));
        if (rows.isEmpty()) {
            rows = List.of(new StatusRow(GitSection.UNSTAGED,
                    new GitFileChange(GitSection.UNSTAGED, "(clean)", _snapshot.repositoryRoot(), " ")));
        }
        clampSelection(rows.size());
        int bodyHeight = Math.max(0, height - 3);
        adjustScroll(bodyHeight, rows.size());
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            lines.add(index < rows.size() ? formatStatusRow(rows.get(index), index == _selection) : "");
        }
        return lines;
    }

    private List<String> renderActions(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" q close  l history  z stash  S stage-all  U unstage-all ");
        lines.add(" s stage  u unstage  x discard  d inspect  c commit ");
        lines.add(" a apply stash  P pop stash  D drop stash  m resolver ");
        appendBlankLines(lines, height);
        return lines;
    }

    private List<String> renderHistory(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" j/k move  Enter inspect  d inspect  r refresh  left status ");
        if (_historyEntries.isEmpty()) {
            lines.add(" No commits ");
            appendBlankLines(lines, height);
            return lines;
        }
        _historySelection = Math.max(0, Math.min(_historySelection, _historyEntries.size() - 1));
        int bodyHeight = Math.max(0, height - 2);
        adjustHistoryScroll(bodyHeight);
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            lines.add(index < _historyEntries.size()
                    ? (index == _historySelection ? "> " : "  ") + _historyEntries.get(index).displayLabel()
                    : "");
        }
        return lines;
    }

    private List<String> renderDiff(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        String hunkInfo = _diffView != null && _diffView.hasHunks()
                ? " hunk " + (_currentHunkIndex + 1) + "/" + _diffView.hunks().size()
                : "";
        lines.add(" left status  n/p hunk  s/u/x apply  e edit  Enter open " + hunkInfo);
        int bodyHeight = Math.max(0, height - 2);
        int maxScroll = Math.max(0, _diffLines.size() - bodyHeight);
        _scroll = Math.max(0, Math.min(_scroll, maxScroll));
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            lines.add(index < _diffLines.size() ? renderDiffLine(index) : "");
        }
        return lines;
    }

    private List<String> renderCommit(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" Enter commit  Backspace delete  type message ");
        lines.add("> " + _commitMessage);
        appendBlankLines(lines, height);
        return lines;
    }

    private List<String> renderHunkEdit(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" ctrl-s apply  ctrl-g cancel  arrows move  Enter newline ");
        int bodyHeight = Math.max(0, height - 2);
        int start = Math.max(0, Math.min(_scroll, Math.max(0, _editLines.size() - bodyHeight)));
        _scroll = start;
        for (int i = 0; i < bodyHeight; i++) {
            int index = start + i;
            if (index >= _editLines.size()) {
                lines.add("");
                continue;
            }
            String line = _editLines.get(index);
            if (index == _editRow) {
                int column = Math.max(0, Math.min(_editColumn, line.length()));
                line = line.substring(0, column) + "|" + line.substring(column);
            }
            lines.add(index == _editRow ? "> " + line : "  " + line);
        }
        return lines;
    }

    private List<String> renderResolver(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        if (_resolverState == null || _resolverState.blockCount() == 0) {
            lines.add(" No conflict blocks detected ");
            appendBlankLines(lines, height);
            return lines;
        }
        var block = _resolverState.selectedBlock();
        lines.add(" n/p block  o ours  t theirs  b both  a apply  left status ");
        lines.add(" Block " + (_resolverState.selection() + 1) + "/" + _resolverState.blockCount());
        appendSection(lines, "BASE", block.base().isEmpty() ? List.of("(not available in markers)") : block.base());
        appendSection(lines, "OURS", block.ours());
        appendSection(lines, "THEIRS", block.theirs());
        appendSection(lines, "RESULT", block.result());
        appendBlankLines(lines, height);
        return lines;
    }

    private SwimPanelResult handleStatusInput(String input) {
        _message = null;
        if (!_snapshot.hasRepository()) {
            return "r".equals(input) ? refreshResult() : SwimPanelResult.ignored();
        }
        var rows = statusRows();
        if (rows.isEmpty()) {
            return switch (input) {
            case "c" -> beginCommit();
            case "?", "space" -> openActionsMenu();
            case "l" -> openHistoryBrowser();
            case "r" -> refreshResult();
            default -> SwimPanelResult.ignored();
            };
        }
        clampSelection(rows.size());
        var row = rows.get(_selection);
        return switch (input) {
        case "j", "down" -> moveStatusSelection(rows.size(), 1);
        case "k", "up" -> moveStatusSelection(rows.size(), -1);
        case "tab", "space", "left", "right" -> toggleOrAdjustSection(row, input);
        case "enter" -> openSelected(row);
        case "r" -> refreshResult();
        case "s" -> stageSelected(row);
        case "u" -> unstageSelected(row);
        case "x" -> discardSelected(row);
        case "d" -> inspectSelected(row);
        case "?" -> openActionsMenu();
        case "l" -> openHistoryBrowser();
        case "c" -> beginCommit();
        case "o" -> resolveConflict(row, "ours");
        case "t" -> resolveConflict(row, "theirs");
        case "b" -> resolveConflict(row, "both");
        case "m" -> openResolver(row);
        case "a" -> applySelected(row);
        case "P" -> popSelected(row);
        case "D" -> dropSelected(row);
        case "S" -> stageAll();
        case "U" -> unstageAll();
        case "z" -> createStash();
        default -> SwimPanelResult.ignored();
        };
    }

    private SwimPanelResult handleActionsInput(String input) {
        if (isCloseInput(input)) {
            _mode = Mode.STATUS;
            return SwimPanelResult.success();
        }
        _mode = Mode.STATUS;
        return handleStatusInput(input);
    }

    private SwimPanelResult handleHistoryInput(String input) {
        if (isCloseInput(input)) {
            _mode = Mode.STATUS;
            return SwimPanelResult.success();
        }
        if ("r".equals(input)) {
            return openHistoryBrowser();
        }
        if (_historyEntries.isEmpty()) {
            return SwimPanelResult.ignored();
        }
        return switch (input) {
        case "j", "down" -> {
            _historySelection = Math.min(_historyEntries.size() - 1, _historySelection + 1);
            yield SwimPanelResult.success();
        }
        case "k", "up" -> {
            _historySelection = Math.max(0, _historySelection - 1);
            yield SwimPanelResult.success();
        }
        case "enter", "d" -> {
            try {
                yield showText("Git Commit: " + _historyEntries.get(_historySelection).shortId(),
                        GitStatusService.commitText(_snapshot.repositoryRoot(), new GitCommitEntry(
                                _historyEntries.get(_historySelection).objectId(),
                                _historyEntries.get(_historySelection).summary(),
                                _historyEntries.get(_historySelection).author())));
            } catch (IOException e) {
                yield actionFailed();
            }
        }
        default -> SwimPanelResult.ignored();
        };
    }

    private SwimPanelResult handleDiffInput(String input) {
        return switch (input) {
        case "j", "down" -> {
            _scroll++;
            yield SwimPanelResult.success();
        }
        case "k", "up" -> {
            _scroll = Math.max(0, _scroll - 1);
            yield SwimPanelResult.success();
        }
        case "n" -> {
            moveCurrentHunk(1);
            yield SwimPanelResult.success();
        }
        case "p" -> {
            moveCurrentHunk(-1);
            yield SwimPanelResult.success();
        }
        case "s" -> applyCurrentHunk(GitPatchOperation.STAGE_HUNK);
        case "u" -> applyCurrentHunk(GitPatchOperation.UNSTAGE_HUNK);
        case "x" -> applyCurrentHunk(GitPatchOperation.DISCARD_HUNK);
        case "e" -> beginHunkEdit();
        case "enter" -> _diffChange != null && java.nio.file.Files.exists(_diffChange.absolutePath())
                ? SwimPanelResult.success(_diffChange.absolutePath())
                : SwimPanelResult.success();
        default -> isCloseInput(input) ? closeToStatus() : SwimPanelResult.ignored();
        };
    }

    private SwimPanelResult handleCommitInput(String input) {
        if (isCloseInput(input)) {
            _mode = Mode.STATUS;
            return SwimPanelResult.success();
        }
        if ("enter".equals(input)) {
            String message = _commitMessage.toString().trim();
            if (message.isEmpty()) {
                _message = "Commit message cannot be empty";
                _mode = Mode.STATUS;
                return SwimPanelResult.successMessage(_message);
            }
            try {
                GitStatusService.commit(_snapshot.repositoryRoot(), message);
                _commitMessage.setLength(0);
                _mode = Mode.STATUS;
                refresh();
                return SwimPanelResult.successMessage("Committed changes");
            } catch (IOException | GitAPIException e) {
                _mode = Mode.STATUS;
                _message = "Commit failed";
                return SwimPanelResult.successMessage(_message);
            }
        }
        if ("backspace".equals(input)) {
            if (_commitMessage.length() > 0) {
                _commitMessage.deleteCharAt(_commitMessage.length() - 1);
            }
            return SwimPanelResult.success();
        }
        if ("space".equals(input)) {
            _commitMessage.append(' ');
            return SwimPanelResult.success();
        }
        if (input.length() == 1) {
            _commitMessage.append(input);
            return SwimPanelResult.success();
        }
        return SwimPanelResult.ignored();
    }

    private SwimPanelResult handleHunkEditInput(String input) {
        switch (input) {
        case "ctrl-s":
            try {
                GitStatusService.applyPatch(_snapshot.repositoryRoot(), currentEditedPatch(), _editOperation);
                refresh();
                rebuildDiffAfterPatch();
                return SwimPanelResult.successMessage("Applied edited hunk");
            } catch (IOException e) {
                return SwimPanelResult.successMessage(e.getMessage());
            }
        case "ctrl-g":
            resetHunkEdit();
            return SwimPanelResult.success();
        case "left":
            _editColumn = Math.max(0, _editColumn - 1);
            return SwimPanelResult.success();
        case "right":
            _editColumn = Math.min(currentEditLine().length(), _editColumn + 1);
            return SwimPanelResult.success();
        case "up":
            _editRow = Math.max(0, _editRow - 1);
            _editColumn = Math.min(currentEditLine().length(), _editColumn);
            ensureEditCursorVisible();
            return SwimPanelResult.success();
        case "down":
            _editRow = Math.min(_editLines.size() - 1, _editRow + 1);
            _editColumn = Math.min(currentEditLine().length(), _editColumn);
            ensureEditCursorVisible();
            return SwimPanelResult.success();
        case "backspace":
            deleteEditCharacter();
            return SwimPanelResult.success();
        case "enter":
            splitEditLine();
            ensureEditCursorVisible();
            return SwimPanelResult.success();
        case "tab":
            insertEditText("    ");
            return SwimPanelResult.success();
        case "space":
            insertEditText(" ");
            return SwimPanelResult.success();
        default:
            if (isCloseInput(input)) {
                resetHunkEdit();
                return SwimPanelResult.success();
            }
            if (input.length() == 1) {
                insertEditText(input);
                return SwimPanelResult.success();
            }
            return SwimPanelResult.ignored();
        }
    }

    private SwimPanelResult handleResolverInput(String input) {
        if (_resolverState == null) {
            return SwimPanelResult.ignored();
        }
        return switch (input) {
        case "n", "down" -> {
            _resolverState.nextBlock();
            yield SwimPanelResult.success();
        }
        case "p", "up" -> {
            _resolverState.previousBlock();
            yield SwimPanelResult.success();
        }
        case "o" -> {
            _resolverState.chooseOurs();
            yield SwimPanelResult.success();
        }
        case "t" -> {
            _resolverState.chooseTheirs();
            yield SwimPanelResult.success();
        }
        case "b" -> {
            _resolverState.chooseBoth();
            yield SwimPanelResult.success();
        }
        case "a" -> applyResolverResult();
        case "enter" -> SwimPanelResult.success(_resolverState.path());
        default -> isCloseInput(input) ? closeResolver() : SwimPanelResult.ignored();
        };
    }

    private SwimPanelResult refreshResult() {
        refresh();
        return SwimPanelResult.success();
    }

    private void refresh() {
        try {
            _snapshot = GitStatusService.snapshot(_currentPath);
            _message = null;
        } catch (IOException | GitAPIException e) {
            _snapshot = GitStatusSnapshot.noRepository();
            _message = "Git refresh failed";
        }
    }

    private List<StatusRow> statusRows() {
        var rows = new ArrayList<StatusRow>();
        appendSection(rows, GitSection.STAGED, _snapshot.staged());
        appendSection(rows, GitSection.UNSTAGED, _snapshot.unstaged());
        appendSection(rows, GitSection.UNTRACKED, _snapshot.untracked());
        appendSection(rows, GitSection.CONFLICTS, _snapshot.conflicts());
        appendSection(rows, GitSection.STASHES, _snapshot.stashes());
        appendSection(rows, GitSection.COMMITS, _snapshot.commits());
        appendSection(rows, GitSection.REFLOG, _snapshot.reflogEntries());
        return rows;
    }

    private void appendSection(List<StatusRow> rows, GitSection section, List<?> entries) {
        rows.add(new StatusRow(section, null));
        if (_collapsed.contains(section)) {
            return;
        }
        rows.addAll(entries.stream().map(entry -> new StatusRow(section, entry)).toList());
    }

    private String formatStatusRow(StatusRow row, boolean selected) {
        String prefix = selected ? "> " : "  ";
        if (row.isHeader()) {
            int count = sectionCount(row.section());
            String folded = _collapsed.contains(row.section()) ? ">" : "v";
            return prefix + folded + " " + row.section().title() + " (" + count + ")";
        }
        if (row.fileChange() != null) {
            return prefix + row.fileChange().displayLabel();
        }
        if (row.stashEntry() != null) {
            return prefix + row.stashEntry().displayLabel();
        }
        if (row.commitEntry() != null) {
            return prefix + row.commitEntry().displayLabel();
        }
        if (row.reflogEntry() != null) {
            return prefix + row.reflogEntry().displayLabel();
        }
        return prefix;
    }

    private int sectionCount(GitSection section) {
        return switch (section) {
        case STAGED -> _snapshot.staged().size();
        case UNSTAGED -> _snapshot.unstaged().size();
        case UNTRACKED -> _snapshot.untracked().size();
        case CONFLICTS -> _snapshot.conflicts().size();
        case STASHES -> _snapshot.stashes().size();
        case COMMITS -> _snapshot.commits().size();
        case REFLOG -> _snapshot.reflogEntries().size();
        };
    }

    private SwimPanelResult moveStatusSelection(int size, int delta) {
        _selection = Math.floorMod(_selection + delta, size);
        return SwimPanelResult.success();
    }

    private void clampSelection(int size) {
        _selection = Math.max(0, Math.min(_selection, Math.max(0, size - 1)));
    }

    private void adjustScroll(int bodyHeight, int rowsSize) {
        if (_selection < _scroll) {
            _scroll = _selection;
        } else if (_selection >= _scroll + bodyHeight) {
            _scroll = _selection - bodyHeight + 1;
        }
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, rowsSize - Math.max(1, bodyHeight))));
    }

    private void adjustHistoryScroll(int bodyHeight) {
        if (_historySelection < _scroll) {
            _scroll = _historySelection;
        } else if (_historySelection >= _scroll + bodyHeight) {
            _scroll = _historySelection - bodyHeight + 1;
        }
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, _historyEntries.size() - Math.max(1, bodyHeight))));
    }

    private SwimPanelResult toggleOrAdjustSection(StatusRow row, String input) {
        if (!row.isHeader()) {
            return SwimPanelResult.ignored();
        }
        if ("left".equals(input)) {
            _collapsed.add(row.section());
        } else if ("right".equals(input)) {
            _collapsed.remove(row.section());
        } else if (_collapsed.contains(row.section())) {
            _collapsed.remove(row.section());
        } else {
            _collapsed.add(row.section());
        }
        return SwimPanelResult.success();
    }

    private SwimPanelResult openSelected(StatusRow row) {
        if (row.isHeader()) {
            return toggleOrAdjustSection(row, "tab");
        }
        if (row.fileChange() != null) {
            if (java.nio.file.Files.exists(row.fileChange().absolutePath())) {
                return SwimPanelResult.success(row.fileChange().absolutePath());
            }
            return SwimPanelResult.successMessage("File is not present in the worktree");
        }
        return inspectSelected(row);
    }

    private SwimPanelResult stageSelected(StatusRow row) {
        var change = row.fileChange();
        if (change == null) {
            return SwimPanelResult.ignored();
        }
        try {
            GitStatusService.stage(_snapshot.repositoryRoot(), change);
            refresh();
            return SwimPanelResult.successMessage("Staged " + change.relativePath());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult unstageSelected(StatusRow row) {
        var change = row.fileChange();
        if (change == null) {
            return SwimPanelResult.ignored();
        }
        if (change.section() != GitSection.STAGED) {
            return SwimPanelResult.successMessage("Select a staged file to unstage");
        }
        try {
            GitStatusService.unstage(_snapshot.repositoryRoot(), change);
            refresh();
            return SwimPanelResult.successMessage("Unstaged " + change.relativePath());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult discardSelected(StatusRow row) {
        var change = row.fileChange();
        if (change == null) {
            return SwimPanelResult.ignored();
        }
        if (change.section() == GitSection.STAGED) {
            return SwimPanelResult.successMessage("Unstage the file before discarding it");
        }
        if (change.section() == GitSection.CONFLICTS) {
            return SwimPanelResult.successMessage("Use o, t, or b to resolve the conflict");
        }
        try {
            GitStatusService.discard(_snapshot.repositoryRoot(), change);
            refresh();
            return SwimPanelResult.successMessage("Discarded " + change.relativePath());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult inspectSelected(StatusRow row) {
        try {
            if (row.fileChange() != null) {
                return showDiff(row.fileChange());
            }
            if (row.stashEntry() != null) {
                return showText("Git Stash: " + row.stashEntry().refName(),
                        GitStatusService.stashText(_snapshot.repositoryRoot(), row.stashEntry()));
            }
            if (row.commitEntry() != null) {
                return showText("Git Commit: " + row.commitEntry().shortId(),
                        GitStatusService.commitText(_snapshot.repositoryRoot(), row.commitEntry()));
            }
            if (row.reflogEntry() != null) {
                return showText("Git Reflog: HEAD@{" + row.reflogEntry().index() + "}",
                        GitStatusService.reflogText(_snapshot.repositoryRoot(), row.reflogEntry()));
            }
            return SwimPanelResult.ignored();
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult showDiff(GitFileChange change) throws IOException, GitAPIException {
        _diffChange = change;
        _diffView = GitStatusService.buildDiffView(_snapshot.repositoryRoot(), change);
        _diffLines = _diffView.lines();
        _diffTitle = "Git Diff: " + change.relativePath();
        _currentHunkIndex = 0;
        _mode = Mode.DIFF;
        _scroll = 0;
        return SwimPanelResult.success();
    }

    private SwimPanelResult showText(String title, String text) {
        _diffChange = null;
        _diffView = new GitDiffView("", List.of(text.split("\\R", -1)), List.of(), null);
        _diffLines = _diffView.lines();
        _diffTitle = title;
        _currentHunkIndex = 0;
        _mode = Mode.DIFF;
        _scroll = 0;
        return SwimPanelResult.success();
    }

    private SwimPanelResult beginCommit() {
        if (_snapshot.repositoryRoot() == null) {
            return SwimPanelResult.ignored();
        }
        _commitMessage.setLength(0);
        _mode = Mode.COMMIT;
        return SwimPanelResult.success();
    }

    private SwimPanelResult openActionsMenu() {
        _mode = Mode.ACTIONS;
        return SwimPanelResult.success();
    }

    private SwimPanelResult openHistoryBrowser() {
        try {
            _historyEntries = GitStatusService.historyGraphEntries(_snapshot.repositoryRoot(), 30);
            _historySelection = 0;
            _scroll = 0;
            _mode = Mode.HISTORY;
            return SwimPanelResult.success();
        } catch (IOException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult resolveConflict(StatusRow row, String resolution) {
        var change = row.fileChange();
        if (change == null) {
            return SwimPanelResult.ignored();
        }
        if (change.section() != GitSection.CONFLICTS) {
            return SwimPanelResult.successMessage("Select a conflicted file");
        }
        try {
            switch (resolution) {
            case "ours" -> GitStatusService.resolveConflictWithOurs(_snapshot.repositoryRoot(), change);
            case "theirs" -> GitStatusService.resolveConflictWithTheirs(_snapshot.repositoryRoot(), change);
            case "both" -> GitStatusService.resolveConflictWithBoth(_snapshot.repositoryRoot(), change);
            default -> {
                return SwimPanelResult.ignored();
            }
            }
            refresh();
            return SwimPanelResult.successMessage("Resolved " + change.relativePath());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult openResolver(StatusRow row) {
        var change = row.fileChange();
        if (change == null) {
            return SwimPanelResult.ignored();
        }
        if (change.section() != GitSection.CONFLICTS) {
            return SwimPanelResult.successMessage("Select a conflicted file");
        }
        try {
            _resolverState = GitConflictResolverState.parse(change.absolutePath());
            _mode = Mode.RESOLVER;
            return SwimPanelResult.success();
        } catch (IOException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult applySelected(StatusRow row) {
        var stash = row.stashEntry();
        if (stash == null) {
            return SwimPanelResult.ignored();
        }
        try {
            GitStatusService.applyStash(_snapshot.repositoryRoot(), stash);
            refresh();
            return SwimPanelResult.successMessage("Applied " + stash.refName());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult popSelected(StatusRow row) {
        var stash = row.stashEntry();
        if (stash == null) {
            return SwimPanelResult.ignored();
        }
        try {
            GitStatusService.popStash(_snapshot.repositoryRoot(), stash);
            refresh();
            return SwimPanelResult.successMessage("Popped " + stash.refName());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult dropSelected(StatusRow row) {
        var stash = row.stashEntry();
        if (stash == null) {
            return SwimPanelResult.ignored();
        }
        try {
            GitStatusService.dropStash(_snapshot.repositoryRoot(), stash);
            refresh();
            return SwimPanelResult.successMessage("Dropped " + stash.refName());
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult stageAll() {
        try {
            GitStatusService.stageAll(_snapshot.repositoryRoot());
            refresh();
            return SwimPanelResult.successMessage("Staged all changes");
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult unstageAll() {
        try {
            GitStatusService.unstageAll(_snapshot.repositoryRoot());
            refresh();
            return SwimPanelResult.successMessage("Unstaged all changes");
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult createStash() {
        try {
            GitStatusService.createStash(_snapshot.repositoryRoot(), "swim stash");
            refresh();
            return SwimPanelResult.successMessage("Created stash");
        } catch (IOException | GitAPIException e) {
            return actionFailed();
        }
    }

    private String renderDiffLine(int lineIndex) {
        String line = _diffLines.get(lineIndex);
        if (_diffView == null || !_diffView.hasHunks()) {
            return line;
        }
        var hunk = _diffView.hunks().get(_currentHunkIndex);
        return lineIndex >= hunk.displayStartLine() && lineIndex <= hunk.displayEndLine() ? "> " + line : "  " + line;
    }

    private void moveCurrentHunk(int delta) {
        if (_diffView == null || !_diffView.hasHunks()) {
            return;
        }
        _currentHunkIndex = Math.floorMod(_currentHunkIndex + delta, _diffView.hunks().size());
        var hunk = _diffView.hunks().get(_currentHunkIndex);
        _scroll = Math.max(0, hunk.displayStartLine());
    }

    private SwimPanelResult applyCurrentHunk(GitPatchOperation operation) {
        if (_diffView == null || !_diffView.hasHunks()) {
            return SwimPanelResult.successMessage("No hunks available");
        }
        try {
            var hunk = _diffView.hunks().get(_currentHunkIndex);
            switch (operation) {
            case STAGE_HUNK -> {
                if (_diffChange.section() != GitSection.UNSTAGED) {
                    return SwimPanelResult.successMessage("Stage hunks from the unstaged diff");
                }
                GitStatusService.stageHunk(_snapshot.repositoryRoot(), hunk);
            }
            case UNSTAGE_HUNK -> {
                if (_diffChange.section() != GitSection.STAGED) {
                    return SwimPanelResult.successMessage("Unstage hunks from the staged diff");
                }
                GitStatusService.unstageHunk(_snapshot.repositoryRoot(), hunk);
            }
            case DISCARD_HUNK -> {
                if (_diffChange.section() != GitSection.UNSTAGED) {
                    return SwimPanelResult.successMessage("Discard hunks from the unstaged diff");
                }
                GitStatusService.discardHunk(_snapshot.repositoryRoot(), hunk);
            }
            }
            refresh();
            rebuildDiffAfterPatch();
            return SwimPanelResult.successMessage(switch (operation) {
            case STAGE_HUNK -> "Staged current hunk";
            case UNSTAGE_HUNK -> "Unstaged current hunk";
            case DISCARD_HUNK -> "Discarded current hunk";
            });
        } catch (IOException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult beginHunkEdit() {
        if (_diffView == null || !_diffView.hasHunks() || _diffView.defaultEditOperation() == null) {
            return SwimPanelResult.successMessage("Patch editing is unavailable for this diff");
        }
        _editLines = new ArrayList<>(List.of(_diffView.hunks().get(_currentHunkIndex).patchText().split("\\R", -1)));
        _editRow = 0;
        _editColumn = 0;
        _editOperation = _diffView.defaultEditOperation();
        _scroll = 0;
        _mode = Mode.HUNK_EDIT;
        return SwimPanelResult.success();
    }

    private void rebuildDiffAfterPatch() {
        _mode = Mode.STATUS;
        if (_diffChange == null) {
            return;
        }
        try {
            _diffView = GitStatusService.buildDiffView(_snapshot.repositoryRoot(), _diffChange);
            _diffLines = _diffView.lines();
            _diffTitle = "Git Diff: " + _diffChange.relativePath();
            _currentHunkIndex = 0;
            _mode = _diffView.hasHunks() ? Mode.DIFF : Mode.STATUS;
        } catch (IOException | GitAPIException e) {
            _mode = Mode.STATUS;
            _message = "Diff refresh failed";
        }
    }

    private void insertEditText(String text) {
        var lines = new ArrayList<>(_editLines);
        String line = currentEditLine();
        lines.set(_editRow, line.substring(0, _editColumn) + text + line.substring(_editColumn));
        _editLines = List.copyOf(lines);
        _editColumn += text.length();
        ensureEditCursorVisible();
    }

    private void splitEditLine() {
        var lines = new ArrayList<>(_editLines);
        String line = currentEditLine();
        lines.set(_editRow, line.substring(0, _editColumn));
        lines.add(_editRow + 1, line.substring(_editColumn));
        _editLines = List.copyOf(lines);
        _editRow++;
        _editColumn = 0;
    }

    private void deleteEditCharacter() {
        var lines = new ArrayList<>(_editLines);
        if (_editColumn > 0) {
            String line = currentEditLine();
            lines.set(_editRow, line.substring(0, _editColumn - 1) + line.substring(_editColumn));
            _editLines = List.copyOf(lines);
            _editColumn--;
            ensureEditCursorVisible();
            return;
        }
        if (_editRow == 0) {
            return;
        }
        String previous = lines.get(_editRow - 1);
        String current = lines.get(_editRow);
        lines.set(_editRow - 1, previous + current);
        lines.remove(_editRow);
        _editLines = List.copyOf(lines);
        _editRow--;
        _editColumn = previous.length();
        ensureEditCursorVisible();
    }

    private String currentEditLine() {
        return _editLines.get(_editRow);
    }

    private String currentEditedPatch() {
        String text = String.join("\n", _editLines);
        return text.endsWith("\n") ? text : text + "\n";
    }

    private void ensureEditCursorVisible() {
        if (_editRow < _scroll) {
            _scroll = _editRow;
        } else if (_editRow >= _scroll + 8) {
            _scroll = _editRow - 7;
        }
    }

    private SwimPanelResult applyResolverResult() {
        if (_resolverState == null) {
            return SwimPanelResult.ignored();
        }
        try {
            _resolverState.writeResolvedFile();
            var relativePath = _snapshot.conflicts().stream()
                    .map(GitFileChange::relativePath)
                    .filter(path -> _resolverState.path().endsWith(path))
                    .findFirst()
                    .orElse(_resolverState.path().getFileName().toString());
            GitStatusService.resolveConflictWithBoth(_snapshot.repositoryRoot(),
                    new GitFileChange(GitSection.CONFLICTS, relativePath, _resolverState.path(), "U"));
            _mode = Mode.STATUS;
            _resolverState = null;
            refresh();
            return SwimPanelResult.successMessage("Applied resolver result");
        } catch (IOException | GitAPIException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult actionFailed() {
        _message = "Git action failed";
        return SwimPanelResult.successMessage(_message);
    }

    private String statusHelp(StatusRow row) {
        if (row == null || row.isHeader()) {
            return "j/k move  Tab fold  ? actions  l history  S stage-all  U unstage-all  z stash";
        }
        if (row.fileChange() != null) {
            return switch (row.fileChange().section()) {
            case STAGED -> "Enter open  d diff  u unstage  U unstage-all";
            case UNSTAGED -> "Enter open  d diff  s stage  x discard  S stage-all";
            case UNTRACKED -> "Enter open  s stage  x discard  S stage-all";
            case CONFLICTS -> "Enter open  o/t/b resolve  m resolver";
            case STASHES, COMMITS, REFLOG -> "Enter inspect";
            };
        }
        if (row.stashEntry() != null) {
            return "Enter/d inspect  a apply  P pop  D drop";
        }
        if (row.commitEntry() != null) {
            return "Enter/d inspect commit";
        }
        if (row.reflogEntry() != null) {
            return "Enter/d inspect reflog entry";
        }
        return "j/k move  Tab fold  Enter open";
    }

    private SwimPanelResult closeToStatus() {
        _mode = Mode.STATUS;
        _scroll = 0;
        return SwimPanelResult.success();
    }

    private SwimPanelResult closeResolver() {
        _mode = Mode.STATUS;
        _resolverState = null;
        return SwimPanelResult.success();
    }

    private void resetHunkEdit() {
        _mode = Mode.DIFF;
        _editLines = List.of();
        _editRow = 0;
        _editColumn = 0;
        _scroll = 0;
    }

    private static boolean isCloseInput(String input) {
        return "q".equals(input) || "esc".equals(input) || "left".equals(input) || "backspace".equals(input);
    }

    private static void appendBlankLines(List<String> lines, int height) {
        while (lines.size() < Math.max(1, height)) {
            lines.add("");
        }
    }

    private static void appendSection(List<String> lines, String title, List<String> content) {
        lines.add(" " + title);
        if (content.isEmpty()) {
            lines.add("   (empty)");
            return;
        }
        for (String line : content) {
            lines.add("   " + line);
        }
    }
}
