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
                ":help shows this tutorial.",
                ":e <path> opens a file and creates it if it does not exist.",
                ":w writes the current buffer to disk.",
                ":q quits the editor.");
        addSection(items, "Java shortcuts",
                "Space e i organizes imports.",
                "Space e f makes a field final.",
                "Space e a generates accessors.",
                "Space e s generates toString().",
                "Space e l shows code lens information.");
        return new ArrayList<ListItem>(items);
    }
}
