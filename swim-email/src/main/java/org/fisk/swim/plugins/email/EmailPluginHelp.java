package org.fisk.swim.plugins.email;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class EmailPluginHelp {
    private EmailPluginHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("mail", "Mail",
                "How to open and work with the mail workspace.",
                List.of(
                        section("Opening mail",
                                "Use :mail or Space-m to load the mail plugin and open a mail tab. Mail starts from the local H2 cache, then synchronizes configured accounts in the background so the workspace can open before every unread count and label count is known.",
                                ":mail\n<SPACE> m"),
                        section("Reading without exposing private text",
                                "Mail is a confidential workspace. It is visible to you in the terminal, but Nemo editor-control snapshots and tool outputs must not include message content. This means mail is useful for your own workflow while remaining outside assistant-driven inspection.",
                                "Open mail normally, but do not expect Nemo to read it."),
                        section("Closing mail",
                                "Press q or Esc from the mail workspace to remove the mail tab. Closing the tab also lets SWIM exit normally when it was the last remaining workspace.",
                                "q\n<ESC>")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
