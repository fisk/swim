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
        assertTrue(rendered.contains("Beginner tutorial: the two most important ideas"));
        assertTrue(rendered.contains("  SWIM starts in NORMAL mode, where keys are commands instead of text."));
        assertTrue(rendered.contains("  If you feel lost, press Esc a few times, then type :help and press Enter."));
        assertTrue(rendered.contains("Beginner tutorial: opening and saving files"));
        assertTrue(rendered.contains("  Launch with swim file.txt to open one file, or swim one.txt two.txt to open several files."));
        assertTrue(rendered.contains("  When SWIM starts without a file, the welcome buffer tells you how to open help."));
        assertTrue(rendered.contains("Beginner tutorial: your first edit"));
        assertTrue(rendered.contains("  You can also use the arrow keys while learning."));
        assertTrue(rendered.contains("Beginner tutorial: selecting, copying, and pasting"));
        assertTrue(rendered.contains("  Press Ctrl-v for visual block selection when you need a rectangular selection."));
        assertTrue(rendered.contains("Beginner tutorial: buffers and panes"));
        assertTrue(rendered.contains("  Run :bnext and :bprev to move through open buffers."));
        assertTrue(rendered.contains("  :help shows this tutorial."));
        assertTrue(rendered.contains("  :slack opens the fullscreen Slack workspace."));
        assertTrue(rendered.contains("  :todo opens the Todo workspace."));
        assertTrue(rendered.contains("  Ctrl-t opens quick Todo capture from any screen."));
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
        assertTrue(rendered.contains("  / starts regex forward search, ? starts regex backward search, n/N repeat it."));
        assertTrue(rendered.contains("Todo"));
        assertTrue(rendered.contains("  Run :todo to open the Todo workspace."));
        assertTrue(rendered.contains("  Press Ctrl-t from any screen to add a quick Inbox todo."));
        assertTrue(rendered.contains("  In quick capture, Enter adds the todo and Esc cancels it."));
        assertTrue(rendered.contains("  Todo items are stored in ~/.swim/todo/todos.mv.db."));
        assertTrue(rendered.contains("Nemo"));
        assertTrue(rendered.contains("  Inside Nemo, press Enter to send; Shift-Enter, Ctrl-Enter, Alt-Enter, or Ctrl-J insert newlines."));
        assertTrue(rendered.contains("  Nemo's webSearch tool is enabled by default and can be disabled in ~/.swim/nemo/nemo.conf."));
        assertTrue(rendered.contains("  Nemo supports stdio MCP servers configured in ~/.swim/nemo/nemo.conf."));
        assertTrue(rendered.contains("  MCP tools are exposed as mcp__server__tool, require approval unless full-access, and are hidden in read-only mode."));
        assertTrue(rendered.contains("  Nemo's delegateTask tool starts focused work in parallel sub-agent workers with the same permissions."));
        assertTrue(rendered.contains("  Nemo can inspect delegated work with worker_status/read_worker, steer it with message_worker, and wait with bounded join_worker."));
        assertTrue(rendered.contains("  Nemo can use screen_snapshot and drive_editor to inspect and control the editor after host approval."));
        assertTrue(rendered.contains("  screen_snapshot is blocked while mail is visible; email content is never exposed to Nemo."));
        assertTrue(rendered.contains("  Editor-control approvals appear in a host overlay that Nemo cannot see or control; Esc in that overlay stops/denies the request."));
        assertTrue(rendered.contains("  finish_editor_control reopens the invoking Nemo chat when editor-control work is done."));
        assertTrue(rendered.contains("  :mcp lists configured MCP servers and discovered tools."));
        assertTrue(rendered.contains("  :permissions shows permission mode, command policy, OS sandbox backend, and approval policy."));
        assertTrue(rendered.contains("  :tell <session-id> <message> sends a message to a worker without switching sessions."));
        assertTrue(rendered.contains("  When normal tool approval is required, Nemo opens same-workspace approval options; use arrows and Enter to choose approve once, approve always, or deny."));
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
