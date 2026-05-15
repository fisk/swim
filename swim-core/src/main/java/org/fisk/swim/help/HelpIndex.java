package org.fisk.swim.help;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.ui.ListView.ListItem;

public class HelpIndex {
    private static class HelpItem extends ListItem {
        private final String _text;

        HelpItem(String text) {
            _text = text;
        }

        @Override
        public void onClick() {
        }

        @Override
        public String displayString() {
            return _text;
        }
    }

    private static void addSection(List<HelpItem> items, String title, String... lines) {
        items.add(new HelpItem(title));
        for (var line : lines) {
            items.add(new HelpItem("  " + line));
        }
        items.add(new HelpItem(""));
    }

    public static List<ListItem> createHelpList() {
        var items = new ArrayList<HelpItem>();
        addSection(items, "SWIM tutorial",
                "Press Esc to close this help panel.",
                "Type in the filter row to narrow the tutorial by keyword.",
                "Use Up/Down to move through the help items.");
        addSection(items, "Getting started",
                "NORMAL mode is the default mode for navigation and commands.",
                "Press i to enter INSERT mode and type text.",
                "Press Esc to leave INSERT or VISUAL mode and return to NORMAL.",
                "Press : to open the command line.");
        addSection(items, "Movement",
                "h j k l move left, down, up, right.",
                "^ and $ jump to the start and end of the line.",
                "gg jumps to the top of the buffer and G jumps to the end.",
                "w<char> jumps to visible word starts and shows hints when needed.",
                "f<char> and F<char> find the next or previous matching character.",
                "/ starts forward search, ? starts backward search, n/N repeat it.");
        addSection(items, "Editing",
                "x deletes the character under the cursor.",
                "d i w deletes the inner word, d w deletes the next word, d d deletes the line.",
                "c i w and c w change text and switch to INSERT mode.",
                "o opens a line below, O opens a line above.",
                "a appends after the cursor, A appends at the end of the line.",
                "y y yanks the current line, p/P paste after or before the cursor.",
                "u undoes the last committed change, Ctrl-r redoes it.");
        addSection(items, "Selection modes",
                "v enters VISUAL mode.",
                "V enters VISUAL LINE mode.",
                "Ctrl-v enters VISUAL BLOCK mode.");
        addSection(items, "Panels and commands",
                "m toggles the project file list.",
                "M toggles whole-project text search.",
                "t toggles the tree view on the left.",
                ":help shows this tutorial.",
                ":e <path> opens a file and creates it if it does not exist.",
                ":grep <text> searches project contents and shows matching lines.",
                ":split opens another view below the active buffer.",
                ":vsplit opens another view to the right of the active buffer.",
                ":focus left|right|up|down|next|prev moves between panes.",
                ":close closes the active pane and :only keeps just the active pane.",
                "While typing : commands, Up/Down browses matches and Tab completes them.",
                ":nemo <question> asks Nemo about the current file and opens the persistent chat pane.",
                "Inside Nemo, use :sessions, :workers, :new, and :switch to manage multiple chat sessions.",
                ":w writes the current buffer to disk.",
                ":reload loads the latest built SWIM core.",
                ":rebuild and :upgrade rebuild and reload SWIM.",
                ":q quits the editor.");
        addSection(items, "Pane shortcuts",
                "Ctrl-w s splits below and Ctrl-w v splits to the right.",
                "Ctrl-w h/j/k/l moves focus left, down, up, or right.",
                "Ctrl-w w cycles panes and Ctrl-w W cycles backward.",
                "Ctrl-w q closes the active pane and Ctrl-w o keeps only that pane.");
        addSection(items, "Discoverability",
                "The two-line top menu shows key chains in NORMAL mode.",
                "It switches to contextual hints while the command line, lists, panels, or chat are active.",
                "The command popup shows matching : commands as you type.");
        addSection(items, "Java shortcuts",
                "Space e i organizes imports.",
                "Space e f makes a field final.",
                "Space e a generates accessors.",
                "Space e s generates toString().",
                "Space e l shows code lens information.",
                "Insert mode shows a completion popup for Java suggestions and snippets.");
        return new ArrayList<ListItem>(items);
    }
}
