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
        addSection(items, "Beginner tutorial: the two most important ideas",
                "SWIM starts in NORMAL mode, where keys are commands instead of text.",
                "Press i when you want to type ordinary text.",
                "Press Esc when you want to stop typing and return to commands.",
                "Press : when you want to run a named command such as :help, :w, :q, or :e file.txt.",
                "If you feel lost, press Esc a few times, then type :help and press Enter.");
        addSection(items, "Beginner tutorial: opening and saving files",
                "Launch with swim file.txt to open one file, or swim one.txt two.txt to open several files.",
                "When SWIM starts without a file, the welcome buffer tells you how to open help.",
                "Run :e notes.txt to open or create notes.txt.",
                "Run :w to save the current file.",
                "Run :w new-name.txt to save the current buffer to a specific file.",
                "Run :q to quit, or :q! to quit without saving changes.");
        addSection(items, "Beginner tutorial: your first edit",
                "Move the cursor with h left, j down, k up, and l right.",
                "You can also use the arrow keys while learning.",
                "Press i, type a short sentence, then press Esc.",
                "Press x in NORMAL mode to delete the character under the cursor.",
                "Press u to undo and Ctrl-r to redo.",
                "Press . to repeat the last edit.");
        addSection(items, "Beginner tutorial: useful movement",
                "w moves to the start of the next word, and b moves back one word.",
                "0 moves to the start of the line, and $ moves to the end.",
                "gg moves to the first line, and G moves to the last line.",
                "Type a number before a motion to repeat it, such as 10j or 3w.",
                "/word searches forward for word; n repeats the search and N goes the other way.");
        addSection(items, "Beginner tutorial: selecting, copying, and pasting",
                "Press v to start a character selection, or V to select whole lines.",
                "Move to extend the selection, then press y to copy or d to delete.",
                "Press p to paste after the cursor, or P to paste before it.",
                "Press yy to copy the current line and dd to delete the current line.",
                "Press Ctrl-v for visual block selection when you need a rectangular selection.");
        addSection(items, "Beginner tutorial: buffers and panes",
                "Each opened file is a buffer.",
                "Run :buffers or :ls to list open buffers.",
                "Run :bnext and :bprev to move through open buffers.",
                "Run :split to open a horizontal pane and :vsplit to open a vertical pane.",
                "Use Ctrl-w h, Ctrl-w j, Ctrl-w k, and Ctrl-w l to move between panes.");
        addSection(items, "Getting started",
                "NORMAL mode is the default mode for navigation and commands.",
                "Press i to enter INSERT mode and type text.",
                "Press Esc to leave INSERT or VISUAL mode and return to NORMAL.",
                "Press : to open the command line.");
        addSection(items, "Movement",
                "h j k l move left, down, up, right; counts such as 5j repeat motions.",
                "0 jumps to column zero, ^ and _ jump to first nonblank, and $ jumps to the end of the line.",
                "w/W, b/B, and e/E move by words, WORDs, and word ends.",
                "{ and } move by paragraphs, ( and ) move by sentences, and % jumps between matching brackets.",
                "H, M, and L jump to the top, middle, and bottom visible lines.",
                "Ctrl-d/u scroll half pages and Ctrl-f/b scroll full pages.",
                "gg jumps to the top of the buffer and G jumps to the end.",
                "Ctrl-o and Tab move backward and forward through the jump list.",
                "m<char> or g m<char> sets a mark, and '<char> or `<char> jumps back to it.",
                "g n and g N add another cursor for the current word, and g C clears extra cursors.",
                "g ] and g [ jump to the next or previous project diagnostic.",
                "g } and g { jump to the next or previous project error.",
                "g w<char> jumps to visible word starts and shows hints when needed.",
                "g c<char> jumps to visible matching characters and shows hints when needed.",
                "f/F/t/T<char> find or move until a line character; ; and , repeat or reverse the last character find.",
                "/ starts regex forward search, ? starts regex backward search, n/N repeat it.");
        addSection(items, "Editing",
                "x deletes the character under the cursor.",
                "d, c, y, >, <, =, gu, gU, and g~ work as operators with motions, counts, searches, and text objects.",
                "Examples: d$, c}, yG, d/foo<Enter>, gUiw, >ap, dd, yy, cc, >>, <<, and ==.",
                "d/c/y support text objects such as iw, aw, i(, a(, i\", a\", i', a', i{, a{, i[, a[, ip, and ap.",
                "c i w and c w change text and switch to INSERT mode.",
                "D and C delete or change to end of line, Y yanks the line, J joins lines, r replaces characters, R enters REPLACE mode, and ~ toggles case.",
                "o opens a line below, O opens a line above.",
                "a appends after the cursor, A appends at the end of the line.",
                "y y yanks the current line, p/P paste after or before the cursor.",
                "\"<char> targets a named register; 0-9, -, _, +, *, and uppercase append registers are supported.",
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
                "Ctrl-v enters VISUAL BLOCK mode.",
                "Visual block mode supports rectangular y, d, c, I, and p/P paste from block registers.");
        addSection(items, "Panels and commands",
                ":tree opens the project tree.",
                ":grep <text> searches project contents.",
                ":todo opens the fullscreen Todo workspace.",
                "Ctrl-t opens quick Todo capture from any screen.",
                ":git opens the fullscreen Git workspace.",
                ":debug providers lists debugger backends and :debug open opens the debugger panel.",
                ":debug java ... launches Java debugging and :debug cpp ... launches C/C++ debugging.",
                "B toggles a breakpoint at the current line when a debugger session is active.",
                ":help shows this tutorial.",
                ":e <path> opens a file and creates it if it does not exist.",
                ":todo opens the Todo workspace.",
                ":buffers or :ls lists open buffers, and :buffer/:bnext/:bprev switches between them.",
                ":grep <text> searches project contents and shows matching lines.",
                ":copen/:cnext/:cprev opens and walks the quickfix list from project search results.",
                ":lgrep <text> builds a location list from the current buffer, and :lopen/:lnext/:lprev walks it.",
                ":multicursor <text> places cursors on every literal match in the current buffer.",
                ":registers, :marks, and :jumps show editor state.",
                ":s/.../.../, :%s/.../.../, and :10,20s/.../.../ run substitutions.",
                ":g/pattern/d and :v/pattern/d delete matching or non-matching lines; :g can also run substitutions.",
                ":w [path], :wq, :x, :q!, :read <path>, :normal <keys>, and :set option=value are supported.",
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
        addSection(items, "Todo",
                "Run :todo to open the Todo workspace.",
                "Press Ctrl-t from any screen to add a quick Inbox todo.",
                "New todos start in the Inbox until a project is assigned.",
                "In quick capture, Enter adds the todo and Esc cancels it.",
                "Inside Todo, n creates an Inbox item, p assigns or creates a project, and g edits tags.",
                "c or Enter toggles completion, x deletes the selected todo, and Tab moves between sidebar and todos.",
                "i shows the Inbox, a shows all open todos, and q returns to the previous workspace.",
                "Todo items are stored in ~/.swim/todo/todos.mv.db.");
        addSection(items, "Nemo",
                "Press ! to open the Nemo popup, or run :nemo <question> to open Nemo as a workspace.",
                "Inside Nemo, press Enter to send; Shift-Enter, Ctrl-Enter, Alt-Enter, or Ctrl-J insert newlines.",
                "Pasted multiline text stays in the Nemo draft, so exception traces can be edited before sending.",
                "Mouse wheel scrolling works in the Nemo popup and workspace.",
                "In a Nemo workspace, Esc turns the chat into a read-only transcript buffer; press i to return to live input.",
                "Nemo's webSearch tool is enabled by default and can be disabled in ~/.swim/nemo/nemo.conf.",
                "Nemo supports stdio MCP servers configured in ~/.swim/nemo/nemo.conf.",
                "MCP tools are exposed as mcp__server__tool, require approval unless full-access, and are hidden in read-only mode.",
                "Loaded plugins can expose Nemo tools as plugin__plugin__tool; read-only mode only shows plugin tools marked read-only.",
                "Nemo's delegateTask tool starts focused work in parallel sub-agent workers with the same permissions.",
                "Nemo can inspect delegated work with worker_status/read_worker, steer it with message_worker, and wait with bounded join_worker.",
                "Nemo can use screen_snapshot and drive_editor to inspect and control the editor after host approval.",
                "screen_snapshot is blocked while mail is visible; email content is never exposed to Nemo.",
                "Editor-control approvals appear in a host overlay that Nemo cannot see or control; Esc in that overlay stops/denies the request.",
                "finish_editor_control reopens the invoking Nemo chat when editor-control work is done.",
                "Type : commands for Nemo chat commands.",
                ":sessions, :workers, :new, :switch, :rename, :reset, :delete, and :abort manage sessions and workers.",
                ":mcp lists configured MCP servers and discovered tools.",
                ":permissions shows permission mode, command policy, OS sandbox backend, and approval policy.",
                ":permissions read-only, :permissions workspace-write, and :permissions full-access change the active session.",
                ":tell <session-id> <message> sends a message to a worker without switching sessions.",
                "When normal tool approval is required, Nemo opens same-workspace approval options; use arrows and Enter to choose approve once, approve always, or deny.",
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
