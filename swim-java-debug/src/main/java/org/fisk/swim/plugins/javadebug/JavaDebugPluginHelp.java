package org.fisk.swim.plugins.javadebug;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;

final class JavaDebugPluginHelp {
    private JavaDebugPluginHelp() {
    }

    static SwimHelpChapter chapter() {
        return new SwimHelpChapter("java-debug", "Java Debugger",
                "How to launch and control a Java debugger session.",
                List.of(
                        section("Launching Java debugging",
                                "Use :debug java launch with a main class, class path, optional source root, and program arguments. The debugger panel opens after launch and shows breakpoints, threads, frames, and variables. Use :debug providers first when you want to confirm which debugger plugins are installed.",
                                ":debug providers\n:debug java launch com.example.Main target/classes src/main/java"),
                        section("Controlling execution",
                                "Inside the debugger panel, c continues, n steps over, i steps into, o steps out, and s stops the session. From a source buffer, B toggles a breakpoint on the current line when a debugger session is active. Select rows with j/k and press Enter to choose a thread or frame.",
                                "B\n:debug open\nc\nn\ni\no\ns")));
    }

    private static SwimHelpSection section(String title, String paragraph, String example) {
        return new SwimHelpSection(title, List.of(paragraph), example);
    }
}
