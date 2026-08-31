package org.fisk.swim.session;

/** Heap sample reported by the terminal client for a live session. */
public record SwimServerHeapUsage(long usedBytes, long committedBytes) {
    public SwimServerHeapUsage {
        usedBytes = Math.max(0, usedBytes);
        committedBytes = Math.max(1, Math.max(usedBytes, committedBytes));
    }
}
