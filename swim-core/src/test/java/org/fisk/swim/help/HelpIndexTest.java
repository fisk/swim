package org.fisk.swim.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class HelpIndexTest {
    @Test
    void helpListContainsCoreTutorialEntries() {
        var rendered = HelpIndex.createHelpList().stream()
                .map(item -> item.displayString())
                .collect(Collectors.toList());

        assertFalse(rendered.isEmpty());
        assertTrue(rendered.contains("SWIM tutorial"));
        assertTrue(rendered.contains("  :help shows this tutorial."));
        assertTrue(rendered.contains("  :slack opens the fullscreen Slack workspace."));
        assertTrue(rendered.contains("  :vsplit opens another view to the right of the active buffer."));
        assertTrue(rendered.contains("Pane shortcuts"));
        assertTrue(rendered.contains("  Ctrl-w > and Ctrl-w < make the active pane wider or narrower."));
        assertTrue(rendered.contains("  Ctrl-w + and Ctrl-w - make the active pane taller or shorter."));
        assertTrue(rendered.contains("  Ctrl-w = equalizes split sizes."));
        assertTrue(rendered.contains("  Ctrl-g c w opens a new shell workspace."));
        assertTrue(rendered.contains("  Ctrl-g c v opens a shell in a split to the right."));
        assertTrue(rendered.contains("  Ctrl-g c h opens a shell in a split below."));
        assertTrue(rendered.contains("  Press i to enter INSERT mode and type text."));
        assertTrue(rendered.contains("  g w<char> jumps to visible word starts and shows hints when needed."));
        assertTrue(rendered.contains("  g c<char> jumps to visible matching characters and shows hints when needed."));
        assertTrue(rendered.contains("  / starts forward search, ? starts backward search, n/N repeat it."));
        assertTrue(rendered.contains("Nemo"));
        assertTrue(rendered.contains("  Inside Nemo, press Enter to send; Shift-Enter, Ctrl-Enter, Alt-Enter, or Ctrl-J insert newlines."));
        assertTrue(rendered.contains("  Nemo's webSearch tool is enabled by default and can be disabled in ~/.swim/nemo/nemo.conf."));
        assertTrue(rendered.contains("  Nemo's delegateTask tool starts focused work in parallel sub-agent workers with the same permissions."));
        assertTrue(rendered.contains("  Nemo can inspect delegated work with worker_status/read_worker and wait with bounded join_worker."));
        assertTrue(rendered.contains("  :permissions shows permission mode, command policy, OS sandbox backend, and approval policy."));
        assertTrue(rendered.contains("  When approval is required, Nemo opens approval options; use arrows and Enter to choose approve once, approve always, or deny."));
        assertTrue(rendered.contains("  :approvals and :unapprove manage pending and saved tool approvals."));
        assertTrue(rendered.contains("  osSandbox auto uses sandbox-exec on macOS or bwrap on Linux and asks before rerunning after sandbox write denials."));
        assertTrue(rendered.contains("  osSandbox required fails closed when sandboxing is unavailable; disabled runs unsandboxed."));
    }

    @Test
    void helpItemsAreSafeToSelect() {
        for (var item : HelpIndex.createHelpList()) {
            item.onClick();
        }
    }
}
