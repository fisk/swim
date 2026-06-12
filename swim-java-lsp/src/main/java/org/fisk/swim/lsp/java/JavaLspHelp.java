package org.fisk.swim.lsp.java;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class JavaLspHelp {
    private JavaLspHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("java-lsp", "Java LSP",
                "Java-specific language-server setup and refactoring commands.",
                List.of(
                        section("When Java LSP starts",
                                "Opening a .java file loads the Java LSP plugin and starts the bundled in-process Java language server for the project root. The first answers can take longer because the server has to index and compile enough of the project to understand symbols. After that, diagnostics, semantic colors, completion, and the shared Space-comma LSP commands update asynchronously while you edit.",
                                ":e src/main/java/org/example/App.java"),
                        section("Organizing imports",
                                "Use Space-comma-o when imports are stale after moving code, pasting a type name, or removing references. The server computes a source edit that removes unused imports, sorts the remaining imports, and adds imports it can resolve. This is safer than sorting lines manually because the Java server understands static imports, package-local types, and project classpath entries.",
                                "<SPACE> , o"),
                        section("Java code generation helpers",
                                "The Java plugin also exposes a few generation commands under Space-e. Space-e-f asks the server to make the current field final when that refactor is valid. Space-e-a generates accessors for fields, and Space-e-s generates a toString method. Use these when you want the Java server to make the exact source edit instead of typing boilerplate by hand.",
                                "<SPACE> e f\n<SPACE> e a\n<SPACE> e s"),
                        section("Undoing Java server edits",
                                "Organize imports, generation, rename, formatting, and code actions all apply normal buffer edits. If the result is not what you wanted, press u immediately to return to the previous buffer state, then use Space-comma-a or hover to inspect why the server chose that change.",
                                "<SPACE> , o\nu")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
