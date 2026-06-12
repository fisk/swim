package org.fisk.swim.plugins.slack;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class SlackPluginHelp {
    private SlackPluginHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("slack", "Slack",
                "How to open and use the Slack workspace.",
                List.of(
                        section("Opening Slack",
                                "Use :slack or Space-s to load the Slack plugin and open the Slack workspace. The plugin reads workspace configuration from the SWIM Slack config, then uses the configured token to fetch channels and messages.",
                                ":slack\n<SPACE> s"),
                        section("Working in a communication workspace",
                                "Slack is a full workspace rather than an editor buffer. Use it for reading and sending messages without mixing chat state into project buffers. Like mail, Slack content should be treated as private integration data rather than project text for assistant-driven editor control.",
                                ":slack"),
                        section("Closing Slack",
                                "Use q or Esc when you are done with the Slack tab. Closing the tab removes the workspace instead of leaving a hidden plugin view that prevents the editor from quitting.",
                                "q\n<ESC>")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
