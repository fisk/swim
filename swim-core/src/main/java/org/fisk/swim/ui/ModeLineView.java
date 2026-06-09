package org.fisk.swim.ui;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.mail.MailStatusService;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.DiagnosticCounts;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;

import com.googlecode.lanterna.TextColor;

public class ModeLineView extends View {
    private static final int HEAP_BAR_WIDTH = 10;

    private String _time;
    private TextColor _foregroundColour;
    private final Timer _timer;
    private final MemoryMXBean _memoryBean;
    private final List<GarbageCollectorMXBean> _gcBeans;
    private Path _cachedBranchRoot;
    private String _cachedBranch = "";

    private String getTime() {
        var format = DateFormat.getTimeInstance(2);
        return format.format(new Date());
    }

    private String getMode() {
        return frameModeName(Window.getInstance() == null ? null : Window.getInstance().getActiveView(), true);
    }
    
    private String getName() {
        return frameName(Window.getInstance() == null ? null : Window.getInstance().getActiveView());
    }

    private String getBranch() {
        Path path = currentPath();
        Path root = ProjectPaths.getProjectRootPath(path);
        if (root == null) {
            _cachedBranchRoot = null;
            _cachedBranch = "";
            return "";
        }
        if (root.equals(_cachedBranchRoot)) {
            return _cachedBranch;
        }
        try {
            Path gitDir = root.resolve(".git");
            if (!gitDir.toFile().exists()) {
                _cachedBranchRoot = root;
                _cachedBranch = "";
                return "";
            }
            try (var repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir.toFile())
                    .build()) {
                _cachedBranchRoot = root;
                _cachedBranch = repo.getBranch();
                return _cachedBranch;
            }
        } catch (IOException e) {
            _cachedBranchRoot = root;
            _cachedBranch = "";
            return "";
        }
    }

    private Path currentPath() {
        return Window.getInstance().getBufferContext().getBuffer().getPath();
    }
    
    static long heapCapacityBytes(MemoryUsage usage) {
        long committed = usage.getCommitted();
        if (committed > 0) {
            return committed;
        }
        long max = usage.getMax();
        if (max > 0) {
            return max;
        }
        return 1;
    }

    static int heapBarFilledColumns(MemoryUsage usage, int width) {
        long capacity = heapCapacityBytes(usage);
        long used = Math.max(0, Math.min(usage.getUsed(), capacity));
        return (int) Math.round((double) used * width / capacity);
    }

    static String heapLabel(MemoryUsage usage) {
        long capacity = heapCapacityBytes(usage);
        long used = Math.max(0, Math.min(usage.getUsed(), capacity));
        return used / 1024 / 1024 + "/" + capacity / 1024 / 1024 + "M";
    }

    static String zgcCollectionLabel(List<? extends GarbageCollectorMXBean> beans) {
        long minor = 0;
        long major = 0;
        boolean hasMinor = false;
        boolean hasMajor = false;
        for (GarbageCollectorMXBean bean : beans == null ? List.<GarbageCollectorMXBean>of() : beans) {
            String name = bean.getName() == null ? "" : bean.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("zgc") || name.contains("pause") || !name.contains("cycle")) {
                continue;
            }
            long count = bean.getCollectionCount();
            if (count < 0) {
                continue;
            }
            if (name.contains("minor")) {
                minor += count;
                hasMinor = true;
            } else if (name.contains("major")) {
                major += count;
                hasMajor = true;
            }
        }
        if (!hasMinor && !hasMajor) {
            return "";
        }
        return minor + "m " + major + "M";
    }

    static TextColor heapBarColor(MemoryUsage usage) {
        long capacity = heapCapacityBytes(usage);
        long used = Math.max(0, Math.min(usage.getUsed(), capacity));
        double ratio = capacity == 0 ? 0 : (double) used / capacity;
        if (ratio >= 0.85) {
            return UiTheme.ACCENT_RED;
        }
        if (ratio >= 0.6) {
            return UiTheme.ACCENT_GOLD;
        }
        return UiTheme.ACCENT_GREEN;
    }

    private MemoryUsage getHeapUsage() {
        return _memoryBean.getHeapMemoryUsage();
    }

    private AttributedString getHeapString() {
        MemoryUsage usage = getHeapUsage();
        int filledColumns = heapBarFilledColumns(usage, HEAP_BAR_WIDTH);
        String collectionLabel = zgcCollectionLabel(_gcBeans);
        var str = new AttributedString();
        str.append("[", _foregroundColour, _backgroundColour);
        str.append(UiTheme.repeat("■", filledColumns), heapBarColor(usage), _backgroundColour);
        str.append(UiTheme.repeat("·", HEAP_BAR_WIDTH - filledColumns), UiTheme.TEXT_SUBTLE, _backgroundColour);
        str.append("] " + heapLabel(usage)
                + (collectionLabel.isBlank() ? "" : " " + collectionLabel), _foregroundColour, _backgroundColour);
        return str;
    }

    private String getLine() {
        return frameLine(Window.getInstance() == null ? null : Window.getInstance().getActiveView());
    }

    private TextColor getModeColor() {
        return UiTheme.modeColor(getMode());
    }

    public ModeLineView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.MODELINE_BACKGROUND);
        _foregroundColour = UiTheme.MODELINE_FOREGROUND;
        _memoryBean = ManagementFactory.getMemoryMXBean();
        _gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        _time = getTime();

        _timer = new Timer(true);
        _timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                var time = getTime();
                if (!time.equals(_time)) {
                    EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                        _time = time;
                        ModeLineView.this.setNeedsRedraw();
                    }));
                }
            }
        }, 1000, 1000);
    }

    public void close() {
        _timer.cancel();
    }

    private AttributedString getLeftString() {
        return leftStringForView(Window.getInstance() == null ? null : Window.getInstance().getActiveView(), true, _backgroundColour);
    }

    static AttributedString leftStringForView(View view, boolean active, TextColor trailingBackground) {
        var str = new AttributedString();
        String mode = frameModeName(view, active);
        String name = frameName(view);
        String line = frameLine(view);
        DiagnosticCounts counts = frameDiagnosticCounts(view);
        if (mode != null && !mode.isBlank()) {
            TextColor modeColour = UiTheme.modeColor(mode);
            UiTheme.appendSegment(str, mode, UiTheme.TEXT_ON_ACCENT, modeColour);
            UiTheme.appendRightSeparator(str, modeColour, UiTheme.SURFACE_ACCENT);
        }
        if (name != null && !name.isBlank()) {
            UiTheme.appendSegment(str, name, UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ACCENT);
            UiTheme.appendRightSeparator(str, UiTheme.SURFACE_ACCENT, trailingBackground);
        }
        appendDiagnosticCounts(str, counts, trailingBackground);
        if (line != null && !line.isBlank()) {
            str.append(" " + Powerline.SYMBOL_LN + " " + line + " ", UiTheme.TEXT_MUTED, trailingBackground);
        }
        return str;
    }

    private AttributedString getRightString() {
        var str = new AttributedString();
        TextColor currentBackground = _backgroundColour;
        DiagnosticCounts counts = projectDiagnosticCounts();
        if (counts.errors() > 0) {
            UiTheme.appendLeftSeparator(str, UiTheme.ACCENT_RED, currentBackground);
            UiTheme.appendSegment(str, "E " + counts.errors(), UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_RED);
            currentBackground = UiTheme.ACCENT_RED;
        }
        if (counts.warnings() > 0) {
            UiTheme.appendLeftSeparator(str, UiTheme.ACCENT_GOLD, currentBackground);
            UiTheme.appendSegment(str, "W " + counts.warnings(), UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
            currentBackground = UiTheme.ACCENT_GOLD;
        }
        int unreadCount = MailStatusService.getInstance().currentStatus().unreadCount();
        if (unreadCount > 0) {
            UiTheme.appendLeftSeparator(str, UiTheme.ACCENT_GREEN, currentBackground);
            UiTheme.appendSegment(str, "mail " + unreadCount, UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GREEN);
            currentBackground = UiTheme.ACCENT_GREEN;
        }
        String branch = getBranch();
        if (!branch.equals("")) {
            UiTheme.appendLeftSeparator(str, UiTheme.SURFACE_ACCENT, currentBackground);
            UiTheme.appendSegment(str, Powerline.SYMBOL_BRANCH + " " + branch, UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ACCENT);
            currentBackground = UiTheme.SURFACE_ACCENT;
        }
        UiTheme.appendLeftSeparator(str, UiTheme.SURFACE_MUTED, currentBackground);
        UiTheme.appendSegment(str, _time, UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.appendLeftSeparator(str, UiTheme.SURFACE_ELEVATED, UiTheme.SURFACE_MUTED);
        str.append(getHeapString());
        return str;
    }

    private AttributedString getWhitespaces(int length) {
        return AttributedString.create(UiTheme.repeat(" ", Math.max(0, length)), UiTheme.TEXT_MUTED, _backgroundColour);
    }

    private AttributedString getString() {
        var str = new AttributedString();
        var left = Window.getInstance() != null && Window.getInstance().usesFrameModeLines()
                ? new AttributedString()
                : getLeftString();
        var right = getRightString();

        str.append(left);
        str.append(getWhitespaces(getBounds().getSize().getWidth() - left.length() - right.length()));
        str.append(right);

        return str;
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var textGraphics = terminalContext.getGraphics();
        UiTheme.drawLine(textGraphics, rect.getPoint(), rect.getSize().getWidth(), getString(), UiTheme.TEXT_MUTED,
                _backgroundColour);
    }

    static String frameModeName(View view, boolean active) {
        if (view instanceof ShellPanelView shellPanelView) {
            return shellPanelView.modeName();
        }
        if (view instanceof BufferView) {
            if (active && Window.getInstance() != null) {
                return Window.getInstance().modeNameForDisplay();
            }
            return "NORMAL";
        }
        return null;
    }

    private static DiagnosticCounts frameDiagnosticCounts(View view) {
        if (view instanceof BufferView bufferView) {
            return DiagnosticService.getInstance().countsForBuffer(bufferView.getBufferContext().getBuffer().getPath());
        }
        return DiagnosticCounts.EMPTY;
    }

    private DiagnosticCounts projectDiagnosticCounts() {
        Path path = null;
        if (Window.getInstance() != null && Window.getInstance().getBufferContext() != null) {
            path = Window.getInstance().getBufferContext().getBuffer().getPath();
        }
        return DiagnosticService.getInstance().countsForProject(path);
    }

    private static void appendDiagnosticCounts(AttributedString str, DiagnosticCounts counts, TextColor trailingBackground) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        if (counts.errors() > 0) {
            UiTheme.appendSegment(str, "E " + counts.errors(), UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_RED);
            UiTheme.appendRightSeparator(str, UiTheme.ACCENT_RED, counts.warnings() > 0 ? UiTheme.ACCENT_GOLD : trailingBackground);
        }
        if (counts.warnings() > 0) {
            UiTheme.appendSegment(str, "W " + counts.warnings(), UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
            UiTheme.appendRightSeparator(str, UiTheme.ACCENT_GOLD, trailingBackground);
        }
    }

    static String frameName(View view) {
        if (view instanceof BufferView bufferView) {
            var path = bufferView.getBufferContext().getBuffer().getPath();
            if (path == null) {
                return "*scratch*";
            }
            var root = ProjectPaths.getProjectRootPath(path);
            if (root != null) {
                return root.relativize(path).toString();
            }
            return path.toString();
        }
        if (view instanceof ShellPanelView shellPanelView) {
            return shellPanelView.getTitle();
        }
        if (view instanceof DirectoryBrowserView directoryBrowserView) {
            return directoryBrowserView.getTitle();
        }
        if (view instanceof PluginPanelView pluginPanelView) {
            return pluginPanelView.getTitle();
        }
        if (view instanceof ProjectSearchPanelView projectSearchPanelView) {
            return projectSearchPanelView.getTitle();
        }
        if (view instanceof TextPanelView textPanelView) {
            return textPanelView.getTitle();
        }
        if (view instanceof ListView listView) {
            return listView.getTitle();
        }
        if (view instanceof ChatPanelView chatPanelView) {
            return chatPanelView.getTitle();
        }
        if (view instanceof MailPanelView) {
            return "Mail";
        }
        if (view instanceof SlackPanelView) {
            return "Slack";
        }
        return "";
    }

    static String frameLine(View view) {
        if (view instanceof ShellPanelView shellPanelView) {
            var cursor = shellPanelView.getCursor();
            if (cursor == null) {
                return "prompt";
            }
            return "prompt " + (cursor.getYOnScreen() + 1) + ", " + (cursor.getXOnScreen() + 1);
        }
        if (view instanceof BufferView bufferView) {
            var bufferContext = bufferView.getBufferContext();
            var buffer = bufferContext.getBuffer();
            var cursor = buffer.getCursor();
            var textLayout = bufferContext.getTextLayout();
            var line = cursor.getYAbsolute();
            var position = cursor.getPosition();
            var index = textLayout.getLogicalLineAt(position).getIndex(position);
            return (position + 1) + ": " + (line + 1) + ", " + (index + 1);
        }
        return "";
    }
}
