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
        assertTrue(rendered.contains("  :permissions shows permission mode, command policy, OS sandbox backend, and approval policy."));
        assertTrue(rendered.contains("  :approve <id>, :approve <id> always, :deny <id>, :approvals, and :unapprove manage tool approvals."));
        assertTrue(rendered.contains("  osSandbox auto uses sandbox-exec on macOS or bwrap on Linux, required fails closed, and disabled runs unsandboxed."));
    }

    @Test
    void helpItemsAreSafeToSelect() {
        for (var item : HelpIndex.createHelpList()) {
            item.onClick();
        }
    }
}
