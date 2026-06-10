package org.fisk.swim.help;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimHelpSection;

public final class HelpDocument {
    public record Section(String title, List<String> paragraphs, String example) {
        public Section {
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            example = example == null ? "" : example;
        }
    }

    public record Chapter(String id, String title, String summary, List<Section> sections) {
        public Chapter {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    private static final List<Chapter> BUILT_IN_CHAPTERS = buildChapters();

    private HelpDocument() {
    }

    public static List<Chapter> chapters() {
        List<SwimHelpChapter> pluginChapters = SwimHelpRegistry.chapters();
        if (pluginChapters.isEmpty()) {
            return BUILT_IN_CHAPTERS;
        }
        var chapters = new ArrayList<Chapter>(BUILT_IN_CHAPTERS);
        var ids = new LinkedHashSet<String>();
        for (Chapter chapter : BUILT_IN_CHAPTERS) {
            ids.add(normalize(chapter.id()));
        }
        for (SwimHelpChapter chapter : pluginChapters) {
            if (ids.add(normalize(chapter.id()))) {
                chapters.add(adapt(chapter));
            }
        }
        return List.copyOf(chapters);
    }

    public static Chapter defaultChapter() {
        return chapters().getFirst();
    }

    public static Chapter findChapter(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String normalized = normalize(topic);
        for (Chapter chapter : chapters()) {
            if (normalize(chapter.id()).equals(normalized)
                    || normalize(chapter.title()).equals(normalized)
                    || normalize(chapter.title()).contains(normalized)) {
                return chapter;
            }
        }
        return null;
    }

    public static String renderIndex() {
        var lines = new ArrayList<String>();
        lines.add("SWIM Help Index");
        lines.add("");
        lines.add("Open the editor help workspace with :help. Inside the help workspace, use j/k or arrow keys to scroll the current chapter, ]/[ or Left/Right to choose chapters, gg/G to jump to the top or bottom, and q to return.");
        lines.add("");
        for (Chapter chapter : chapters()) {
            lines.add(chapter.id() + " - " + chapter.title());
            lines.add("  " + chapter.summary());
        }
        return String.join("\n", lines);
    }

    public static String renderChapter(Chapter chapter) {
        if (chapter == null) {
            return renderIndex();
        }
        var lines = new ArrayList<String>();
        lines.add(chapter.title());
        lines.add("");
        lines.add(chapter.summary());
        for (Section section : chapter.sections()) {
            lines.add("");
            lines.add(section.title());
            lines.add("");
            for (String paragraph : section.paragraphs()) {
                lines.add(paragraph);
                lines.add("");
            }
            if (!section.example().isBlank()) {
                lines.add("Example:");
                for (String exampleLine : section.example().split("\\R", -1)) {
                    lines.add("  " + exampleLine);
                }
            }
        }
        return trimTrailingBlankLines(lines);
    }

    public static String renderForNemo(String topic) {
        if (topic == null || topic.isBlank() || "index".equals(normalize(topic))) {
            return renderIndex();
        }
        Chapter chapter = findChapter(topic);
        if (chapter != null) {
            return renderChapter(chapter);
        }
        var matches = search(topic);
        if (matches.isEmpty()) {
            return "No SWIM help chapter matched: " + topic + "\n\n" + renderIndex();
        }
        var lines = new ArrayList<String>();
        lines.add("SWIM help search results for: " + topic);
        lines.add("");
        for (Chapter match : matches) {
            lines.add(match.id() + " - " + match.title());
            lines.add("  " + match.summary());
        }
        lines.add("");
        lines.add("Call swim_help again with a chapter id or title to read the full chapter.");
        return String.join("\n", lines);
    }

    public static List<Chapter> search(String query) {
        if (query == null || query.isBlank()) {
            return chapters();
        }
        String normalized = normalize(query);
        var matches = new ArrayList<Chapter>();
        for (Chapter chapter : chapters()) {
            if (normalize(chapter.id()).contains(normalized)
                    || normalize(chapter.title()).contains(normalized)
                    || normalize(chapter.summary()).contains(normalized)
                    || chapter.sections().stream().anyMatch(section -> sectionMatches(section, normalized))) {
                matches.add(chapter);
            }
        }
        return matches;
    }

    private static boolean sectionMatches(Section section, String normalized) {
        if (normalize(section.title()).contains(normalized)
                || normalize(section.example()).contains(normalized)) {
            return true;
        }
        for (String paragraph : section.paragraphs()) {
            if (normalize(paragraph).contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String trimTrailingBlankLines(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return String.join("\n", lines.subList(0, end));
    }

    private static Chapter adapt(SwimHelpChapter chapter) {
        return new Chapter(
                chapter.id(),
                chapter.title(),
                chapter.summary(),
                chapter.sections().stream()
                        .map(HelpDocument::adapt)
                        .toList());
    }

    private static Section adapt(SwimHelpSection section) {
        return new Section(section.title(), section.paragraphs(), section.example());
    }

    private static Chapter chapter(String id, String title, String summary, Section... sections) {
        return new Chapter(id, title, summary, List.of(sections));
    }

    private static Section section(String title, String paragraph, String example) {
        return new Section(title, List.of(paragraph), example);
    }

    private static Section section(String title, String firstParagraph, String secondParagraph, String example) {
        return new Section(title, List.of(firstParagraph, secondParagraph), example);
    }

    private static List<Chapter> buildChapters() {
        return List.of(
                chapter("start", "Start Here",
                        "A first tour for people who have never used Vim-style editing before.",
                        section("Normal mode and Insert mode",
                                "SWIM starts in NORMAL mode. In NORMAL mode, ordinary keys are commands: j moves down, x deletes a character, and : opens the command line. Press i to enter INSERT mode when you want typed characters to become text in the file. Press Esc to return to NORMAL mode before running editor commands again.",
                                "ihello world<ESC>\n:w"),
                        section("Opening help and files",
                                "Use :help any time you want the full manual. The help view is a workspace, so it has enough room for an index on the left and the selected chapter on the right. Scroll the chapter with j/k, use ] and [ to move between chapters, and click section names in the tree to jump inside a chapter. Use :e with a path to open or create a file, and launch SWIM with several file names when you want the buffers ready at startup.",
                                ":help\n:e notes/today.txt\nswim one.txt two.txt"),
                        section("Saving and leaving",
                                ":w writes the current buffer to disk. :w path writes the current buffer to a specific path. :q exits SWIM, while :q! exits without asking the current buffer to save. If you are reading a workspace such as help, q returns to your previous workspace without exiting the editor.",
                                ":w\n:w draft.txt\n:q"),
                        section("When you feel lost",
                                "Vim-style editors are modal, so most confusion comes from being in a different mode than expected. Press Esc a few times to return to NORMAL mode, then use :help, :buffers, or :q depending on what you need. The top context bar also changes to show commands that make sense for the current focus.",
                                "<ESC><ESC>\n:help")),
                chapter("movement", "Movement, Search, and Jumps",
                        "How to move around quickly before you edit.",
                        section("h j k l and arrow keys",
                                "The h, j, k, and l keys move left, down, up, and right. Arrow keys also work, but the letter motions keep your hands near the editing commands. Most motions accept a count, so 10j means move down ten lines and 3l means move right three columns.",
                                "10j\n3l"),
                        section("Words and line edges",
                                "w moves to the next word, b moves back to the previous word, and e moves to the end of a word. Uppercase W, B, and E treat punctuation as part of a larger WORD. Use 0 for the first column, ^ or _ for the first nonblank character, and $ for the end of the line.",
                                "w\nb\n0\n$"),
                        section("Big jumps",
                                "gg jumps to the top of the buffer, while G jumps to the end. H, M, and L move to the top, middle, and bottom of the visible screen. Ctrl-d and Ctrl-u scroll half pages, while Ctrl-f and Ctrl-b scroll full pages.",
                                "gg\nG\n<CTRL>-d\n<CTRL>-f"),
                        section("Search",
                                "/ starts a forward regex search and ? starts a backward regex search. Press Enter to run it, then use n for the next match and N for the previous match. Searches are also motions, so d/foo<Enter> deletes from the cursor through the next match.",
                                "/error<ENTER>\nn\nd/TODO<ENTER>"),
                        section("Marks, jumps, and diagnostics",
                                "m<char> or g m<char> sets a mark, and '<char> or `<char> jumps back to it. Ctrl-o moves backward through the jump list and Tab moves forward. Diagnostic navigation uses g ] and g [ for next and previous project diagnostic, while g } and g { move between project errors.",
                                "ma\n'a\n<CTRL>-o\ng]")),
                chapter("editing", "Editing Text",
                        "The core editing commands and how operators combine with motions.",
                        section("Inserting and appending",
                                "i inserts before the cursor. a appends after the cursor. A appends at the end of the current line. o opens a new line below, and O opens a new line above. After any of these, type normally until Esc returns you to NORMAL mode.",
                                "A // trailing note<ESC>\noTODO: explain this<ESC>"),
                        section("Deleting and changing",
                                "x deletes one character. d is an operator: it waits for a motion or text object and deletes that range. c is the same shape as d, but switches to INSERT mode after removing the text. D deletes to the end of the line, and C changes to the end of the line.",
                                "x\ndw\ncaw\nD\nCnew ending<ESC>"),
                        section("Operators with motions",
                                "Operators such as d, c, y, >, <, =, gu, gU, and g~ combine with motions. This is the grammar that makes Vim-style editing compact: choose an operation, then choose the text range. For example, y} copies through the next paragraph and gUiw uppercases the inner word.",
                                "d$\nyG\n>ap\ngUiw"),
                        section("Text objects",
                                "Text objects describe structured ranges around the cursor. iw means inner word, aw means a word including surrounding space, ip means inner paragraph, and a( includes parentheses. Operators use them exactly like motions, so ci\" changes the contents inside quotes.",
                                "ci\"\ndi(\nyap"),
                        section("Undo, redo, and repeat",
                                "u undoes the last change and Ctrl-r redoes it. . repeats the last edit at the new cursor location. Repeat is most useful when you make one precise change, move to the next target, and press . to replay it.",
                                "cwrenamed<ESC>\nww.\nu\n<CTRL>-r"),
                        section("Macros",
                                "q<char> starts recording a macro into a register and q stops recording. @<char> plays it back, and @@ repeats the last macro. Macros are useful when the same multi-step edit must be applied to many similar lines.",
                                "qa\nA;<ESC>j\nq\n@a\n@@"),
                        section("Manual folds",
                                "Manual folds hide a range of lines without changing the file. Use zf followed by any normal motion or text object to create a fold, such as zf} for the next paragraph or zfap for the current paragraph. zF folds the current line plus any count, while visual-line mode still supports zf for the selected lines.",
                                "zf}\nzfap\n3zF"),
                        section("Opening, closing, deleting, and navigating folds",
                                "za toggles the fold under the cursor, zA toggles it recursively, zc closes it, zo opens it, and zv opens enough folds at the cursor to reveal the text. Uppercase zC and zO apply recursively to nested folds. zM closes all folds, zR opens all folds, zd deletes one manual fold, zD deletes the fold tree under the cursor, and zE deletes every manual fold. Use zj and zk to jump to the next or previous fold start.",
                                "za\nzA\nzv\nzC\nzO\nzd\nzj")),
                chapter("selection", "Selection, Registers, and Clipboard",
                        "Visual modes, copying, pasting, and register selection.",
                        section("Character, line, and block selection",
                                "v starts character-wise VISUAL mode, V starts VISUAL LINE mode, and Ctrl-v starts VISUAL BLOCK mode. Move to extend the selection, then use y to copy, d to delete, c to change, > or < to indent, or = to reformat.",
                                "vwwy\nVjj>\n<CTRL>-vjjI// <ESC>"),
                        section("Copying and pasting",
                                "yy copies the current line. y with a motion copies a range, such as y$ for the rest of the line. p pastes after the cursor and P pastes before the cursor. Line-wise copies paste as whole lines; character-wise copies paste inline. When you do not name a register, SWIM also uses the operating-system clipboard, so text copied outside the editor can be pasted with p and ordinary yanks are available to other apps.",
                                "yy\np\nyiw\nP"),
                        section("Named registers",
                                "\"<char> chooses a register for the next copy, delete, paste, or macro command. Registers 0-9, -, _, +, and * are supported, and uppercase register names append. The unnamed register is still used when you do not name one.",
                                "\"ayy\n\"ap\n\"Ayy"),
                        section("Moving selected text",
                                "The default leader key is Space. In NORMAL mode, Space-j and Space-k move the current line down or up, while Space-l and Space-h indent or outdent it. Space-f opens the project file picker, and Space-/ opens project grep. Put a count before the leader sequence to operate on several lines, such as 3 Space-j to move the current line and the next two lines down. The same leader-prefixed lowercase keys work in visual modes for the selected lines; a count there moves the whole selection several rows. Because the movement keys stay behind Space, classic Vim bindings like J for join and H/L for screen movement remain intact.",
                                "<SPACE>f\n<SPACE>/\n<SPACE>j\n3 <SPACE>j\nVjj2 <SPACE>k\n<SPACE>l"),
                        section("Visual block paste",
                                "Visual block mode keeps rectangular text as a block register. Copy a column with Ctrl-v and y, move to another column, then paste with p or P. This is useful for adding or moving aligned prefixes across several lines.",
                                "<CTRL>-vjjy\njjp")),
                chapter("files", "Files, Buffers, and Panes",
                        "Opening files, switching buffers, and arranging split views.",
                        section(":e and startup files",
                                ":e path opens an existing file or creates it when possible. Launching with no file opens the welcome screen. Launching with several file paths opens the first file and keeps the others ready as buffers.",
                                ":e src/Main.java\nswim Main.java Test.java"),
                        section("Buffer navigation",
                                "Each open file is a buffer. :buffers and :ls show the list. :buffer or :b jumps to a buffer by number or path fragment. :bnext and :bprev cycle through buffers in order.",
                                ":ls\n:b 2\n:b Main.java\n:bnext"),
                        section("Splits",
                                ":split opens another view below the current buffer, and :vsplit opens one to the right. Ctrl-w s and Ctrl-w v are the normal-mode shortcuts. Splits share the same editor workspace and can show the same or different buffers.",
                                ":split\n:vsplit\n<CTRL>-w v"),
                        section("Moving focus and resizing",
                                ":focus left|right|up|down|next|prev moves between panes. Ctrl-w h, j, k, and l do the same from NORMAL mode. Ctrl-w > and Ctrl-w < adjust width, Ctrl-w + and Ctrl-w - adjust height, and Ctrl-w = equalizes split sizes.",
                                ":focus right\n<CTRL>-w h\n<CTRL>-w ="),
                        section("Closing panes and workspaces",
                                ":close closes the active pane when more than one pane exists, and :only keeps only the active pane. Full workspaces such as help, todo, shell, and Nemo can use q to return to the previous workspace without deleting the underlying buffer state.",
                                ":close\n:only\nq")),
                chapter("commands", "Command Line Reference",
                        "Named commands for project work, editor state, and batch edits.",
                        section("Project search and location lists",
                                ":grep text searches project contents and opens matching results. While you type a :grep command, the project search panel previews results for each character so you can refine the query before pressing Enter. The result list uses the project-local .swim ignore rules, with gitignore-style patterns such as build/, *.log, and !keep.log. Existing .swim settings such as compile_commands are ignored by the search filter. :copen, :cnext, and :cprev walk the quickfix list. :lgrep searches only the current buffer into a location list, and :lopen, :lnext, and :lprev navigate it.",
                                ":grep parse error\n.swim:\n  build/\n  *.log\n  !keep.log\n:cnext\n:lgrep TODO\n:lopen"),
                        section("Substitute and global",
                                ":s/pattern/replacement/ changes the current line, :%s/.../.../ changes the whole buffer, and :10,20s/.../.../ changes a range. :g/pattern/d deletes matching lines, and :v/pattern/d deletes non-matching lines.",
                                ":%s/foo/bar/g\n:g/^$/d\n:v/TODO/d"),
                        section("Moving lines",
                                ":m moves the current line or an addressed range after another line, following Vim's command shape. :m +1 moves the current line down, :m 0 moves it before the first line, and :2,4m $ moves lines two through four to the end of the buffer.",
                                ":m +1\n:m 0\n:2,4m $"),
                        section(":read and :normal",
                                ":read path inserts a file into the current buffer. :normal keys executes normal-mode input as a command, which is useful for scripted edits. Because :normal can change text quickly, check the cursor location before running it on important files.",
                                ":read LICENSE\n:normal gg=G"),
                        section("State views",
                                ":registers, :marks, and :jumps show the editor state that often explains surprising paste or navigation behavior. These are read-only text panels, so they are safe to open while debugging your workflow.",
                                ":registers\n:marks\n:jumps")),
                chapter("diagnostics", "Diagnostics and Code Intelligence",
                        "Language-server feedback, diagnostic navigation, and suggested fixes.",
                        section("Diagnostic colors and counts",
                                "Error lines use a red-tinted background and warning lines use a yellow-tinted background and stronger warning text. The frame mode line shows buffer-local counts, while the global mode line shows project-wide counts on the right.",
                                "Watch the mode line after clangd or Java diagnostics arrive."),
                        section("Opening diagnostics",
                                "g x opens diagnostics for the current line. Moving the mouse over a diagnostic line also opens a hover popup. This keeps warning details nearby without forcing you out of the buffer.",
                                "gx"),
                        section("Code actions",
                                "g a opens code actions for the current diagnostic line when the LSP backend provides suggested fixes. Select a fix and press Enter to apply it. If no action is available, the command reports that rather than changing the file silently.",
                                "ga"),
                        section("Project diagnostic navigation",
                                "g ] and g [ move to the next and previous diagnostic in the project. g } and g { move through project errors only, which is useful when warnings are noisy but compile errors need immediate attention.",
                                "g]\ng}")),
                chapter("workspaces", "Todo, Shell, Git, Debugger, and Integrations",
                        "Full-screen workspaces and side tools that support the editor.",
                        section("Todo",
                                ":todo opens the Todo workspace. Ctrl-t opens quick capture from any screen, Enter adds the item to the Inbox, and Esc cancels. Inside Todo, n creates an item, p assigns a project, g edits tags, c or Enter toggles completion, and x deletes.",
                                "<CTRL>-t\n:todo"),
                        section("Shell",
                                ":shell opens a shell workspace. :vshell opens one in a split to the right, and :hshell opens one below. Shell workspaces use Ctrl-g commands for editor-aware control, such as Ctrl-g Esc to browse output and Ctrl-g q to close.",
                                ":shell\n:vshell\n<CTRL>-g <ESC>"),
                        section("Git and debugger",
                                ":git opens the Git workspace. :debug providers lists debugger backends, :debug open opens the debugger panel, and :debug java or :debug cpp launches debugger sessions. When a debugger session is active, B toggles a breakpoint on the current line.",
                                ":git\n:debug providers\nB"),
                        section("Mail and Slack",
                                ":mail and :slack open communication workspaces. These are intentionally treated as private surfaces for Nemo editor-control features: mail content is not exposed to Nemo screen snapshots because it can contain confidential information.",
                                ":mail\n:slack"),
                        section("Server sessions",
                                ":sessions lists the live swim instances managed by the background session server. Select one and press Enter to move the current terminal client to that instance. :session name switches directly to a named instance, creating it on demand with an empty launch argument list. From the shell, swim --attach name starts the client already attached to that named session instead of interpreting the name as a file. If a session is wedged, :session-kill name or swim --kill-session name asks the server to disconnect its client and terminate the session process tree so its threads and class loader can be released.",
                                ":sessions\n:session scratch\nswim --attach scratch\nswim --kill-session scratch")),
                chapter("nemo", "Nemo Assistant",
                        "How to use Nemo and how Nemo can learn SWIM behavior.",
                        section("Starting Nemo",
                                "Press ! to open the Nemo popup, or run :nemo question to open Nemo as a workspace. Enter sends a message, while Shift-Enter, Ctrl-Enter, Alt-Enter, and Ctrl-J insert newlines. Pasted exception traces stay in the draft so you can edit before sending.",
                                "!\n:nemo explain this file"),
                        section("Nemo commands",
                                "Inside Nemo, :sessions, :session, and :session-kill manage live SWIM server sessions. :conversations, :workers, :new, :switch, :rename, :reset, :delete, and :abort manage Nemo conversations and delegated workers. :permissions shows or changes tool permissions, :mcp lists MCP servers, :approvals lists approvals, and :swim-help reads this editor help.",
                                ":sessions\n:session-kill scratch\n:conversations\n:workers"),
                        section("Nemo tools and permissions",
                                "Nemo has tools for reading files, searching, running sandboxed commands, editing files, delegating work, using MCP servers, and controlling the editor after host approval. Read-only mode hides mutating tools, workspace-write allows workspace changes, and full-access bypasses Nemo's sandbox policy.",
                                ":permissions read-only\n:permissions workspace-write"),
                        section("Editor control",
                                "start_editor_control asks the host for approval before Nemo can inspect or drive the editor. screen_snapshot returns a host-filtered view, drive_editor sends sandbox-aware editor input, and finish_editor_control releases the lock and returns Nemo to its chat.",
                                "Nemo tool flow: start_editor_control -> screen_snapshot -> drive_editor -> finish_editor_control")),
                chapter("configuration", "Configuration, Reloading, and Customization",
                        "How Swim remembers behavior and reloads itself during development.",
                        section("Editor config",
                                "~/.swim/config.json stores editor options, startup commands, and normal-mode remaps. Startup commands run after the window is initialized. Keep remaps small and explicit so the top context bar and command grammar remain understandable.",
                                "~/.swim/config.json"),
                        section("Session restore",
                                "Normal launches open the requested file list or the welcome screen. :reload and :rebuild are different: they preserve the current buffers, workspaces, and split layout while the runtime restarts, so development reloads do not destroy editor state.",
                                ":reload\n:rebuild"),
                        section("Project roots",
                                "Project search, LSP support, and Nemo workspace tools use project-root detection. Git directories, build metadata, compile command files, and Swim project config help identify the root used for searches and sandbox boundaries.",
                                "Open a file inside the project before running :grep or asking Nemo to inspect workspace files."),
                        section("Discoverability",
                                "The top context bar shows available key paths for the active focus. Command completion appears while typing :. In help, the left index is always visible so you can treat the manual as both tutorial and command reference.",
                                ":help")));
    }
}
