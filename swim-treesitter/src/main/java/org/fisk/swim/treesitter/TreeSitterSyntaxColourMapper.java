package org.fisk.swim.treesitter;

import org.fisk.swim.terminal.TextColor;

/** Maps a parser span to an optional foreground colour for the low-priority syntax layer. */
@FunctionalInterface
public interface TreeSitterSyntaxColourMapper {
    TextColor colour(TreeSitterSyntaxSnapshot snapshot, TreeSitterSyntaxSpan span);
}
