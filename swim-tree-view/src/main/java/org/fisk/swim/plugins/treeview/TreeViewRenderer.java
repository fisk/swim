package org.fisk.swim.plugins.treeview;

import java.util.ArrayList;
import java.util.List;

public final class TreeViewRenderer {
    public List<String> render(List<TreeViewRow> rows, String title, int width, int height, int scrollOffset) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int visibleRowCount = Math.max(0, safeHeight - 1);
        List<String> rendered = new ArrayList<>(safeHeight);
        rendered.add(fit(title, safeWidth));
        for (int i = 0; i < visibleRowCount; ++i) {
            int rowIndex = scrollOffset + i;
            String line = rowIndex < rows.size() ? renderRow(rows.get(rowIndex)) : "";
            rendered.add(fit(line, safeWidth));
        }
        return rendered;
    }

    private String renderRow(TreeViewRow row) {
        StringBuilder builder = new StringBuilder();
        builder.append(row.selected() ? "> " : "  ");
        builder.append("  ".repeat(Math.max(0, row.depth())));
        if (row.directory()) {
            builder.append(row.expanded() ? "v " : "> ");
        } else {
            builder.append("- ");
        }
        builder.append(row.label());
        return builder.toString();
    }

    private String fit(String line, int width) {
        if (line.length() >= width) {
            return line.substring(0, width);
        }
        return line + " ".repeat(width - line.length());
    }
}
