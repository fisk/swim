package org.fisk.swim.ui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.DateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.fileindex.ProjectPaths;
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

    private String getTime() {
        var format = DateFormat.getTimeInstance(2);
        return format.format(new Date());
    }

    private String getMode() {
        return Window.getInstance().getCurrentMode().getName();
    }
    
    private String getName() {
        var window = Window.getInstance();
        var buffer = window.getBufferContext().getBuffer();
        var path = buffer.getPath();
        if (path == null) {
            return "*scratch*";
        }
        var root = ProjectPaths.getProjectRootPath();
        if (root != null) {
            return root.relativize(path).toString();
        } else {
            return buffer.getPath().toString();
        }
    }
    
    private String getBranch() {
        try {
            if (ProjectPaths.hasRepository()) {
                var repo = new FileRepositoryBuilder()
                        .setGitDir(ProjectPaths.getProjectRootPath().resolve(".git").toFile())
                        .build();
                return repo.getBranch();
            } else {
                return "";
            }
        } catch (IOException e) {
            return "";
        }
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
        var str = new AttributedString();
        str.append("[", _foregroundColour, _backgroundColour);
        str.append(UiTheme.repeat("■", filledColumns), heapBarColor(usage), _backgroundColour);
        str.append(UiTheme.repeat("·", HEAP_BAR_WIDTH - filledColumns), UiTheme.TEXT_SUBTLE, _backgroundColour);
        str.append("] " + heapLabel(usage), _foregroundColour, _backgroundColour);
        return str;
    }

    private String getLine() {
        var window = Window.getInstance();
        var buffer = window.getBufferContext().getBuffer();
        var cursor = buffer.getCursor();
        var textLayout = window.getBufferContext().getTextLayout();
        var line = cursor.getYAbsolute();
        var position = cursor.getPosition();
        var index = textLayout.getLogicalLineAt(position).getIndex(position);
        return "" + (position + 1) + ": " + (line + 1) + ", " + (index + 1);
    }

    private TextColor getModeColor() {
        return UiTheme.modeColor(getMode());
    }

    public ModeLineView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.MODELINE_BACKGROUND);
        _foregroundColour = UiTheme.MODELINE_FOREGROUND;
        _memoryBean = ManagementFactory.getMemoryMXBean();
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
        var str = new AttributedString();
        TextColor modeColour = getModeColor();
        UiTheme.appendSegment(str, getMode(), UiTheme.TEXT_ON_ACCENT, modeColour);
        UiTheme.appendRightSeparator(str, modeColour, UiTheme.SURFACE_ACCENT);
        UiTheme.appendSegment(str, getName(), UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ACCENT);
        UiTheme.appendRightSeparator(str, UiTheme.SURFACE_ACCENT, _backgroundColour);
        str.append(" " + Powerline.SYMBOL_LN + " " + getLine() + " ", UiTheme.TEXT_MUTED, _backgroundColour);
        return str;
    }

    private AttributedString getRightString() {
        var str = new AttributedString();
        String branch = getBranch();
        if (!branch.equals("")) {
            UiTheme.appendLeftSeparator(str, UiTheme.SURFACE_ACCENT, _backgroundColour);
            UiTheme.appendSegment(str, Powerline.SYMBOL_BRANCH + " " + branch, UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ACCENT);
        }
        UiTheme.appendLeftSeparator(str, UiTheme.SURFACE_MUTED, str.length() == 0 ? _backgroundColour : UiTheme.SURFACE_ACCENT);
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
        var left = getLeftString();
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
}
