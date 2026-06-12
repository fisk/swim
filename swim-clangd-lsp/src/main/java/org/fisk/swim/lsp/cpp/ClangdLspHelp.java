package org.fisk.swim.lsp.cpp;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class ClangdLspHelp {
    private ClangdLspHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("clangd-lsp", "C/C++ LSP",
                "clangd setup, compile database behavior, and C/C++ navigation helpers.",
                List.of(
                        section("When clangd starts",
                                "Opening a C or C++ file makes the clangd plugin look for compile_commands.json in the project root or a build directory. clangd needs that compile database to know include paths, language standards, defines, and generated headers. Without it, SWIM still opens the file, but the plugin avoids starting clangd because diagnostics and navigation would be misleading.",
                                "cmake -S . -B build -DCMAKE_EXPORT_COMPILE_COMMANDS=ON\n:e src/main.cpp"),
                        section("Using shared LSP features in C and C++",
                                "The shared Space-comma LSP commands are especially useful in C++ because declarations, definitions, overrides, and template instantiations often live far apart. For example, use Space-comma-D to inspect a declaration from a call site, Space-comma-d to jump to the definition, and Space-comma-u to see every reference before changing a public API.",
                                "<SPACE> , D\n<SPACE> , d\n<SPACE> , u"),
                        section("Switching between header and implementation",
                                "Use g m in a C or C++ buffer to switch between related source and header files. This is useful when you are editing a declaration in a header and want the implementation, or when you are in a .cpp file and need to adjust the public signature. SWIM searches common sibling and project include/source layouts using .h, .hh, .hpp, .hxx, .c, .cc, .cpp, and .cxx names.",
                                "gm"),
                        section("Choosing a debugger with clangd",
                                "clangd is only the language server. To run or step through a compiled program, use the C/C++ debugger plugin with :debug cpp. A common workflow is to use clangd diagnostics and navigation while editing, build the executable, then launch :debug cpp with the path to that executable.",
                                ":debug cpp launch build/my-program")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
