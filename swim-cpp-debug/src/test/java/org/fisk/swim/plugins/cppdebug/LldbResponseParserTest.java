package org.fisk.swim.plugins.cppdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LldbResponseParserTest {
    @Test
    void parsesBreakpointThreadFrameAndVariables() {
        String breakpoint = "Breakpoint 1: where = main`main + 32 at main.cpp:4:11, address = 0x0001";
        assertEquals("1", LldbResponseParser.breakpointId(breakpoint));

        String threadList = """
                Process 28531 stopped
                * thread #1: tid = 0x54f31b5, 0x00000001000004b8 main`main at main.cpp:4:11, queue = 'com.apple.main-thread', stop reason = breakpoint 1.1
                """;
        assertEquals(1, LldbResponseParser.threads(threadList).size());
        assertTrue(LldbResponseParser.threads(threadList).getFirst().suspended());

        String backtrace = """
                * thread #1, queue = 'com.apple.main-thread', stop reason = breakpoint 1.1
                  * frame #0: 0x00000001000004b8 main`main at main.cpp:4:11
                    frame #1: 0x0000000182042b98 dyld`start + 6076
                """;
        assertEquals(2, LldbResponseParser.frames(backtrace, Path.of("/tmp")).size());
        assertEquals(4, LldbResponseParser.frames(backtrace, Path.of("/tmp")).getFirst().location().line());

        String variables = """
                (int) value = 1
                (const char *) name = "demo"
                """;
        assertEquals(2, LldbResponseParser.variables(variables).size());
        assertEquals("value", LldbResponseParser.variables(variables).getFirst().name());
    }
}
