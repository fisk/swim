package org.fisk.swim.plugins.hotspotzgc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Map;

import org.fisk.swim.debug.DebugSnapshot;
import org.fisk.swim.debug.DebuggerCommandContext;
import org.junit.jupiter.api.Test;

class ZgcDebuggerCommandTest {
    @Test
    void usesExplicitSubcommandsAndReadsCurrentRuntimeState() throws Exception {
        var commands = new ArrayList<String>();
        String result = new ZgcDebuggerCommand().execute("zgc zpointer pointer", context(commands));
        assertTrue(result.contains("ZGC colored pointer 0x100000440"));
        assertTrue(result.contains("ZGC current epochs"));
        assertTrue(commands.stream().anyMatch(command -> command.contains("ZPointerLoadBadMask")));
    }

    @Test
    void remapReadsForwardingStateWithoutCallingIntoLibjvm() throws Exception {
        var commands = new ArrayList<String>();
        String result = new ZgcDebuggerCommand().execute("zgc remap pointer", context(commands));
        assertTrue(result.contains("Java implementation of ZBarrier::make_load_good"));
        assertTrue(result.contains("from-space address:"));
        assertTrue(result.contains("to-space address: unavailable (not forwarded)"));
        assertTrue(commands.stream().anyMatch(command -> command.startsWith("x/gx ")));
        assertFalse(commands.stream().anyMatch(command -> command.contains("make_load_good") || command.contains("remap_object")));
    }

    private static DebuggerCommandContext context(ArrayList<String> commands) {
        var values = Map.ofEntries(
                Map.entry("ZPointerRemapped", 0x400L), Map.entry("ZPointerRemappedYoungMask", 0x400L), Map.entry("ZPointerRemappedOldMask", 0x800L),
                Map.entry("ZPointerMarkedYoung", 0x40L), Map.entry("ZPointerMarkedOld", 0x100L), Map.entry("ZPointerFinalizable", 0x10L),
                Map.entry("ZPointerRemembered", 0x10L), Map.entry("ZPointerLoadBadMask", 0x400L), Map.entry("ZPointerMarkBadMask", 0x3BF0L),
                Map.entry("ZPointerStoreBadMask", 0x3BF0L), Map.entry("ZAddressOffsetMask", 0x7fffffffffffL), Map.entry("ZAddressHeapBase", 0x100000000000L));
        return new DebuggerCommandContext() {
            @Override public DebugSnapshot snapshot() { return DebugSnapshot.empty("test"); }
            @Override public String executeBackendCommand(String command) {
                commands.add(command);
                if (command.startsWith("x/gx ")) return "0x3000: 0x0"; // no forwarding entry
                String expression = command.substring("p/x ".length());
                long value = values.getOrDefault(expression,
                        expression.contains("pointer") ? 0x100000440L
                        : expression.endsWith("::_young") ? 0x1000L
                        : expression.endsWith("::_old") ? 0x2000L
                        : expression.contains("->_phase") ? 2L : 0L);
                return "$1 = 0x" + Long.toHexString(value);
            }
        };
    }
}
