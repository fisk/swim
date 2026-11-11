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
                                "Use :mail or Space-m to open a mail tab immediately. Opening the cache, schema/index upgrades, list queries, and synchronization run in background workers. Cached lists load once, in bounded pages; unread counters use a separate database reader so they do not hold up the list.",
                                ":mail\n<SPACE> m"),
                        section("Mailbox views and scrolling",
                                "The global #all and #unsorted views remain at the top. Every mailbox also has its own #all and #unsorted children, including mailbox-specific unread counts. Mouse-wheel scrolling moves the list viewport without changing the selected message or mailbox. Click a row or use j/k to change selection; G reads the final page directly rather than loading the entire mailbox.",
                                "work\n  #all\n  #unsorted\npersonal\n  #all\n  #unsorted"),
                        section("Reusing the local cache",
                                "IMAP checkpoints include UID validity and exclude previously captured UIDs. POP3 compares server UIDLs with persisted download keys before fetching headers. Exchange compares item/version keys before requesting item details. Those keys commit with the imported messages and survive restarts. Fetched bodies, including empty bodies, are saved once and shared by concurrent views. New or changed server items can still require a fetch; servers without stable identifiers need header checks.",
                                "e refresh\nCached bodies stay available offline."),
                        section("Mail sign-in prompts",
                                "A sign-in popup appears only when an account needs browser or device-code authorization. Loading and synchronization statuses do not ask you to sign in. Press Escape to dismiss a prompt and continue reading cached mail; the same prompt stays dismissed across refreshes. The o/y link actions remain available, and a new sign-in request can appear again.",
                                "<ESC> dismiss prompt\no open sign-in link\ny copy sign-in link\ne refresh"),
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
