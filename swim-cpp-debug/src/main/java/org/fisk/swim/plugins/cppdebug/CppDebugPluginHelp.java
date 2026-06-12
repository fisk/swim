package org.fisk.swim.plugins.cppdebug;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class CppDebugPluginHelp {
    private CppDebugPluginHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("cpp-debug", "C/C++ Debugger",
                "How to launch and control a C or C++ debugger session.",
                List.of(
                        section("Launching C/C++ debugging",
                                "Use :debug cpp with an executable, optional source root, and program arguments. The plugin chooses gdb or lldb when available, or you can request one explicitly with :debug cpp gdb or :debug cpp lldb. Build the executable first, then launch the debugger from a source file in the same project so source paths resolve naturally.",
                                ":debug providers\n:debug cpp launch build/app\n:debug cpp lldb build/app src"),
                        section("Controlling execution",
                                "The debugger panel uses the same controls as other debugger providers: c continues, n steps over, i steps into, o steps out, and s stops. B toggles a breakpoint at the current source line, and Enter on a thread or frame row selects it so the variable list follows that stack frame.",
                                "B\n:debug open\nc\nn\ni\no\ns")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
