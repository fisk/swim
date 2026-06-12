package org.fisk.swim.plugins.treeview;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class TreeViewPluginHelp {
    private TreeViewPluginHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("tree", "Tree View",
                "How to browse the current project with the tree-view plugin.",
                List.of(
                        section("Opening the tree",
                                "Use :tree or Space-T to load the tree-view plugin. The tree roots itself at the current project root, so it is best opened after visiting a file inside the repository you want to browse.",
                                ":tree\n<SPACE> T"),
                        section("Navigating files and directories",
                                "Use j/k or the arrow keys to move through rows. Right or l expands a directory, Left or h collapses it, and Enter or Space opens the selected file or toggles the selected directory. Use r after external file changes to refresh the tree snapshot.",
                                "j\nl\n<ENTER>\nr")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
