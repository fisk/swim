package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TreeViewNode {
    private static final Comparator<TreeViewNode> SORT_ORDER = Comparator
        .comparing(TreeViewNode::isDirectory)
        .reversed()
        .thenComparing(TreeViewNode::getName, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(TreeViewNode::getName);

    private final Path _path;
    private final String _name;
    private final boolean _directory;
    private final List<TreeViewNode> _children;

    private TreeViewNode(Path path, String name, boolean directory, List<TreeViewNode> children) {
        _path = path;
        _name = name;
        _directory = directory;
        _children = List.copyOf(children);
    }

    public static TreeViewNode load(Path path) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        boolean directory = Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS);
        List<TreeViewNode> children = List.of();
        if (directory) {
            children = loadChildren(normalizedPath);
        }
        return new TreeViewNode(normalizedPath, displayName(normalizedPath), directory, children);
    }

    private static List<TreeViewNode> loadChildren(Path path) throws IOException {
        List<TreeViewNode> children = new ArrayList<>();
        try (var stream = Files.list(path)) {
            for (Path child : stream.toList()) {
                try {
                    children.add(load(child));
                } catch (IOException ignored) {
                    // Skip unreadable descendants so one bad entry does not break the whole tree.
                }
            }
        }
        children.sort(SORT_ORDER);
        return children;
    }

    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    public Path getPath() {
        return _path;
    }

    public String getName() {
        return _name;
    }

    public boolean isDirectory() {
        return _directory;
    }

    public List<TreeViewNode> getChildren() {
        return _children;
    }
}
