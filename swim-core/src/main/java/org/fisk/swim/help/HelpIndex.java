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
                "Ctrl-o and Tab move backward and forward through the jump list.",
                "g m<char> sets a mark, and '<char> or `<char> jumps back to it.",
                "g n and g N add another cursor for the current word, and g C clears extra cursors.",
                "g ] and g [ jump to the next or previous project diagnostic.",
                "g } and g { jump to the next or previous project error.",
                "g w<char> jumps to visible word starts and shows hints when needed.",
                "g c<char> jumps to visible matching characters and shows hints when needed.",
                "f<char> and F<char> find the next or previous matching character.",
                "/ starts forward search, ? starts backward search, n/N repeat it.");
        addSection(items, "Editing",
                "x deletes the character under the cursor.",
                "d i w deletes the inner word, d w deletes the next word, d d deletes the line.",
                "d/c/y also support text objects such as i(, a(, i\", a\", i', a', i{, a{, i[, a[, ip, and ap.",
                "c i w and c w change text and switch to INSERT mode.",
                "o opens a line below, O opens a line above.",
                "a appends after the cursor, A appends at the end of the line.",
                "y y yanks the current line, p/P paste after or before the cursor.",
                "\"<char> targets a named register for the next yank/delete/paste.",
                "q<char> records a macro and q stops recording; @<char> or @@ plays it back.",
                ". repeats the last edit, u undoes it, and Ctrl-r redoes it.");
        addSection(items, "Folds",
                "V then z f creates a manual fold from the selected lines.",
                "z a toggles the fold at the cursor.",
                "z c closes the current fold and z o opens it.",
                "z M closes all folds and z R opens all folds.");
        addSection(items, "Selection modes",
                "v enters VISUAL mode.",
                "V enters VISUAL LINE mode.",
                "Ctrl-v enters VISUAL BLOCK mode.");
        addSection(items, "Panels and commands",
                "m toggles the project file list.",
                "M toggles whole-project text search.",
                "t toggles the tree view on the left.",
                ":git opens the fullscreen Git workspace.",
                ":debug providers lists debugger backends and :debug open opens the debugger panel.",
                ":debug java ... launches Java debugging and :debug cpp ... launches C/C++ debugging.",
                "B toggles a breakpoint at the current line when a debugger session is active.",
                ":help shows this tutorial.",
                ":e <path> opens a file and creates it if it does not exist.",
                ":buffers or :ls lists open buffers, and :buffer/:bnext/:bprev switches between them.",
                ":grep <text> searches project contents and shows matching lines.",
                ":copen/:cnext/:cprev opens and walks the quickfix list from project search results.",
                ":lgrep <text> builds a location list from the current buffer, and :lopen/:lnext/:lprev walks it.",
                ":multicursor <text> places cursors on every literal match in the current buffer.",
                ":registers, :marks, and :jumps show editor state.",
                ":s/.../.../ and :%s/.../.../ run substitutions in the current line or whole buffer.",
                ":split opens another view below the active buffer.",
                ":vsplit opens another view to the right of the active buffer.",
                ":focus left|right|up|down|next|prev moves between panes.",
                ":close closes the active pane and :only keeps just the active pane.",
                "While typing : commands, Up/Down browses matches and Tab completes them.",
                ":nemo <question> asks Nemo about the current file and opens the persistent chat pane.",
                ":shell opens a new shell workspace.",
                ":slack opens the fullscreen Slack workspace.",
                ":vshell opens a shell in a split to the right.",
                ":hshell opens a shell in a split below.",
                ":w writes the current buffer to disk.",
                "~/.swim/config.json can define normal-mode remaps, startup commands, and editor options.",
                "Normal launches open the requested path or an empty scratch buffer.",
                ":reload and :rebuild preserve the current buffers and split layout while the runtime restarts.",
                ":reload loads the latest built SWIM core.",
                ":rebuild and :upgrade rebuild and reload SWIM.",
                ":q quits the editor.");
        addSection(items, "Nemo",
                "Press ! or run :nemo <question> to open Nemo for the current workspace.",
                "Inside Nemo, press Enter to send; Shift-Enter, Ctrl-Enter, Alt-Enter, or Ctrl-J insert newlines.",
                "Pasted multiline text stays in the Nemo draft, so exception traces can be edited before sending.",
                "Nemo's webSearch tool is enabled by default and can be disabled in ~/.swim/nemo/nemo.conf.",
                "Nemo's delegateTask tool starts focused work in parallel sub-agent workers with the same permissions.",
                "Nemo can inspect delegated work with worker_status/read_worker and wait with bounded join_worker.",
                "Type : commands for Nemo chat commands.",
                ":sessions, :workers, :new, :switch, :rename, :reset, :delete, and :abort manage sessions and workers.",
                ":permissions shows permission mode, command policy, OS sandbox backend, and approval policy.",
                ":permissions read-only, :permissions workspace-write, and :permissions full-access change the active session.",
                "When approval is required, Nemo opens approval options; use arrows and Enter to choose approve once, approve always, or deny.",
                ":approvals and :unapprove manage pending and saved tool approvals.",
                "permissionMode can be read_only, workspace_write, or full_access.",
                "osSandbox auto uses sandbox-exec on macOS or bwrap on Linux and asks before rerunning after sandbox write denials.",
                "osSandbox required fails closed when sandboxing is unavailable; disabled runs unsandboxed.",
                "approvalPolicy can be on_escalation, on_request, or never.",
                "Nemo loads AGENTS.override.md or AGENTS.md from root to current file directory; SKILLS.md still works too.",
                "Nemo stores sessions in ~/.swim/nemo/sessions.json and saved approvals in ~/.swim/nemo/approvals.json.");
        addSection(items, "Diagnostics",
                "Errors are highlighted with a red line background and warnings with yellow.",
                "The frame mode line shows buffer-local error and warning counts.",
                "The global mode line shows project-wide error and warning counts on the right.",
                "g x opens diagnostics for the current line.",
                "Moving the mouse over a faulty line opens a diagnostic popup.",
                "g a opens code actions for the current line, and Enter applies the selected fix.");
        addSection(items, "Pane shortcuts",
                "Ctrl-w s splits below and Ctrl-w v splits to the right.",
                "Ctrl-w h/j/k/l moves focus left, down, up, or right.",
                "Ctrl-w > and Ctrl-w < make the active pane wider or narrower.",
                "Ctrl-w + and Ctrl-w - make the active pane taller or shorter.",
                "Ctrl-w = equalizes split sizes.",
                "Ctrl-w w cycles panes and Ctrl-w W cycles backward.",
                "Ctrl-w q closes the active pane and Ctrl-w o keeps only that pane.",
                "Ctrl-g c w opens a new shell workspace.",
                "Ctrl-g c v opens a shell in a split to the right.",
                "Ctrl-g c h opens a shell in a split below.");
        addSection(items, "Discoverability",
                "The top context bar groups documented key paths in NORMAL mode.",
                "After the first prefix key, it expands into a dropdown that shows matching continuations only.",
                "When groups do not fit, the bar pages back and forth through them instead of dropping them completely.",
                "It switches to contextual hints while the command line, lists, panels, or chat are active.",
                "The command popup shows matching : commands as you type.");
        addSection(items, "Java shortcuts",
                "Space e i organizes imports.",
                "Space e f makes a field final.",
                "Space e a generates accessors.",
                "Space e s generates toString().",
                "Space e l shows code lens information.",
                "Java diagnostics feed the shared g x / g a / g ] diagnostics workflow.",
                "Insert mode shows a completion popup for Java suggestions and snippets.");
        return new ArrayList<ListItem>(items);
    }
}
