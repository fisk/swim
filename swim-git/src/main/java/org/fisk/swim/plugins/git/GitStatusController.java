package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.fisk.swim.api.SwimKeyBindingHint;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimTextSpan;

final class GitStatusController {
    private enum Mode {
        STATUS,
        ACTIONS,
        HISTORY,
        REBASE,
        DIFF,
        COMMIT,
        HUNK_EDIT,
        RESOLVER,
        PULL_REQUESTS,
        PULL_REVIEW
    }

    private enum ReviewMode {
        SUMMARY("summary"),
        UNIFIED("unified"),
        SPLIT("split"),
        COMMENTS("comments");

        private final String _label;

        ReviewMode(String label) {
            _label = label;
        }

        String label() {
            return _label;
        }
    }

    private enum PullFilterField {
        NAME("Filter Name"),
        LABELS("Filter Labels"),
        AUTHOR("Filter Author");

        private final String _label;

        PullFilterField(String label) {
            _label = label;
        }

        String label() {
            return _label;
        }
    }

    private static final String C_TEXT = "#dce6ef";
    private static final String C_MUTED = "#8ca1b3";
    private static final String C_SUBTLE = "#61788d";
    private static final String C_PANEL = "#111a23";
    private static final String C_ELEVATED = "#15202b";
    private static final String C_ACCENT = "#5ec4ff";
    private static final String C_GOLD = "#ffb454";
    private static final String C_GREEN = "#7ee787";
    private static final String C_RED = "#ff6b6b";
    private static final String C_PURPLE = "#d2a8ff";
    private static final String C_COMMIT_HASH = "#f7a94b";
    private static final String C_PULL_REQUEST_NUMBER = "#3ddbd9";
    private static final String C_LABEL = "#a6e3a1";
    private static final String C_FILTER_FIELD = "#ffb454";
    private static final String C_FILTER_NEGATED = "#ff6b6b";
    private static final String C_INSERT_BG = "#173d22";
    private static final String C_INSERT_FG = "#d9ffe2";
    private static final String C_DELETE_BG = "#4a2020";
    private static final String C_DELETE_FG = "#ffd8d8";
    private static final String C_SELECTION_BG = "#20405a";
    private static final String REVIEW_SEPARATOR = "│";

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
    private List<GitRebaseEntry> _rebaseEntries = List.of();
    private int _rebaseSelection;
    private String _rebaseUpstreamId;
    private String _rebaseUpstreamLabel;
    private String _message;
    private final StringBuilder _commitMessage = new StringBuilder();
    private List<String> _editLines = List.of();
    private int _editRow;
    private int _editColumn;
    private GitPatchOperation _editOperation;
    private GitConflictResolverState _resolverState;
    private List<GitHubRepository> _pullRepositories = List.of();
    private int _pullRepositorySelection;
    private List<GitHubPullRequest> _allPullRequests = List.of();
    private List<GitHubPullRequest> _pullRequests = List.of();
    private int _pullRequestSelection;
    private boolean _pullRequestFiltering;
    private PullFilterField _pullFilterEditingField = PullFilterField.NAME;
    private String _pullFilterName = "";
    private String _pullFilterLabels = "";
    private String _pullFilterAuthor = "";
    private List<GitPullRequestSavedView> _pullSavedViews = List.of();
    private String _pullActiveViewName;
    private boolean _pullViewNameEditing;
    private final StringBuilder _pullViewNameInput = new StringBuilder();
    private GitHubRepository _reviewRepository;
    private GitHubPullRequest _reviewPullRequest;
    private List<GitHubPullRequestFile> _reviewFiles = List.of();
    private List<GitHubReviewComment> _reviewComments = List.of();
    private int _reviewFileSelection;
    private int _reviewScroll;
    private ReviewMode _reviewMode = ReviewMode.UNIFIED;

    void syncToPath(Path path) {
        _currentPath = path;
        Path repositoryRoot = GitRepositoryLocator.findRepositoryRoot(path);
        if (Objects.equals(repositoryRoot, _snapshot.repositoryRoot())) {
            return;
        }
        refresh();
    }

    String title() {
        return switch (_mode) {
        case ACTIONS -> "Git Actions";
        case HISTORY -> "Git History";
        case REBASE -> "Git Interactive Rebase";
        case DIFF -> _diffChange != null ? "Git Diff: " + _diffChange.relativePath()
                : _diffTitle == null ? "Git Diff" : _diffTitle;
        case COMMIT -> "Git Commit";
        case HUNK_EDIT -> _diffChange == null ? "Git Hunk Edit" : "Git Hunk Edit: " + _diffChange.relativePath();
        case RESOLVER -> _resolverState == null ? "Git Merge Resolver"
                : "Git Merge Resolver: " + _resolverState.path().getFileName();
        case PULL_REQUESTS -> "GitHub Pull Requests";
        case PULL_REVIEW -> "GitHub Pull Request Review";
        case STATUS -> _snapshot.repositoryRoot() == null ? "Git" : "Git: " + _snapshot.repositoryRoot().getFileName();
        };
    }

    List<SwimKeyBindingHint> keyBindingHints() {
        return GitKeyBindings.hints(bindingView());
    }

    List<String> render(int width, int height) {
        return renderRich(width, height).stream()
                .map(SwimPanelLine::text)
                .toList();
    }

    List<SwimPanelLine> renderRich(int width, int height) {
        if (_mode == Mode.PULL_REVIEW) {
            return renderPullReviewRich(width, height);
        }
        return switch (_mode) {
        case STATUS -> renderStatusRich(height);
        case ACTIONS -> plainLines(renderActions(height));
        case HISTORY -> renderHistoryRich(height);
        case REBASE -> renderRebaseRich(height);
        case DIFF -> plainLines(renderDiff(height));
        case COMMIT -> plainLines(renderCommit(height));
        case HUNK_EDIT -> plainLines(renderHunkEdit(height));
        case RESOLVER -> plainLines(renderResolver(height));
        case PULL_REQUESTS -> renderPullRequestsRich(height);
        case PULL_REVIEW -> renderPullReviewRich(width, height);
        };
    }

    SwimPanelResult handleInput(String input, int width, int height) {
        return switch (_mode) {
        case STATUS -> handleStatusInput(input);
        case ACTIONS -> handleActionsInput(input);
        case HISTORY -> handleHistoryInput(input);
        case REBASE -> handleRebaseInput(input);
        case DIFF -> handleDiffInput(input);
        case COMMIT -> handleCommitInput(input);
        case HUNK_EDIT -> handleHunkEditInput(input);
        case RESOLVER -> handleResolverInput(input);
        case PULL_REQUESTS -> handlePullRequestsInput(input);
        case PULL_REVIEW -> handlePullReviewInput(input);
        };
    }

    private List<String> renderStatus(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        if (!_snapshot.hasRepository()) {
            lines.add(" Open :git inside a repository-backed buffer. ");
            lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.STATUS));
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

    private List<SwimPanelLine> renderStatusRich(int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(SwimPanelLine.plain(title()));
        if (!_snapshot.hasRepository()) {
            lines.add(rich(span(" Open :git inside a repository-backed buffer. ", C_MUTED, C_PANEL)));
            lines.add(rich(span(" " + GitKeyBindings.helpLine(GitKeyBindings.View.STATUS), C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        var rows = statusRows();
        StatusRow selected = rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(_selection, rows.size() - 1)));
        lines.add(statusBranchLine());
        lines.add(rich(span(" " + statusHelp(selected), C_MUTED, C_ELEVATED)));
        if (rows.isEmpty()) {
            rows = List.of(new StatusRow(GitSection.UNSTAGED,
                    new GitFileChange(GitSection.UNSTAGED, "(clean)", _snapshot.repositoryRoot(), " ")));
        }
        clampSelection(rows.size());
        int bodyHeight = Math.max(0, height - 3);
        adjustScroll(bodyHeight, rows.size());
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            lines.add(index < rows.size() ? formatStatusRowRich(rows.get(index), index == _selection)
                    : SwimPanelLine.plain(""));
        }
        return lines;
    }

    private SwimPanelLine statusBranchLine() {
        String message = _message == null ? _snapshot.statusMessage() : _message;
        String messageColor = _message == null && _snapshot.isClean() ? C_GREEN : _message == null ? C_GOLD : C_RED;
        return rich(
                span(" ", C_MUTED, C_PANEL),
                span(_snapshot.branch(), C_ACCENT, C_PANEL),
                span("  ", C_MUTED, C_PANEL),
                span(message, messageColor, C_PANEL));
    }

    private List<String> renderActions(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.ACTIONS));
        appendBlankLines(lines, height);
        return lines;
    }

    private List<String> renderHistory(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.HISTORY));
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

    private List<SwimPanelLine> renderHistoryRich(int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(SwimPanelLine.plain(title()));
        lines.add(rich(span(" " + GitKeyBindings.helpLine(GitKeyBindings.View.HISTORY), C_MUTED, C_ELEVATED)));
        if (_historyEntries.isEmpty()) {
            lines.add(rich(span(" No commits ", C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        _historySelection = Math.max(0, Math.min(_historySelection, _historyEntries.size() - 1));
        int bodyHeight = Math.max(0, height - 2);
        adjustHistoryScroll(bodyHeight);
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            if (index < _historyEntries.size()) {
                boolean selected = index == _historySelection;
                GitHistoryGraphEntry entry = _historyEntries.get(index);
                lines.add(highlightedTokenLine(selected ? "> " : "  ", entry.displayLabel(), entry.shortId(),
                        selected ? C_TEXT : C_MUTED, C_COMMIT_HASH, selected ? C_SELECTION_BG : C_PANEL));
            } else {
                lines.add(SwimPanelLine.plain(""));
            }
        }
        return lines;
    }

    private List<String> renderRebase(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" upstream " + (_rebaseUpstreamLabel == null ? "(none)" : _rebaseUpstreamLabel));
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.REBASE));
        int bodyHeight = Math.max(0, height - 3);
        if (_rebaseEntries.isEmpty()) {
            lines.add(" No commits to rebase ");
            appendBlankLines(lines, height);
            return lines;
        }
        _rebaseSelection = Math.max(0, Math.min(_rebaseSelection, _rebaseEntries.size() - 1));
        if (_rebaseSelection < _scroll) {
            _scroll = _rebaseSelection;
        } else if (_rebaseSelection >= _scroll + bodyHeight) {
            _scroll = _rebaseSelection - bodyHeight + 1;
        }
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, _rebaseEntries.size() - Math.max(1, bodyHeight))));
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            lines.add(index < _rebaseEntries.size()
                    ? (index == _rebaseSelection ? "> " : "  ") + _rebaseEntries.get(index).displayLabel()
                    : "");
        }
        return lines;
    }

    private List<SwimPanelLine> renderRebaseRich(int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(SwimPanelLine.plain(title()));
        String upstreamLabel = _rebaseUpstreamLabel == null ? "(none)" : _rebaseUpstreamLabel;
        lines.add(highlightedTokenLine(" upstream ", upstreamLabel,
                _rebaseUpstreamLabel == null ? "" : firstToken(upstreamLabel), C_MUTED,
                C_COMMIT_HASH, C_PANEL));
        lines.add(rich(span(" " + GitKeyBindings.helpLine(GitKeyBindings.View.REBASE), C_MUTED, C_ELEVATED)));
        int bodyHeight = Math.max(0, height - 3);
        if (_rebaseEntries.isEmpty()) {
            lines.add(rich(span(" No commits to rebase ", C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        _rebaseSelection = Math.max(0, Math.min(_rebaseSelection, _rebaseEntries.size() - 1));
        if (_rebaseSelection < _scroll) {
            _scroll = _rebaseSelection;
        } else if (_rebaseSelection >= _scroll + bodyHeight) {
            _scroll = _rebaseSelection - bodyHeight + 1;
        }
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, _rebaseEntries.size() - Math.max(1, bodyHeight))));
        for (int i = 0; i < bodyHeight; i++) {
            int index = _scroll + i;
            if (index < _rebaseEntries.size()) {
                boolean selected = index == _rebaseSelection;
                GitRebaseEntry entry = _rebaseEntries.get(index);
                lines.add(highlightedTokenLine(selected ? "> " : "  ", entry.displayLabel(), entry.shortId(),
                        selected ? C_TEXT : C_MUTED, C_COMMIT_HASH, selected ? C_SELECTION_BG : C_PANEL));
            } else {
                lines.add(SwimPanelLine.plain(""));
            }
        }
        return lines;
    }

    private List<String> renderDiff(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        String hunkInfo = _diffView != null && _diffView.hasHunks()
                ? " hunk " + (_currentHunkIndex + 1) + "/" + _diffView.hunks().size()
                : "";
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.DIFF) + hunkInfo);
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
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.COMMIT));
        lines.add("> " + _commitMessage);
        appendBlankLines(lines, height);
        return lines;
    }

    private List<String> renderHunkEdit(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.HUNK_EDIT));
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
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.RESOLVER));
        lines.add(" Block " + (_resolverState.selection() + 1) + "/" + _resolverState.blockCount());
        appendSection(lines, "BASE", block.base().isEmpty() ? List.of("(not available in markers)") : block.base());
        appendSection(lines, "OURS", block.ours());
        appendSection(lines, "THEIRS", block.theirs());
        appendSection(lines, "RESULT", block.result());
        appendBlankLines(lines, height);
        return lines;
    }

    private List<String> renderPullRequests(int height) {
        var lines = new ArrayList<String>();
        lines.add(title());
        lines.add(" " + GitKeyBindings.helpLine(GitKeyBindings.View.PULL_REQUESTS));
        lines.add(" repos: " + pullRepositoryListLabel());
        String remote = currentPullRepository() == null ? "no GitHub remote" : currentPullRepository().label();
        lines.add(" repo: " + remote
                + "  name: " + (_pullFilterName.isBlank() ? "(any)" : _pullFilterName)
                + "  labels: " + (_pullFilterLabels.isBlank() ? "(any)" : _pullFilterLabels)
                + "  author: " + (_pullFilterAuthor.isBlank() ? "(any)" : _pullFilterAuthor));
        int bodyHeight = Math.max(0, height - lines.size());
        if (_pullRepositories.isEmpty()) {
            lines.add(" No GitHub remotes found ");
            appendBlankLines(lines, height);
            return lines;
        }
        if (_pullRequests.isEmpty()) {
            lines.add(" No pull requests ");
            appendBlankLines(lines, height);
            return lines;
        }
        _pullRequestSelection = Math.max(0, Math.min(_pullRequestSelection, _pullRequests.size() - 1));
        int start = Math.max(0, Math.min(_scroll, Math.max(0, _pullRequests.size() - bodyHeight)));
        _scroll = start;
        for (int i = 0; i < bodyHeight; i++) {
            int index = start + i;
            lines.add(index < _pullRequests.size()
                    ? (index == _pullRequestSelection ? "> " : "  ") + _pullRequests.get(index).displayLabel()
                    : "");
        }
        return lines;
    }

    private List<SwimPanelLine> renderPullRequestsRich(int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(SwimPanelLine.plain(title()));
        lines.add(rich(span(" " + GitKeyBindings.helpLine(GitKeyBindings.View.PULL_REQUESTS), C_MUTED, C_ELEVATED)));
        lines.add(pullRepositoryLine());
        lines.add(pullFilterStatusLine());
        lines.add(pullSavedViewLine());
        int bodyHeight = Math.max(0, height - lines.size());
        if (_pullRepositories.isEmpty()) {
            lines.add(rich(span(" No GitHub remotes found ", C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        if (_pullRequests.isEmpty()) {
            String message = _allPullRequests.isEmpty() ? " No pull requests " : " No pull requests match the filter ";
            lines.add(rich(span(message, C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        _pullRequestSelection = Math.max(0, Math.min(_pullRequestSelection, _pullRequests.size() - 1));
        int start = Math.max(0, Math.min(_scroll, Math.max(0, _pullRequests.size() - bodyHeight)));
        _scroll = start;
        for (int i = 0; i < bodyHeight; i++) {
            int index = start + i;
            if (index < _pullRequests.size()) {
                boolean selected = index == _pullRequestSelection;
                GitHubPullRequest pullRequest = _pullRequests.get(index);
                lines.add(pullRequestLine(selected ? "> " : "  ", pullRequest,
                        selected ? C_TEXT : C_MUTED, selected ? C_SELECTION_BG : C_PANEL));
            } else {
                lines.add(SwimPanelLine.plain(""));
            }
        }
        return lines;
    }

    private List<SwimPanelLine> renderPullReviewRich(int width, int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(SwimPanelLine.plain(title()));
        if (height <= 1) {
            return lines;
        }
        if (_reviewPullRequest == null) {
            lines.add(rich(span(" No pull request selected ", C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        if (_reviewFiles.isEmpty()) {
            lines.add(rich(span(" No files changed ", C_MUTED, C_PANEL)));
            appendBlankRichLines(lines, height);
            return lines;
        }
        _reviewFileSelection = Math.max(0, Math.min(_reviewFileSelection, _reviewFiles.size() - 1));
        int sidebarWidth = reviewSidebarWidth(width);
        int bodyWidth = Math.max(0, width - sidebarWidth - 1);
        int bodyRows = Math.max(0, height - 1);
        List<SwimPanelLine> sidebar = reviewSidebar(sidebarWidth, bodyRows);
        List<SwimPanelLine> body = reviewBody(bodyWidth, bodyRows);
        for (int row = 0; row < bodyRows; row++) {
            lines.add(joinReviewPanes(
                    row < sidebar.size() ? sidebar.get(row) : paddedLine("", sidebarWidth, C_MUTED, C_PANEL),
                    row < body.size() ? body.get(row) : paddedLine("", bodyWidth, C_MUTED, C_PANEL),
                    sidebarWidth));
        }
        appendBlankRichLines(lines, height);
        return lines;
    }

    private List<SwimPanelLine> reviewSidebar(int width, int height) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(paddedLine(" Files (" + _reviewFiles.size() + ") ", width, C_TEXT, C_ELEVATED));
        int rows = Math.max(0, height - 1);
        int start = Math.max(0, Math.min(_reviewFileSelection - Math.max(0, rows / 2),
                Math.max(0, _reviewFiles.size() - rows)));
        for (int row = 0; row < rows; row++) {
            int index = start + row;
            if (index >= _reviewFiles.size()) {
                lines.add(paddedLine("", width, C_MUTED, C_PANEL));
                continue;
            }
            GitHubPullRequestFile file = _reviewFiles.get(index);
            boolean selected = index == _reviewFileSelection;
            String label = (selected ? "> " : "  ") + file.path();
            String stats = " +" + file.additions() + " -" + file.deletions();
            String text = label + (width > 28 ? stats : "");
            lines.add(paddedLine(text, width, selected ? C_TEXT : C_MUTED, selected ? C_SELECTION_BG : C_PANEL));
        }
        return lines;
    }

    private List<SwimPanelLine> reviewBody(int width, int height) {
        var lines = new ArrayList<SwimPanelLine>();
        GitHubPullRequestFile file = currentReviewFile();
        lines.add(paddedLine(" " + GitKeyBindings.helpLine(GitKeyBindings.View.PULL_REVIEW)
                + "  mode " + _reviewMode.label(), width, C_MUTED, C_ELEVATED));
        lines.add(pullRequestReviewHeader(width));
        lines.add(paddedLine(" " + (_reviewFileSelection + 1) + "/" + _reviewFiles.size() + "  "
                + file.displayLabel() + "  comments " + commentsFor(file.path()).size(),
                width, C_TEXT, C_PANEL));
        int contentHeight = Math.max(0, height - lines.size());
        List<SwimPanelLine> content = switch (_reviewMode) {
        case SUMMARY -> reviewSummaryLines(width);
        case UNIFIED -> reviewUnifiedLines(file, width);
        case SPLIT -> reviewSplitLines(file, width);
        case COMMENTS -> reviewCommentLines(file, width);
        };
        int maxScroll = Math.max(0, content.size() - contentHeight);
        _reviewScroll = Math.max(0, Math.min(_reviewScroll, maxScroll));
        for (int row = 0; row < contentHeight; row++) {
            int index = _reviewScroll + row;
            lines.add(index < content.size() ? content.get(index) : paddedLine("", width, C_MUTED, C_PANEL));
        }
        return lines;
    }

    private List<SwimPanelLine> reviewSummaryLines(int width) {
        var lines = new ArrayList<SwimPanelLine>();
        int additions = 0;
        int deletions = 0;
        for (GitHubPullRequestFile file : _reviewFiles) {
            additions += file.additions();
            deletions += file.deletions();
        }
        lines.add(paddedLine(" Summary", width, C_GOLD, C_PANEL));
        lines.add(paddedLine(" Files changed: " + _reviewFiles.size(), width, C_TEXT, C_PANEL));
        lines.add(paddedLine(" Insertions: +" + additions, width, C_INSERT_FG, C_INSERT_BG));
        lines.add(paddedLine(" Deletions: -" + deletions, width, C_DELETE_FG, C_DELETE_BG));
        lines.add(paddedLine(" Review comments: " + _reviewComments.size(), width, C_TEXT, C_PANEL));
        lines.add(paddedLine("", width, C_MUTED, C_PANEL));
        for (GitHubPullRequestFile file : _reviewFiles) {
            lines.add(paddedLine(" " + file.displayLabel(), width, C_MUTED, C_PANEL));
        }
        return lines;
    }

    private List<SwimPanelLine> reviewUnifiedLines(GitHubPullRequestFile file, int width) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(paddedLine(" Unified diff", width, C_GOLD, C_PANEL));
        for (String patchLine : file.patchLines()) {
            lines.add(reviewPatchLine(patchLine, width));
        }
        appendReviewComments(lines, file, width);
        return lines;
    }

    private List<SwimPanelLine> reviewSplitLines(GitHubPullRequestFile file, int width) {
        var lines = new ArrayList<SwimPanelLine>();
        int leftWidth = Math.max(8, (width - 3) / 2);
        int rightWidth = Math.max(8, width - leftWidth - 3);
        lines.add(paddedLine(" Split diff", width, C_GOLD, C_PANEL));
        for (String patchLine : file.patchLines()) {
            if (patchLine.startsWith("@@")) {
                lines.add(paddedLine(" " + patchLine, width, C_GOLD, C_PANEL));
            } else if (patchLine.startsWith("-") && !patchLine.startsWith("---")) {
                lines.add(rich(
                        span(padRight(fit(patchLine, leftWidth), leftWidth), C_DELETE_FG, C_DELETE_BG),
                        span(" " + REVIEW_SEPARATOR + " ", C_SUBTLE, C_PANEL),
                        span(padRight("", rightWidth), C_MUTED, C_PANEL)));
            } else if (patchLine.startsWith("+") && !patchLine.startsWith("+++")) {
                lines.add(rich(
                        span(padRight("", leftWidth), C_MUTED, C_PANEL),
                        span(" " + REVIEW_SEPARATOR + " ", C_SUBTLE, C_PANEL),
                        span(padRight(fit(patchLine, rightWidth), rightWidth), C_INSERT_FG, C_INSERT_BG)));
            } else {
                String context = patchLine.startsWith(" ") ? patchLine.substring(1) : patchLine;
                lines.add(rich(
                        span(padRight(fit(" " + context, leftWidth), leftWidth), C_MUTED, C_PANEL),
                        span(" " + REVIEW_SEPARATOR + " ", C_SUBTLE, C_PANEL),
                        span(padRight(fit(" " + context, rightWidth), rightWidth), C_MUTED, C_PANEL)));
            }
        }
        appendReviewComments(lines, file, width);
        return lines;
    }

    private List<SwimPanelLine> reviewCommentLines(GitHubPullRequestFile file, int width) {
        var lines = new ArrayList<SwimPanelLine>();
        lines.add(paddedLine(" Comments", width, C_GOLD, C_PANEL));
        List<GitHubReviewComment> comments = commentsFor(file.path());
        if (comments.isEmpty() && issueComments().isEmpty()) {
            lines.add(paddedLine(" No review comments for this file", width, C_MUTED, C_PANEL));
            return lines;
        }
        for (GitHubReviewComment comment : comments) {
            appendComment(lines, comment, width);
        }
        for (GitHubReviewComment comment : issueComments()) {
            appendComment(lines, comment, width);
        }
        return lines;
    }

    private SwimPanelLine reviewPatchLine(String patchLine, int width) {
        if (patchLine.startsWith("@@")) {
            return paddedLine(" " + patchLine, width, C_GOLD, C_PANEL);
        }
        if (patchLine.startsWith("+") && !patchLine.startsWith("+++")) {
            return paddedLine(patchLine, width, C_INSERT_FG, C_INSERT_BG);
        }
        if (patchLine.startsWith("-") && !patchLine.startsWith("---")) {
            return paddedLine(patchLine, width, C_DELETE_FG, C_DELETE_BG);
        }
        if (patchLine.startsWith("+++") || patchLine.startsWith("---")) {
            return paddedLine(patchLine, width, C_ACCENT, C_PANEL);
        }
        return paddedLine(patchLine, width, C_MUTED, C_PANEL);
    }

    private void appendReviewComments(List<SwimPanelLine> lines, GitHubPullRequestFile file, int width) {
        List<GitHubReviewComment> comments = commentsFor(file.path());
        if (comments.isEmpty()) {
            return;
        }
        lines.add(paddedLine("", width, C_MUTED, C_PANEL));
        lines.add(paddedLine(" Review comments", width, C_GOLD, C_PANEL));
        for (GitHubReviewComment comment : comments) {
            appendComment(lines, comment, width);
        }
    }

    private void appendComment(List<SwimPanelLine> lines, GitHubReviewComment comment, int width) {
        lines.add(paddedLine(" @" + comment.author() + "  " + comment.locationLabel()
                + (comment.createdAt().isBlank() ? "" : "  " + comment.createdAt()), width, C_ACCENT, C_ELEVATED));
        if (!comment.diffHunk().isBlank()) {
            for (String line : comment.diffHunk().split("\\R")) {
                lines.add(reviewPatchLine("   " + line, width));
            }
        }
        String body = comment.body().isBlank() ? "(empty comment)" : comment.body();
        for (String line : body.split("\\R")) {
            lines.add(paddedLine("   " + line, width, C_TEXT, C_PANEL));
        }
    }

    private List<GitHubReviewComment> commentsFor(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return _reviewComments.stream()
                .filter(comment -> !comment.issueComment())
                .filter(comment -> path.equals(comment.path()))
                .toList();
    }

    private List<GitHubReviewComment> issueComments() {
        return _reviewComments.stream()
                .filter(GitHubReviewComment::issueComment)
                .toList();
    }

    private GitHubPullRequestFile currentReviewFile() {
        return _reviewFiles.get(Math.max(0, Math.min(_reviewFileSelection, _reviewFiles.size() - 1)));
    }

    private static int reviewSidebarWidth(int width) {
        if (width < 60) {
            return Math.max(16, width / 3);
        }
        return Math.max(24, Math.min(44, width / 3));
    }

    private static List<SwimPanelLine> plainLines(List<String> lines) {
        return lines.stream()
                .map(SwimPanelLine::plain)
                .toList();
    }

    private static SwimPanelLine joinReviewPanes(SwimPanelLine left, SwimPanelLine right, int leftWidth) {
        var spans = new ArrayList<SwimTextSpan>();
        spans.addAll(left.spans());
        if (left.text().length() < leftWidth) {
            spans.add(span(" ".repeat(leftWidth - left.text().length()), C_MUTED, C_PANEL));
        }
        spans.add(span(REVIEW_SEPARATOR, C_SUBTLE, C_ELEVATED));
        spans.addAll(right.spans());
        return new SwimPanelLine(spans);
    }

    private static SwimPanelLine paddedLine(String text, int width, String foreground, String background) {
        return rich(span(padRight(fit(text, width), width), foreground, background));
    }

    private static SwimPanelLine paddedRichLine(int width, String padForeground, String padBackground,
            SwimTextSpan... spans) {
        if (width <= 0) {
            return SwimPanelLine.plain("");
        }
        var fitted = new ArrayList<SwimTextSpan>();
        int remaining = width;
        for (SwimTextSpan span : spans) {
            if (remaining <= 0) {
                break;
            }
            String text = span.text();
            if (text.length() > remaining) {
                text = fit(text, remaining);
            }
            if (!text.isEmpty()) {
                fitted.add(SwimTextSpan.styled(text, span.foreground(), span.background()));
            }
            remaining -= text.length();
        }
        if (remaining > 0) {
            fitted.add(span(" ".repeat(remaining), padForeground, padBackground));
        }
        return new SwimPanelLine(fitted);
    }

    private static SwimPanelLine rich(SwimTextSpan... spans) {
        return SwimPanelLine.of(spans);
    }

    private static SwimTextSpan span(String text, String foreground, String background) {
        return SwimTextSpan.styled(text, foreground, background);
    }

    private static String fit(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text == null) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width == 1) {
            return ".";
        }
        return text.substring(0, width - 1) + ".";
    }

    private static String padRight(String text, int width) {
        String fitted = fit(text, width);
        return fitted + " ".repeat(Math.max(0, width - fitted.length()));
    }

    private static SwimPanelLine highlightedTokenLine(String prefix, String label, String token, String foreground,
            String highlight, String background) {
        var spans = new ArrayList<SwimTextSpan>();
        if (!prefix.isEmpty()) {
            spans.add(span(prefix, C_MUTED, background));
        }
        int index = token == null || token.isBlank() ? -1 : label.indexOf(token);
        if (index < 0) {
            spans.add(span(label, foreground, background));
            return new SwimPanelLine(spans);
        }
        if (index > 0) {
            spans.add(span(label.substring(0, index), foreground, background));
        }
        spans.add(span(token, highlight, background));
        if (index + token.length() < label.length()) {
            spans.add(span(label.substring(index + token.length()), foreground, background));
        }
        return new SwimPanelLine(spans);
    }

    private SwimPanelLine pullRepositoryLine() {
        var spans = new ArrayList<SwimTextSpan>();
        spans.add(span(" repos: ", C_FILTER_FIELD, C_PANEL));
        if (_pullRepositories.isEmpty()) {
            spans.add(span("none", C_MUTED, C_PANEL));
            return new SwimPanelLine(spans);
        }
        for (int i = 0; i < _pullRepositories.size(); i++) {
            if (i > 0) {
                spans.add(span("  ", C_MUTED, C_PANEL));
            }
            String label = _pullRepositories.get(i).label();
            boolean selected = i == _pullRepositorySelection;
            spans.add(span(selected ? "[" + label + "]" : label, selected ? C_ACCENT : C_MUTED, C_PANEL));
        }
        return new SwimPanelLine(spans);
    }

    private SwimPanelLine pullFilterStatusLine() {
        var spans = new ArrayList<SwimTextSpan>();
        if (_pullViewNameEditing) {
            spans.add(span(" save view as: ", C_FILTER_FIELD, C_ELEVATED));
            spans.add(span(_pullViewNameInput.toString(), C_TEXT, C_ELEVATED));
            spans.add(span("  Enter save  Esc cancel", C_MUTED, C_ELEVATED));
            return new SwimPanelLine(spans);
        }
        if (_pullActiveViewName != null) {
            spans.add(span("[" + _pullActiveViewName + "] ", C_ACCENT,
                    _pullRequestFiltering ? C_ELEVATED : C_PANEL));
        }
        appendFilterFieldSpans(spans, PullFilterField.NAME, _pullFilterName, C_TEXT);
        spans.add(span("  ", C_MUTED, C_PANEL));
        appendFilterFieldSpans(spans, PullFilterField.LABELS, _pullFilterLabels, C_LABEL);
        spans.add(span("  ", C_MUTED, C_PANEL));
        appendFilterFieldSpans(spans, PullFilterField.AUTHOR, _pullFilterAuthor, C_PURPLE);
        return new SwimPanelLine(spans);
    }

    private SwimPanelLine pullSavedViewLine() {
        var spans = new ArrayList<SwimTextSpan>();
        spans.add(span(" views: ", C_FILTER_FIELD, C_PANEL));
        if (_pullSavedViews.isEmpty()) {
            spans.add(span("none", C_MUTED, C_PANEL));
            return new SwimPanelLine(spans);
        }
        for (int i = 0; i < _pullSavedViews.size(); i++) {
            if (i > 0) {
                spans.add(span("  ", C_MUTED, C_PANEL));
            }
            GitPullRequestSavedView view = _pullSavedViews.get(i);
            boolean selected = view.name().equals(_pullActiveViewName);
            spans.add(span(selected ? "[" + view.displayLabel() + "]" : view.displayLabel(),
                    selected ? C_ACCENT : C_MUTED, selected ? C_SELECTION_BG : C_PANEL));
        }
        return new SwimPanelLine(spans);
    }

    private void appendFilterFieldSpans(List<SwimTextSpan> spans, PullFilterField field, String value,
            String valueColor) {
        boolean editing = _pullRequestFiltering && _pullFilterEditingField == field;
        String background = editing ? C_ELEVATED : C_PANEL;
        spans.add(span(field.label() + (editing ? "*: " : ": "), C_FILTER_FIELD, background));
        spans.add(span(value.isBlank() ? "(any)" : value, value.isBlank() ? C_MUTED : valueColor, background));
    }

    private static SwimPanelLine pullRequestLine(String prefix, GitHubPullRequest pullRequest, String foreground,
            String background) {
        var spans = new ArrayList<SwimTextSpan>();
        spans.add(span(prefix, C_MUTED, background));
        spans.add(span("#" + pullRequest.number(), C_PULL_REQUEST_NUMBER, background));
        spans.add(span(" " + pullRequest.title(), foreground, background));
        spans.add(span("  ", C_MUTED, background));
        spans.add(span(pullRequest.headRef(), C_ACCENT, background));
        spans.add(span(" -> ", C_MUTED, background));
        spans.add(span(pullRequest.baseRef(), C_ACCENT, background));
        spans.add(span("  @", C_MUTED, background));
        spans.add(span(pullRequest.author(), C_PURPLE, background));
        if (!pullRequest.labels().isEmpty()) {
            spans.add(span("  ", C_MUTED, background));
            for (int i = 0; i < pullRequest.labels().size(); i++) {
                if (i > 0) {
                    spans.add(span(" ", C_MUTED, background));
                }
                spans.add(span("[" + pullRequest.labels().get(i) + "]", C_LABEL, background));
            }
        }
        return new SwimPanelLine(spans);
    }

    private SwimPanelLine pullRequestReviewHeader(int width) {
        String prefix = " " + _reviewRepository.slug() + "  ";
        String number = "#" + _reviewPullRequest.number();
        return paddedRichLine(width, C_TEXT, C_PANEL,
                span(prefix, C_TEXT, C_PANEL),
                span(number, C_PULL_REQUEST_NUMBER, C_PANEL),
                span(" " + _reviewPullRequest.title(), C_TEXT, C_PANEL));
    }

    private static String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static void appendBlankRichLines(List<SwimPanelLine> lines, int height) {
        while (lines.size() < Math.max(1, height)) {
            lines.add(SwimPanelLine.plain(""));
        }
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
        case "p" -> openPullRequestBrowser();
        case "r" -> refreshResult();
            case "C" -> continueCurrentOperation();
            case "A" -> abortCurrentOperation();
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
        case "p" -> openPullRequestBrowser();
        case "c" -> beginCommit();
        case "C" -> continueCurrentOperation();
        case "A" -> abortCurrentOperation();
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
        case "y" -> cherryPickSelectedHistory();
        case "R" -> openInteractiveRebase();
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

    private SwimPanelResult handleRebaseInput(String input) {
        if (isCloseInput(input)) {
            _mode = Mode.HISTORY;
            return SwimPanelResult.success();
        }
        if (_rebaseEntries.isEmpty()) {
            return SwimPanelResult.ignored();
        }
        return switch (input) {
        case "j", "down" -> {
            _rebaseSelection = Math.min(_rebaseEntries.size() - 1, _rebaseSelection + 1);
            yield SwimPanelResult.success();
        }
        case "k", "up" -> {
            _rebaseSelection = Math.max(0, _rebaseSelection - 1);
            yield SwimPanelResult.success();
        }
        case "J" -> {
            moveRebaseEntry(1);
            yield SwimPanelResult.success();
        }
        case "K" -> {
            moveRebaseEntry(-1);
            yield SwimPanelResult.success();
        }
        case "p" -> {
            currentRebaseEntry().setAction(GitRebaseEntry.Action.PICK);
            yield SwimPanelResult.success();
        }
        case "s" -> {
            currentRebaseEntry().setAction(GitRebaseEntry.Action.SQUASH);
            yield SwimPanelResult.success();
        }
        case "f" -> {
            currentRebaseEntry().setAction(GitRebaseEntry.Action.FIXUP);
            yield SwimPanelResult.success();
        }
        case "d" -> {
            currentRebaseEntry().setAction(GitRebaseEntry.Action.DROP);
            yield SwimPanelResult.success();
        }
        case "enter" -> applyInteractiveRebase();
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

    private SwimPanelResult handlePullRequestsInput(String input) {
        if (_pullViewNameEditing) {
            return handlePullViewNameInput(input);
        }
        if (_pullRequestFiltering) {
            return handlePullRequestFilterInput(input);
        }
        if ("left".equals(input) && _pullRepositories.size() > 1) {
            selectPullRepository(-1);
            return refreshPullRequests();
        }
        if (isCloseInput(input)) {
            _mode = Mode.STATUS;
            return SwimPanelResult.success();
        }
        return switch (input) {
        case "j", "down" -> {
            _pullRequestSelection = Math.min(Math.max(0, _pullRequests.size() - 1), _pullRequestSelection + 1);
            yield SwimPanelResult.success();
        }
        case "k", "up" -> {
            _pullRequestSelection = Math.max(0, _pullRequestSelection - 1);
            yield SwimPanelResult.success();
        }
        case "right" -> {
            selectPullRepository(1);
            yield refreshPullRequests();
        }
        case "/" -> {
            beginPullFilterEdit(PullFilterField.NAME);
            yield SwimPanelResult.success();
        }
        case "L" -> {
            beginPullFilterEdit(PullFilterField.LABELS);
            yield SwimPanelResult.success();
        }
        case "A" -> {
            beginPullFilterEdit(PullFilterField.AUTHOR);
            yield SwimPanelResult.success();
        }
        case "backspace" -> {
            String value = pullFilterValue(_pullFilterEditingField);
            if (!value.isEmpty()) {
                setPullFilterValue(_pullFilterEditingField, value.substring(0, value.length() - 1));
                _pullActiveViewName = null;
                applyPullRequestFilter();
            }
            yield SwimPanelResult.success();
        }
        case "S" -> beginSavePullView();
        case "[" -> applyAdjacentPullView(-1);
        case "]" -> applyAdjacentPullView(1);
        case "0" -> clearPullView();
        case "D" -> deleteActivePullView();
        case "r" -> refreshPullRequests();
        case "f" -> fetchSelectedPullRequest();
        case "enter" -> openPullReview();
        default -> SwimPanelResult.ignored();
        };
    }

    private SwimPanelResult handlePullRequestFilterInput(String input) {
        if ("enter".equals(input)) {
            _pullRequestFiltering = false;
            syncActivePullViewName();
            return SwimPanelResult.success();
        }
        if ("backspace".equals(input)) {
            String value = pullFilterValue(_pullFilterEditingField);
            if (!value.isEmpty()) {
                setPullFilterValue(_pullFilterEditingField, value.substring(0, value.length() - 1));
                _pullActiveViewName = null;
            }
            applyPullRequestFilter();
            return SwimPanelResult.success();
        }
        if (isCloseInput(input)) {
            _pullRequestFiltering = false;
            return SwimPanelResult.success();
        }
        if ("space".equals(input)) {
            setPullFilterValue(_pullFilterEditingField, pullFilterValue(_pullFilterEditingField) + " ");
            _pullActiveViewName = null;
            applyPullRequestFilter();
            return SwimPanelResult.success();
        }
        if (input.length() == 1) {
            setPullFilterValue(_pullFilterEditingField, pullFilterValue(_pullFilterEditingField) + input);
            _pullActiveViewName = null;
            applyPullRequestFilter();
            return SwimPanelResult.success();
        }
        return SwimPanelResult.ignored();
    }

    private SwimPanelResult handlePullViewNameInput(String input) {
        if ("enter".equals(input)) {
            return savePullView();
        }
        if ("backspace".equals(input)) {
            if (!_pullViewNameInput.isEmpty()) {
                _pullViewNameInput.deleteCharAt(_pullViewNameInput.length() - 1);
            }
            return SwimPanelResult.success();
        }
        if (isCloseInput(input)) {
            _pullViewNameEditing = false;
            _pullViewNameInput.setLength(0);
            return SwimPanelResult.success();
        }
        if ("space".equals(input)) {
            _pullViewNameInput.append(' ');
            return SwimPanelResult.success();
        }
        if (input.length() == 1) {
            _pullViewNameInput.append(input);
            return SwimPanelResult.success();
        }
        return SwimPanelResult.ignored();
    }

    private SwimPanelResult handlePullReviewInput(String input) {
        if (isCloseInput(input)) {
            _mode = Mode.PULL_REQUESTS;
            _reviewScroll = 0;
            return SwimPanelResult.success();
        }
        return switch (input) {
        case "j" -> {
            moveReviewFile(1);
            yield SwimPanelResult.success();
        }
        case "k" -> {
            moveReviewFile(-1);
            yield SwimPanelResult.success();
        }
        case "n" -> {
            moveReviewHunk(1);
            yield SwimPanelResult.success();
        }
        case "p" -> {
            moveReviewHunk(-1);
            yield SwimPanelResult.success();
        }
        case "down" -> {
            _reviewScroll++;
            yield SwimPanelResult.success();
        }
        case "up" -> {
            _reviewScroll = Math.max(0, _reviewScroll - 1);
            yield SwimPanelResult.success();
        }
        case "m" -> {
            cycleReviewMode();
            yield SwimPanelResult.success();
        }
        case "1" -> {
            setReviewMode(ReviewMode.SUMMARY);
            yield SwimPanelResult.success();
        }
        case "2" -> {
            setReviewMode(ReviewMode.UNIFIED);
            yield SwimPanelResult.success();
        }
        case "3" -> {
            setReviewMode(ReviewMode.SPLIT);
            yield SwimPanelResult.success();
        }
        case "4" -> {
            setReviewMode(ReviewMode.COMMENTS);
            yield SwimPanelResult.success();
        }
        case "r" -> openPullReview();
        case "f" -> fetchReviewPullRequest();
        case "enter", "o" -> openReviewFile();
        default -> SwimPanelResult.ignored();
        };
    }

    private void moveReviewFile(int delta) {
        if (_reviewFiles.isEmpty()) {
            _reviewFileSelection = 0;
            _reviewScroll = 0;
            return;
        }
        _reviewFileSelection = Math.max(0, Math.min(_reviewFileSelection + delta, _reviewFiles.size() - 1));
        _reviewScroll = 0;
    }

    private void cycleReviewMode() {
        ReviewMode[] modes = ReviewMode.values();
        setReviewMode(modes[(_reviewMode.ordinal() + 1) % modes.length]);
    }

    private void setReviewMode(ReviewMode mode) {
        _reviewMode = mode;
        _reviewScroll = 0;
    }

    private void moveReviewHunk(int delta) {
        if (_reviewFiles.isEmpty()) {
            return;
        }
        List<Integer> hunkRows = reviewHunkRows(currentReviewFile());
        if (hunkRows.isEmpty()) {
            return;
        }
        int selected = 0;
        for (int i = 0; i < hunkRows.size(); i++) {
            if (hunkRows.get(i) <= _reviewScroll) {
                selected = i;
            }
        }
        int next = Math.floorMod(selected + delta, hunkRows.size());
        _reviewScroll = hunkRows.get(next);
    }

    private static List<Integer> reviewHunkRows(GitHubPullRequestFile file) {
        var rows = new ArrayList<Integer>();
        for (int i = 0; i < file.patchLines().size(); i++) {
            if (file.patchLines().get(i).startsWith("@@")) {
                rows.add(i + 1);
            }
        }
        return rows;
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

    private SwimPanelLine formatStatusRowRich(StatusRow row, boolean selected) {
        String background = selected ? C_SELECTION_BG : C_PANEL;
        String prefix = selected ? "> " : "  ";
        if (row.isHeader()) {
            int count = sectionCount(row.section());
            String folded = _collapsed.contains(row.section()) ? ">" : "v";
            return rich(
                    span(prefix, C_MUTED, background),
                    span(folded + " ", C_SUBTLE, background),
                    span(row.section().title(), sectionColor(row.section()), background),
                    span(" (" + count + ")", C_MUTED, background));
        }
        if (row.fileChange() != null) {
            GitFileChange change = row.fileChange();
            return rich(
                    span(prefix, C_MUTED, background),
                    span(change.statusCode(), sectionColor(change.section()), background),
                    span(" ", C_MUTED, background),
                    span(change.relativePath(), selected ? C_TEXT : C_MUTED, background));
        }
        if (row.stashEntry() != null) {
            return rich(
                    span(prefix, C_MUTED, background),
                    span(row.stashEntry().displayLabel(), C_PURPLE, background));
        }
        if (row.commitEntry() != null) {
            GitCommitEntry entry = row.commitEntry();
            return highlightedTokenLine(prefix, entry.displayLabel(), entry.shortId(),
                    selected ? C_TEXT : C_MUTED, C_COMMIT_HASH, background);
        }
        if (row.reflogEntry() != null) {
            GitReflogEntryView entry = row.reflogEntry();
            return highlightedTokenLine(prefix, entry.displayLabel(), entry.shortId(),
                    selected ? C_TEXT : C_SUBTLE, C_COMMIT_HASH, background);
        }
        return rich(span(prefix, C_MUTED, background));
    }

    private static String sectionColor(GitSection section) {
        return switch (section) {
        case STAGED -> C_GREEN;
        case UNSTAGED -> C_GOLD;
        case UNTRACKED -> C_ACCENT;
        case CONFLICTS -> C_RED;
        case STASHES -> C_PURPLE;
        case COMMITS -> C_TEXT;
        case REFLOG -> C_SUBTLE;
        };
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

    private SwimPanelResult openPullRequestBrowser() {
        if (_snapshot.repositoryRoot() == null) {
            return SwimPanelResult.ignored();
        }
        try {
            _pullRepositories = GitHubPullRequestService.repositories(_snapshot.repositoryRoot());
            _pullSavedViews = GitProjectSwimConfig.loadPullRequestViews(_snapshot.repositoryRoot());
            syncActivePullViewName();
            _pullRepositorySelection = Math.max(0, Math.min(_pullRepositorySelection,
                    Math.max(0, _pullRepositories.size() - 1)));
            _pullRequestSelection = 0;
            _scroll = 0;
            _mode = Mode.PULL_REQUESTS;
            return refreshPullRequests();
        } catch (IOException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult refreshPullRequests() {
        GitHubRepository repository = currentPullRepository();
        if (repository == null) {
            _allPullRequests = List.of();
            _pullRequests = List.of();
            return SwimPanelResult.successMessage("No GitHub remotes found");
        }
        try {
            _allPullRequests = GitHubPullRequestService.listPullRequests(repository);
            applyPullRequestFilter();
            return SwimPanelResult.success();
        } catch (IOException e) {
            _allPullRequests = List.of();
            _pullRequests = List.of();
            return SwimPanelResult.successMessage(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            _allPullRequests = List.of();
            _pullRequests = List.of();
            return SwimPanelResult.successMessage("GitHub request interrupted");
        }
    }

    private void applyPullRequestFilter() {
        _pullRequests = GitHubPullRequestService.filterPullRequests(_allPullRequests, currentPullFilters());
        _pullRequestSelection = Math.max(0, Math.min(_pullRequestSelection, Math.max(0, _pullRequests.size() - 1)));
        _scroll = Math.max(0, Math.min(_scroll, Math.max(0, _pullRequests.size() - 1)));
    }

    private GitPullRequestFilters currentPullFilters() {
        return new GitPullRequestFilters(_pullFilterName, _pullFilterLabels, _pullFilterAuthor);
    }

    private void setCurrentPullFilters(GitPullRequestFilters filters) {
        GitPullRequestFilters value = filters == null ? GitPullRequestFilters.empty() : filters;
        _pullFilterName = value.name();
        _pullFilterLabels = value.labels();
        _pullFilterAuthor = value.author();
    }

    private void beginPullFilterEdit(PullFilterField field) {
        _pullFilterEditingField = field;
        _pullRequestFiltering = true;
    }

    private String pullFilterValue(PullFilterField field) {
        return switch (field) {
        case NAME -> _pullFilterName;
        case LABELS -> _pullFilterLabels;
        case AUTHOR -> _pullFilterAuthor;
        };
    }

    private void setPullFilterValue(PullFilterField field, String value) {
        switch (field) {
        case NAME -> _pullFilterName = value == null ? "" : value;
        case LABELS -> _pullFilterLabels = value == null ? "" : value;
        case AUTHOR -> _pullFilterAuthor = value == null ? "" : value;
        }
    }

    private SwimPanelResult beginSavePullView() {
        if (currentPullRepository() == null) {
            return SwimPanelResult.successMessage("Select a GitHub remote before saving a PR view");
        }
        _pullViewNameInput.setLength(0);
        if (_pullActiveViewName != null) {
            _pullViewNameInput.append(_pullActiveViewName);
        }
        _pullViewNameEditing = true;
        return SwimPanelResult.success();
    }

    private SwimPanelResult savePullView() {
        String name = _pullViewNameInput.toString().strip();
        if (name.isBlank()) {
            return SwimPanelResult.successMessage("View name cannot be empty");
        }
        GitHubRepository repository = currentPullRepository();
        if (repository == null) {
            return SwimPanelResult.successMessage("Select a GitHub remote before saving a PR view");
        }
        var views = new ArrayList<GitPullRequestSavedView>();
        boolean replaced = false;
        GitPullRequestSavedView next = new GitPullRequestSavedView(name, repository.remoteName(), currentPullFilters());
        for (GitPullRequestSavedView view : _pullSavedViews) {
            if (view.name().equals(name)) {
                views.add(next);
                replaced = true;
            } else {
                views.add(view);
            }
        }
        if (!replaced) {
            views.add(next);
        }
        try {
            GitProjectSwimConfig.savePullRequestViews(_snapshot.repositoryRoot(), views);
            _pullSavedViews = List.copyOf(views);
            _pullActiveViewName = name;
            _pullViewNameEditing = false;
            _pullViewNameInput.setLength(0);
            return SwimPanelResult.successMessage("Saved PR view " + next.displayLabel());
        } catch (IOException e) {
            return SwimPanelResult.successMessage("Could not save PR view: " + e.getMessage());
        }
    }

    private SwimPanelResult applyAdjacentPullView(int delta) {
        if (_pullSavedViews.isEmpty()) {
            return SwimPanelResult.successMessage("No saved PR views");
        }
        int index = activePullViewIndex();
        int next = index < 0 ? (delta >= 0 ? 0 : _pullSavedViews.size() - 1)
                : Math.floorMod(index + delta, _pullSavedViews.size());
        GitPullRequestSavedView view = _pullSavedViews.get(next);
        if (view.pinsRemote() && !hasPullRepositoryRemoteName(view.remoteName())) {
            return SwimPanelResult.successMessage("Saved PR view remote not available: " + view.remoteName());
        }
        boolean switchedRemote = view.pinsRemote() && selectPullRepositoryByRemoteName(view.remoteName());
        setCurrentPullFilters(view.filters());
        _pullActiveViewName = view.name();
        _pullRequestFiltering = false;
        if (switchedRemote) {
            SwimPanelResult result = refreshPullRequests();
            if (result.message() != null && !result.message().isBlank()) {
                return result;
            }
        } else {
            applyPullRequestFilter();
        }
        return SwimPanelResult.successMessage("Applied PR view " + view.displayLabel());
    }

    private SwimPanelResult clearPullView() {
        setCurrentPullFilters(GitPullRequestFilters.empty());
        _pullActiveViewName = null;
        _pullRequestFiltering = false;
        applyPullRequestFilter();
        return SwimPanelResult.successMessage("Cleared PR view");
    }

    private SwimPanelResult deleteActivePullView() {
        if (_pullActiveViewName == null) {
            syncActivePullViewName();
        }
        if (_pullActiveViewName == null) {
            return SwimPanelResult.successMessage("No active saved PR view");
        }
        String deleted = _pullActiveViewName;
        List<GitPullRequestSavedView> views = _pullSavedViews.stream()
                .filter(filter -> !filter.name().equals(deleted))
                .toList();
        try {
            GitProjectSwimConfig.savePullRequestViews(_snapshot.repositoryRoot(), views);
            _pullSavedViews = List.copyOf(views);
            _pullActiveViewName = null;
            return SwimPanelResult.successMessage("Deleted PR view " + deleted);
        } catch (IOException e) {
            return SwimPanelResult.successMessage("Could not delete PR view: " + e.getMessage());
        }
    }

    private int activePullViewIndex() {
        for (int i = 0; i < _pullSavedViews.size(); i++) {
            if (_pullSavedViews.get(i).name().equals(_pullActiveViewName)) {
                return i;
            }
        }
        return -1;
    }

    private void syncActivePullViewName() {
        _pullActiveViewName = null;
        GitPullRequestFilters filters = currentPullFilters();
        GitHubRepository repository = currentPullRepository();
        String remoteName = repository == null ? "" : repository.remoteName();
        for (GitPullRequestSavedView view : _pullSavedViews) {
            if (view.filters().equals(filters) && (!view.pinsRemote() || view.remoteName().equals(remoteName))) {
                _pullActiveViewName = view.name();
                return;
            }
        }
    }

    private void selectPullRepository(int delta) {
        if (_pullRepositories.isEmpty()) {
            _pullRepositorySelection = 0;
            return;
        }
        _pullRepositorySelection = Math.floorMod(_pullRepositorySelection + delta, _pullRepositories.size());
        _pullActiveViewName = null;
        _allPullRequests = List.of();
        _pullRequests = List.of();
        _pullRequestSelection = 0;
        _scroll = 0;
    }

    private boolean selectPullRepositoryByRemoteName(String remoteName) {
        if (remoteName == null || remoteName.isBlank()) {
            return false;
        }
        for (int i = 0; i < _pullRepositories.size(); i++) {
            if (remoteName.equals(_pullRepositories.get(i).remoteName())) {
                if (i == _pullRepositorySelection) {
                    return false;
                }
                _pullRepositorySelection = i;
                _allPullRequests = List.of();
                _pullRequests = List.of();
                _pullRequestSelection = 0;
                _scroll = 0;
                return true;
            }
        }
        return false;
    }

    private boolean hasPullRepositoryRemoteName(String remoteName) {
        if (remoteName == null || remoteName.isBlank()) {
            return true;
        }
        return _pullRepositories.stream()
                .anyMatch(repository -> remoteName.equals(repository.remoteName()));
    }

    private String pullRepositoryListLabel() {
        if (_pullRepositories.isEmpty()) {
            return "none";
        }
        var labels = new ArrayList<String>();
        for (int i = 0; i < _pullRepositories.size(); i++) {
            String label = _pullRepositories.get(i).label();
            labels.add(i == _pullRepositorySelection ? "[" + label + "]" : label);
        }
        return String.join("  ", labels);
    }

    private SwimPanelResult fetchSelectedPullRequest() {
        GitHubPullRequest pullRequest = currentPullRequest();
        if (pullRequest == null) {
            return SwimPanelResult.ignored();
        }
        try {
            return SwimPanelResult.successMessage(
                    GitHubPullRequestService.fetch(_snapshot.repositoryRoot(), currentPullRepository(), pullRequest)
                            + ". Press Enter to review.");
        } catch (IOException | GitAPIException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult fetchReviewPullRequest() {
        if (_reviewPullRequest == null || _reviewRepository == null) {
            return SwimPanelResult.ignored();
        }
        try {
            return SwimPanelResult.successMessage(
                    GitHubPullRequestService.fetch(_snapshot.repositoryRoot(), _reviewRepository, _reviewPullRequest));
        } catch (IOException | GitAPIException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult openPullReview() {
        GitHubPullRequest pullRequest = currentPullRequest();
        GitHubRepository repository = currentPullRepository();
        if (pullRequest == null || repository == null) {
            return SwimPanelResult.ignored();
        }
        ReviewMode mode = _mode == Mode.PULL_REVIEW ? _reviewMode : ReviewMode.UNIFIED;
        try {
            _reviewRepository = repository;
            _reviewPullRequest = pullRequest;
            _reviewFiles = GitHubPullRequestService.files(repository, pullRequest);
            String commentsMessage = null;
            try {
                _reviewComments = GitHubPullRequestService.reviewComments(repository, pullRequest);
            } catch (IOException e) {
                _reviewComments = List.of();
                commentsMessage = "Review comments unavailable: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                _reviewComments = List.of();
                commentsMessage = "Review comments unavailable: GitHub request interrupted";
            }
            _reviewFileSelection = 0;
            _reviewScroll = 0;
            _reviewMode = mode;
            _mode = Mode.PULL_REVIEW;
            return commentsMessage == null ? SwimPanelResult.success() : SwimPanelResult.successMessage(commentsMessage);
        } catch (IOException e) {
            return SwimPanelResult.successMessage(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SwimPanelResult.successMessage("GitHub request interrupted");
        }
    }

    private SwimPanelResult openReviewFile() {
        if (_reviewFiles.isEmpty()) {
            return SwimPanelResult.ignored();
        }
        Path path = _snapshot.repositoryRoot().resolve(_reviewFiles.get(_reviewFileSelection).path()).normalize();
        return java.nio.file.Files.exists(path) ? SwimPanelResult.success(path)
                : SwimPanelResult.successMessage("File is not present in the worktree");
    }

    private GitHubRepository currentPullRepository() {
        if (_pullRepositories.isEmpty()) {
            return null;
        }
        return _pullRepositories.get(Math.max(0, Math.min(_pullRepositorySelection, _pullRepositories.size() - 1)));
    }

    private GitHubPullRequest currentPullRequest() {
        if (_pullRequests.isEmpty()) {
            return null;
        }
        return _pullRequests.get(Math.max(0, Math.min(_pullRequestSelection, _pullRequests.size() - 1)));
    }

    private SwimPanelResult cherryPickSelectedHistory() {
        if (_historyEntries.isEmpty()) {
            return SwimPanelResult.ignored();
        }
        try {
            var entry = _historyEntries.get(_historySelection);
            GitStatusService.cherryPick(_snapshot.repositoryRoot(),
                    new GitCommitEntry(entry.objectId(), entry.summary(), entry.author()));
            refresh();
            _mode = Mode.STATUS;
            return SwimPanelResult.successMessage("Cherry-picked " + entry.shortId());
        } catch (IOException e) {
            refresh();
            _mode = Mode.STATUS;
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult openInteractiveRebase() {
        if (_historyEntries.isEmpty()) {
            return SwimPanelResult.ignored();
        }
        try {
            var upstream = _historyEntries.get(_historySelection);
            _rebaseEntries = GitStatusService.interactiveRebaseEntries(_snapshot.repositoryRoot(), upstream.objectId());
            _rebaseSelection = 0;
            _rebaseUpstreamId = upstream.objectId();
            _rebaseUpstreamLabel = upstream.shortId() + " " + upstream.summary();
            _scroll = 0;
            _mode = Mode.REBASE;
            return SwimPanelResult.success();
        } catch (IOException e) {
            return actionFailed();
        }
    }

    private SwimPanelResult applyInteractiveRebase() {
        if (_rebaseUpstreamId == null) {
            return SwimPanelResult.ignored();
        }
        try {
            GitStatusService.runInteractiveRebase(_snapshot.repositoryRoot(), _rebaseUpstreamId, _rebaseEntries);
            refresh();
            _mode = Mode.STATUS;
            return SwimPanelResult.successMessage("Completed interactive rebase");
        } catch (IOException e) {
            refresh();
            _mode = Mode.STATUS;
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult continueCurrentOperation() {
        try {
            if (_snapshot.operationState().rebaseInProgress()) {
                GitStatusService.continueRebase(_snapshot.repositoryRoot());
                refresh();
                return SwimPanelResult.successMessage("Continued rebase");
            }
            if (_snapshot.operationState().cherryPickInProgress()) {
                GitStatusService.continueCherryPick(_snapshot.repositoryRoot());
                refresh();
                return SwimPanelResult.successMessage("Continued cherry-pick");
            }
            return SwimPanelResult.ignored();
        } catch (IOException e) {
            refresh();
            return SwimPanelResult.successMessage(e.getMessage());
        }
    }

    private SwimPanelResult abortCurrentOperation() {
        try {
            if (_snapshot.operationState().rebaseInProgress()) {
                GitStatusService.abortRebase(_snapshot.repositoryRoot());
                refresh();
                return SwimPanelResult.successMessage("Aborted rebase");
            }
            if (_snapshot.operationState().cherryPickInProgress()) {
                GitStatusService.abortCherryPick(_snapshot.repositoryRoot());
                refresh();
                return SwimPanelResult.successMessage("Aborted cherry-pick");
            }
            return SwimPanelResult.ignored();
        } catch (IOException e) {
            refresh();
            return SwimPanelResult.successMessage(e.getMessage());
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
        return GitKeyBindings.helpLine(GitKeyBindings.View.STATUS);
    }

    private GitKeyBindings.View bindingView() {
        return switch (_mode) {
        case STATUS -> GitKeyBindings.View.STATUS;
        case ACTIONS -> GitKeyBindings.View.ACTIONS;
        case HISTORY -> GitKeyBindings.View.HISTORY;
        case REBASE -> GitKeyBindings.View.REBASE;
        case DIFF -> GitKeyBindings.View.DIFF;
        case COMMIT -> GitKeyBindings.View.COMMIT;
        case HUNK_EDIT -> GitKeyBindings.View.HUNK_EDIT;
        case RESOLVER -> GitKeyBindings.View.RESOLVER;
        case PULL_REQUESTS -> GitKeyBindings.View.PULL_REQUESTS;
        case PULL_REVIEW -> GitKeyBindings.View.PULL_REVIEW;
        };
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

    private GitRebaseEntry currentRebaseEntry() {
        return _rebaseEntries.get(_rebaseSelection);
    }

    private void moveRebaseEntry(int delta) {
        int other = _rebaseSelection + delta;
        if (other < 0 || other >= _rebaseEntries.size()) {
            return;
        }
        var entries = new ArrayList<>(_rebaseEntries);
        var current = entries.remove(_rebaseSelection);
        entries.add(other, current);
        _rebaseEntries = List.copyOf(entries);
        _rebaseSelection = other;
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
