package org.fisk.swim.lsp.shared;

import java.util.List;
import java.util.Objects;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpRegistry;
import org.fisk.swim.api.SwimHelpSection;
import org.fisk.swim.api.SwimPluginPreloadContext;

public final class LspHelp {
    private static final String HELP_OWNER = "swim-lsp";
    private static final Object LOCK = new Object();
    private static int _leases;
    private static AutoCloseable _registration;

    private LspHelp() {
    }

    public static AutoCloseable registerSharedChapter(SwimPluginPreloadContext context) {
        Objects.requireNonNull(context, "context");
        return context.registerPreloadResource(acquireSharedChapter());
    }

    public static SwimHelpChapter chapter() {
        return new SwimHelpChapter("lsp", "Language Server Features",
                "A tutorial for asynchronous Java and C/C++ language-server commands.",
                List.of(
                        section("What an LSP command is",
                                "A language server answers questions about a parsed project: what symbol is under the cursor, where it is defined, what edits fix a warning, or how a document should be formatted. SWIM sends these requests from a snapshot of the buffer. You can keep typing while the server works; stale answers are ignored when the buffer has moved on.",
                                "Open a Java, C, or C++ file and wait for diagnostics or semantic colors before trying the LSP keys."),
                        section("Reading the symbol under the cursor",
                                "Use Space-comma-h for hover when you want documentation, inferred type information, or a short declaration without leaving the file. For example, put the cursor on a method call you do not recognize and press Space-comma-h to see the server's description. Use Space-comma-p while typing an argument list to see signature help, which is useful when overloads or parameter order are easy to forget.",
                                "<SPACE> , h\n<SPACE> , p"),
                        section("Jumping through code",
                                "Use g d for the fast definition jump and g r for references. The longer Space-comma navigation keys are useful when you need a specific LSP relation: d jumps to a definition, D to a declaration, y to the type definition, i to an implementation, and u to references. For example, on an interface method, Space-comma-i can take you to implementations while Space-comma-d may stay on the interface declaration.",
                                "gd\ngr\n<SPACE> , d\n<SPACE> , D\n<SPACE> , y\n<SPACE> , i\n<SPACE> , u"),
                        section("Finding symbols and highlights",
                                "Use Space-comma-H when you want every read or write of the current symbol highlighted in the current document. Use Space-comma-s to list document symbols such as classes, functions, methods, and fields in the current file. Use Space-comma-S when you remember a symbol name but not the file; SWIM prompts for a workspace-symbol query and then opens a result list.",
                                "<SPACE> , H\n<SPACE> , s\n<SPACE> , S"),
                        section("Applying server-suggested edits",
                                "Use Space-comma-a on a line with a diagnostic or refactor opportunity to ask the server for code actions. This is the right command for fixes such as adding a missing import, changing a type, generating a switch arm, or applying a quick refactor. Use Space-comma-R for rename when every reference in the project should change together. LSP edits go through SWIM's normal undo path, so u should revert the applied edit.",
                                "<SPACE> , a\n<SPACE> , R\nu"),
                        section("Formatting code",
                                "Use Space-comma-f to format the whole buffer after a larger edit or before committing a file. Use Space-comma-F when only the current line should be normalized. Space-comma-t asks for on-type formatting after a trigger character, which is useful in languages where typing a semicolon or closing brace can reindent the current construct.",
                                "<SPACE> , f\n<SPACE> , F\n<SPACE> , t"),
                        section("Understanding structure",
                                "Use Space-comma-l for code lenses when the server provides inline commands such as run test, references, or generated metadata. Use Space-comma-n for inlay hints when types or parameter names are not obvious from the code. Use Space-comma-z to create folds from server folding ranges, and Space-comma-v to inspect nested selection ranges around the cursor before growing a selection by syntax rather than by characters.",
                                "<SPACE> , l\n<SPACE> , n\n<SPACE> , z\n<SPACE> , v"),
                        section("Exploring relationships and links",
                                "Use Space-comma-c for call hierarchy when you need callers and callees of a function. Use Space-comma-T for type hierarchy when you need superclass, subclass, interface, or implementation relationships. Use Space-comma-m to list document links, such as include paths or URLs the server recognized. Space-comma-k shows linked editing ranges, and Space-comma-C offers color presentations when the cursor is on a color literal.",
                                "<SPACE> , c\n<SPACE> , T\n<SPACE> , m\n<SPACE> , k\n<SPACE> , C"),
                        section("Working with LSP result views",
                                "Most LSP commands open a small result view instead of immediately changing the file. Move through entries with j/k or the arrow keys, press Enter to jump or apply the selected result, and press q or Esc to close without taking action. If a server does not support a feature, SWIM reports that in the status line instead of blocking the editor.",
                                "j\nk\n<ENTER>\nq")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }

    private static AutoCloseable acquireSharedChapter() {
        synchronized (LOCK) {
            if (_leases == 0) {
                _registration = SwimHelpRegistry.register(HELP_OWNER, chapter());
            }
            _leases++;
        }
        return new Lease();
    }

    private static final class Lease implements AutoCloseable {
        private boolean _closed;

        @Override
        public void close() throws Exception {
            AutoCloseable registrationToClose = null;
            synchronized (LOCK) {
                if (_closed) {
                    return;
                }
                _closed = true;
                _leases = Math.max(0, _leases - 1);
                if (_leases == 0) {
                    registrationToClose = _registration;
                    _registration = null;
                }
            }
            if (registrationToClose != null) {
                registrationToClose.close();
            }
        }
    }
}
