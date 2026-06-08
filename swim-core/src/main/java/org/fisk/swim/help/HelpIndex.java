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

    public static List<ListItem> createHelpList() {
        var items = new ArrayList<HelpItem>();
        items.add(new HelpItem("SWIM Help"));
        items.add(new HelpItem("  Use :help to open the chaptered help workspace."));
        items.add(new HelpItem(""));
        for (HelpDocument.Chapter chapter : HelpDocument.chapters()) {
            items.add(new HelpItem(chapter.title()));
            items.add(new HelpItem("  " + chapter.summary()));
            for (HelpDocument.Section section : chapter.sections()) {
                items.add(new HelpItem("  " + section.title()));
                for (String paragraph : section.paragraphs()) {
                    items.add(new HelpItem("    " + paragraph));
                }
                if (!section.example().isBlank()) {
                    items.add(new HelpItem("    Example: " + section.example().replace('\n', ' ')));
                }
            }
            items.add(new HelpItem(""));
        }
        return new ArrayList<ListItem>(items);
    }
}
