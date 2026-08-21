package org.fisk.swim.treesitter;

import com.googlecode.lanterna.TextColor;

/** Maps a parser span to an optional foreground colour for the low-priority syntax layer. */
@FunctionalInterface
public interface TreeSitterSyntaxColourMapper {
    TextColor colour(TreeSitterSyntaxSnapshot snapshot, TreeSitterSyntaxSpan span);
}
