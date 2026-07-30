package org.fisk.swim.plugins.hotspotzgc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fisk.swim.debug.DebuggerCommandContext;
import org.fisk.swim.debug.DebuggerCommandExtension;

/** Read-only decoder for the current mainline ZGC zAddress/zBarrier layout. */
final class ZgcDebuggerCommand implements DebuggerCommandExtension {
    private static final long REMEMBERED_MASK = 0x30L;
    private static final long REMAPPED_MASK = 0x3c00L;
    private static final long FORWARDING_TO_OFFSET_MASK = (1L << 45) - 1;
    private static final int GRANULE_SHIFT = 21;
    private static final Pattern HEX = Pattern.compile("0x([0-9a-fA-F]+)");

    @Override public String id() { return "zgc"; }
    @Override public String description() { return "zgc phases | zgc zpointer <expression> | zgc remap <expression>"; }
    @Override public boolean handles(String command) { return command.equals("zgc") || command.startsWith("zgc "); }

    @Override
    public String execute(String command, DebuggerCommandContext context) throws Exception {
        String argument = command.substring(3).trim();
        if (argument.equals("phases")) return RuntimeState.read(context).renderPhases();
        if (argument.startsWith("zpointer ")) return inspect(context, argument.substring("zpointer ".length()));
        if (argument.startsWith("remap ")) return remap(context, argument.substring("remap ".length()));
        throw new IllegalArgumentException("Usage: zgc phases | zgc zpointer <colored-pointer-expression> | zgc remap <colored-pointer-expression>");
    }

    private static String inspect(DebuggerCommandContext context, String expression) throws Exception {
        if (expression.isBlank()) throw new IllegalArgumentException("zgc zpointer requires a colored pointer expression");
        RuntimeState state = RuntimeState.read(context);
        return state.describe(evaluateUnsigned(context, "(uintptr_t)(" + expression + ")"));
    }

    private static String remap(DebuggerCommandContext context, String expression) throws Exception {
        if (expression.isBlank()) throw new IllegalArgumentException("zgc remap requires a colored pointer expression");
        RuntimeState state = RuntimeState.read(context);
        long pointer = evaluateUnsigned(context, "(uintptr_t)(" + expression + ")");
        RemapResult remap = state.resolveLoadGood(pointer, context);
        String locations = remap.alreadyLoadGood()
                ? "\nload-good address: " + hex(remap.fromSpace()) + " (pointer is already load-good)"
                : "\nfrom-space address: " + hex(remap.fromSpace())
                        + (remap.toSpace() == null ? "\nto-space address: unavailable (not forwarded)"
                                : "\nto-space address: " + hex(remap.toSpace()));
        return state.describe(pointer) + locations + "\n(Java implementation of ZBarrier::make_load_good)";
    }

    private static long evaluateUnsigned(DebuggerCommandContext context, String expression) throws Exception {
        String response = context.executeBackendCommand("p/x " + expression);
        return lastHex(response, "Could not evaluate ZGC expression: " + expression);
    }

    private static long readWord(DebuggerCommandContext context, long address) throws Exception {
        // x/gx is a debugger memory read. It does not call into libjvm, and is
        // supported by both GDB and LLDB.
        return lastHex(context.executeBackendCommand("x/gx " + hex(address)), "Could not read ZGC memory at " + hex(address));
    }

    private static long lastHex(String response, String failure) {
        Matcher matcher = HEX.matcher(response);
        long value = 0;
        boolean found = false;
        while (matcher.find()) {
            value = Long.parseUnsignedLong(matcher.group(1), 16);
            found = true;
        }
        if (!found) throw new IllegalStateException(failure + "\n" + response.strip());
        return value;
    }

    private static String hex(long value) { return "0x" + Long.toUnsignedString(value, 16); }
    private static long alignUp(long value, long alignment) { return (value + alignment - 1) & -alignment; }

    private record RuntimeState(long remapped, long remappedYoungMask, long remappedOldMask,
            long markedYoung, long markedOld, long finalizable, long remembered,
            long loadBad, long markBad, long storeBad, long addressOffsetMask, long addressHeapBase,
            String youngPhase, String oldPhase) {
        static RuntimeState read(DebuggerCommandContext context) throws Exception {
            return new RuntimeState(
                    evaluateUnsigned(context, "ZPointerRemapped"), evaluateUnsigned(context, "ZPointerRemappedYoungMask"),
                    evaluateUnsigned(context, "ZPointerRemappedOldMask"), evaluateUnsigned(context, "ZPointerMarkedYoung"),
                    evaluateUnsigned(context, "ZPointerMarkedOld"), evaluateUnsigned(context, "ZPointerFinalizable"),
                    evaluateUnsigned(context, "ZPointerRemembered"), evaluateUnsigned(context, "ZPointerLoadBadMask"),
                    evaluateUnsigned(context, "ZPointerMarkBadMask"), evaluateUnsigned(context, "ZPointerStoreBadMask"),
                    evaluateUnsigned(context, "ZAddressOffsetMask"), evaluateUnsigned(context, "ZAddressHeapBase"),
                    generationPhase(context, "young"), generationPhase(context, "old"));
        }

        String renderPhases() {
            return "ZGC current epochs\n"
                    + "  young: phase=" + youngPhase + ", remap=" + hex(remappedYoungMask) + ", marked=" + hex(markedYoung) + "\n"
                    + "  old:   phase=" + oldPhase + ", remap=" + hex(remappedOldMask) + ", marked=" + hex(markedOld)
                    + ", finalizable=" + hex(finalizable) + "\n"
                    + "  remembered=" + hex(remembered) + ", active remap=" + hex(remapped);
        }

        String describe(long pointer) {
            long remapBits = rawRemapBits(pointer);
            return "ZGC colored pointer " + hex(pointer) + "\n"
                    + "  uncolored address: " + hex(uncolor(pointer)) + "\n"
                    + "  remap bits: " + hex(remapBits) + " (active=" + yes((remapBits & remapped) != 0) + ")\n"
                    + "  marked: young=" + yes((pointer & markedYoung) != 0) + ", old=" + yes((pointer & markedOld) != 0)
                    + ", finalizable=" + yes((pointer & finalizable) != 0) + "\n"
                    + "  remembered: " + yes((pointer & REMEMBERED_MASK) == remembered) + "\n"
                    + "  barriers: load=" + goodness(pointer, loadBad) + ", mark=" + goodness(pointer, markBad)
                    + ", store=" + goodness(pointer, storeBad) + "\n" + renderPhases();
        }

        RemapResult resolveLoadGood(long pointer, DebuggerCommandContext context) throws Exception {
            if (pointer == 0 || (pointer & loadBad) == 0) return new RemapResult(uncolor(pointer), uncolor(pointer), true);
            long address = uncolor(pointer);
            String generation = remapGeneration(pointer, address, context);
            long forwarding = forwardingFor(generation, address, context);
            if (forwarding == 0) return new RemapResult(address, null, false);
            ForwardingLookup lookup = lookupForwardedOffset(forwarding, address & addressOffsetMask, context);
            return lookup.found() ? new RemapResult(address, addressHeapBase | lookup.toOffset(), false)
                    : new RemapResult(address, null, false);
        }

        private String remapGeneration(long pointer, long address, DebuggerCommandContext context) throws Exception {
            long bits = rawRemapBits(pointer);
            if ((bits & remappedOldMask) != 0) return "young";
            if ((bits & remappedYoungMask) != 0) return "old";
            if ((pointer & REMEMBERED_MASK) == REMEMBERED_MASK) return "old";
            // Exact ZBarrier::remap_generation fallback: consult the young
            // forwarding table; the old table is mutually exclusive.
            return forwardingFor("young", address, context) != 0 ? "young" : "old";
        }

        private long forwardingFor(String generation, long address, DebuggerCommandContext context) throws Exception {
            long generationAddress = evaluateUnsigned(context, "(uintptr_t)ZGeneration::_" + generation);
            if (generationAddress == 0) throw new IllegalStateException("ZGC " + generation + " generation is unavailable in this core");
            long map = evaluateUnsigned(context, "(uintptr_t)((ZGeneration*)" + hex(generationAddress) + ")->_forwarding_table._map._map");
            return readWord(context, map + ((address & addressOffsetMask) >>> GRANULE_SHIFT) * Long.BYTES);
        }

        private ForwardingLookup lookupForwardedOffset(long forwarding, long fromOffset, DebuggerCommandContext context) throws Exception {
            long start = evaluateUnsigned(context, "(uintptr_t)((ZForwarding*)" + hex(forwarding) + ")->_virtual._start");
            long alignmentShift = evaluateUnsigned(context, "(uintptr_t)((ZForwarding*)" + hex(forwarding) + ")->_object_alignment_shift");
            long entryCount = evaluateUnsigned(context, "(uintptr_t)((ZForwarding*)" + hex(forwarding) + ")->_entries._length");
            long forwardingSize = evaluateUnsigned(context, "sizeof(ZForwarding)");
            if (entryCount == 0 || (entryCount & (entryCount - 1)) != 0) throw new IllegalStateException("Invalid ZGC forwarding table size: " + entryCount);
            long fromIndex = (fromOffset - start) >>> (int) alignmentShift;
            long cursor = Integer.toUnsignedLong(hash32((int) fromIndex)) & (entryCount - 1);
            long entries = forwarding + alignUp(forwardingSize, Long.BYTES);
            for (long probes = 0; probes < entryCount; probes++, cursor = (cursor + 1) & (entryCount - 1)) {
                long entry = readWord(context, entries + cursor * Long.BYTES);
                if ((entry & 1) == 0) return new ForwardingLookup(false, 0);
                if ((entry >>> 46) == fromIndex) return new ForwardingLookup(true, (entry >>> 1) & FORWARDING_TO_OFFSET_MASK);
            }
            return new ForwardingLookup(false, 0);
        }

        long uncolor(long pointer) {
            int index = (int) ((pointer >>> 10) & 0xf);
            int shift = switch (index) {
            case 0 -> 20; case 1 -> 11; case 2 -> 12; case 4 -> 13; case 8 -> 14;
            default -> throw new IllegalArgumentException("Invalid ZGC remap-bit pattern " + hex(pointer & REMAPPED_MASK));
            };
            return pointer >>> shift;
        }

        private long rawRemapBits(long pointer) {
            boolean aarch64 = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch64");
            long bits = pointer & REMAPPED_MASK;
            return aarch64 ? bits ^ REMAPPED_MASK : bits;
        }

        private static String yes(boolean value) { return value ? "yes" : "no"; }
        private static String goodness(long pointer, long badMask) { return (pointer & badMask) == 0 ? "good" : "bad"; }
    }

    private record ForwardingLookup(boolean found, long toOffset) {
    }

    private record RemapResult(long fromSpace, Long toSpace, boolean alreadyLoadGood) {
    }

    private static String generationPhase(DebuggerCommandContext context, String generation) throws Exception {
        long address = evaluateUnsigned(context, "(uintptr_t)ZGeneration::_" + generation);
        long phase = evaluateUnsigned(context, "(uintptr_t)((ZGeneration*)" + hex(address) + ")->_phase");
        return switch ((int) phase) {
        case 0 -> "Mark";
        case 1 -> "MarkComplete";
        case 2 -> "Relocate";
        default -> "unknown(" + phase + ")";
        };
    }

    // ZHash::uint32_to_uint32 from current mainline zHash.inline.hpp.
    private static int hash32(int key) {
        key = ~key + (key << 15);
        key ^= key >>> 12;
        key += key << 2;
        key ^= key >>> 4;
        key *= 2057;
        return key ^ (key >>> 16);
    }

}
