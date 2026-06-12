package org.fisk.swim.ui;

import java.util.List;

import org.eclipse.lsp4j.CompletionItemKind;
import org.fisk.swim.lsp.LspCompletionSession;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;

public class CompletionPopupView extends View {
    private static final int MIN_WIDTH = 24;
    private static final int MAX_WIDTH = 72;

    private LspCompletionSession _session;

    public CompletionPopupView(Rect bounds) {
        super(bounds);
        setBackgroundColour(TextColor.ANSI.BLACK);
    }

    public void setSession(LspCompletionSession session) {
        _session = session;
        syncBounds();
        setNeedsRedraw();
    }

    public LspCompletionSession getSession() {
        return _session;
    }

    public void syncBounds() {
        Size parentSize;
        if (getParent() != null) {
            parentSize = getParent().getBounds().getSize();
        } else {
            parentSize = getBounds().getSize();
        }
        setBounds(calculateBounds(parentSize));
    }

    @Override
    public void resize(Size newParentSize) {
        setBounds(calculateBounds(newParentSize));
    }

    @Override
    public void draw(Rect rect) {
        if (_session == null || _session.isEmpty()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var session = _session;
        int visibleRows = Math.min(LspCompletionSession.DEFAULT_VISIBLE_ROWS, session.size());
        session.ensureSelectionVisible(visibleRows);
        var visible = session.visibleEntries(visibleRows);

        var graphics = TerminalContext.getInstance().getGraphics();
        drawHeader(rect, graphics, session);
        drawEntries(rect, graphics, session, visible);
        drawFooter(rect, graphics, session);
    }

    private void drawHeader(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            LspCompletionSession session) {
        graphics.setBackgroundColor(TextColor.ANSI.CYAN);
        graphics.drawRectangle(
                new TerminalPosition(rect.getPoint().getX(), rect.getPoint().getY()),
                new TerminalSize(rect.getSize().getWidth(), 1),
                ' ');
        String prefix = session.getPrefix().isBlank() ? "" : "  " + session.getPrefix();
        String incomplete = session.isIncomplete() ? " +" : "";
        String header = " Java " + (session.getSelection() + 1) + "/" + session.size() + incomplete + prefix;
        drawLine(
                Point.create(rect.getPoint().getX(), rect.getPoint().getY()),
                clip(header, rect.getSize().getWidth()),
                TextColor.ANSI.BLACK,
                TextColor.ANSI.CYAN);
    }

    private void drawEntries(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            LspCompletionSession session,
            List<LspCompletionSession.Entry> visible) {
        int width = rect.getSize().getWidth();
        int startY = rect.getPoint().getY() + 1;
        int globalIndex = session.getScrollOffset();

        for (int i = 0; i < visible.size(); ++i) {
            var entry = visible.get(i);
            boolean selected = globalIndex + i == session.getSelection();
            TextColor background = selected ? TextColor.ANSI.WHITE : TextColor.ANSI.BLACK;
            TextColor foreground = selected ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE;
            int y = startY + i;

            graphics.setBackgroundColor(background);
            graphics.drawRectangle(new TerminalPosition(rect.getPoint().getX(), y), new TerminalSize(width, 1), ' ');

            int sourceWidth = entry.getSource().isBlank() ? 0 : Math.min(18, Math.max(8, width / 4));
            int leftWidth = width - 4 - (sourceWidth == 0 ? 0 : sourceWidth + 1);
            String label = clip(entry.getLabel(), leftWidth);
            String annotation = entry.getAnnotation();
            int remaining = Math.max(0, leftWidth - label.length());

            var row = new AttributedString();
            row.append(" " + kindTag(entry.getKind()) + " ", kindColor(entry.getKind()), background);
            row.append(label, foreground, background);

            if (remaining > 0) {
                if (!annotation.isBlank()) {
                    String paddedAnnotation = clip(" " + annotation, remaining);
                    row.append(paddedAnnotation, selected ? TextColor.ANSI.BLUE : TextColor.ANSI.CYAN, background);
                    remaining -= paddedAnnotation.length();
                }
                if (remaining > 0) {
                    row.append(" ".repeat(remaining), foreground, background);
                }
            }

            if (sourceWidth > 0) {
                row.append(" ", foreground, background);
                row.append(padLeft(clip(entry.getSource(), sourceWidth), sourceWidth),
                        selected ? TextColor.ANSI.BLUE : TextColor.ANSI.GREEN,
                        background);
            }

            drawLine(Point.create(rect.getPoint().getX(), y), row, width);
        }
    }

    private void drawFooter(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            LspCompletionSession session) {
        int footerY = rect.getPoint().getY() + rect.getSize().getHeight() - 1;
        graphics.setBackgroundColor(TextColor.ANSI.GREEN);
        graphics.drawRectangle(
                new TerminalPosition(rect.getPoint().getX(), footerY),
                new TerminalSize(rect.getSize().getWidth(), 1),
                ' ');

        var selected = session.getSelectedEntry();
        String footerText = "";
        if (selected != null) {
            footerText = firstNonBlank(selected.getDetail(), selected.getSource(), selected.getAnnotation(), selected.getLabel());
        }
        drawLine(
                Point.create(rect.getPoint().getX(), footerY),
                " " + clip(footerText, Math.max(0, rect.getSize().getWidth() - 1)),
                TextColor.ANSI.BLACK,
                TextColor.ANSI.GREEN);
    }

    private Rect calculateBounds(Size parentSize) {
        if (_session == null || _session.isEmpty()) {
            return Rect.create(0, 0, 0, 0);
        }

        int width = Math.min(parentSize.getWidth(), preferredWidth());
        int visibleRows = Math.min(LspCompletionSession.DEFAULT_VISIBLE_ROWS, _session.size());
        int height = Math.min(parentSize.getHeight(), visibleRows + 2);

        var bufferView = _session.getBufferContext().getBufferView();
        var cursor = _session.getBufferContext().getBuffer().getCursor();

        int anchorX = cursor.getXOnScreen();
        int anchorY = cursor.getYOnScreen();

        int x = Math.max(0, Math.min(anchorX, parentSize.getWidth() - width));
        int belowY = anchorY + 1;
        int y;
        if (belowY + height <= parentSize.getHeight()) {
            y = belowY;
        } else {
            y = Math.max(0, anchorY - height + 1);
        }
        return Rect.create(x, y, width, height);
    }

    private int preferredWidth() {
        int width = MIN_WIDTH;
        for (var entry : _session.getEntries()) {
            int candidate = 6 + entry.getLabel().length();
            if (!entry.getAnnotation().isBlank()) {
                candidate += 1 + entry.getAnnotation().length();
            }
            if (!entry.getSource().isBlank()) {
                candidate += 3 + Math.min(18, entry.getSource().length());
            }
            width = Math.max(width, candidate);
        }
        width = Math.max(width, 12 + _session.getPrefix().length());
        return Math.min(MAX_WIDTH, width);
    }

    private void drawLine(Point point, String text, TextColor foreground, TextColor background) {
        AttributedString.create(text, foreground, background).drawAt(point, TerminalContext.getInstance().getGraphics());
    }

    private void drawLine(Point point, AttributedString text, int width) {
        String clipped = clip(text.toString(), width);
        if (clipped.length() != text.length()) {
            text = AttributedString.create(clipped, TextColor.ANSI.WHITE, _backgroundColour);
        }
        text.drawAt(point, TerminalContext.getInstance().getGraphics());
    }

    private static String clip(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width == 1) {
            return text.substring(0, 1);
        }
        return text.substring(0, width - 1) + ">";
    }

    private static String padLeft(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat(width - text.length()) + text;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String kindTag(CompletionItemKind kind) {
        if (kind == null) {
            return "?";
        }
        return switch (kind) {
        case Method -> "m";
        case Function -> "f";
        case Constructor -> "c";
        case Field, Property -> "p";
        case Variable, Value -> "v";
        case Class -> "C";
        case Interface -> "I";
        case Module -> "M";
        case Enum -> "E";
        case EnumMember -> "e";
        case Keyword -> "k";
        case Snippet -> "S";
        case File, Folder -> "F";
        default -> "t";
        };
    }

    private static TextColor kindColor(CompletionItemKind kind) {
        if (kind == null) {
            return TextColor.ANSI.YELLOW;
        }
        return switch (kind) {
        case Method, Function, Constructor -> TextColor.ANSI.BLUE;
        case Class, Interface, Enum, Struct, Module -> TextColor.ANSI.GREEN;
        case Field, Property, EnumMember -> TextColor.ANSI.YELLOW;
        case Variable, Value, Constant, TypeParameter -> TextColor.ANSI.MAGENTA;
        case Keyword, Operator -> TextColor.ANSI.RED;
        default -> TextColor.ANSI.CYAN;
        };
    }
}
