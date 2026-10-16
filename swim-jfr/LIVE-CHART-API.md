# Reusable live chart API

Exported module `org.fisk.swim.jfr`, package `org.fisk.swim.plugins.jfrmetrics`.

```java
var panel = new LiveTimeSeriesPanel("ahs", "AHS comparison");
panel.onKey("a", "run A", () -> startBenchmark("A"));
panel.onKey("b", "run B", () -> startBenchmark("B"));
panel.startRun("A"); // replaces A only, retains B
panel.append("A", elapsedSeconds, systemCpuPercent, systemUsedMemoryBytes);
panel.append("A", elapsedSeconds, systemCpuPercent, systemUsedMemoryBytes, gcDemandRatio);
```

`LiveTimeSeriesPanel` implements `SwimPanel`; register it using the existing plugin
panel facility. `append` is thread-safe, invalidates render caches, and accepts
nondecreasing elapsed seconds per run. CPU is percent (0..100), memory is bytes;
NaN CPU / negative memory indicate unavailable. The five-argument overload accepts
an optional nonnegative GC-demand ratio (or NaN); when present it is drawn as a cyan
line on the System chart and expands its left percent axis as needed. Named runs are retained until
`startRun(name)` replaces them or `removeRun(name)` removes them. Each run retains
100,000 samples by default; constructor overload accepts a different bound.
Key callbacks run outside the model lock on the input thread: schedule long work
on a plugin executor. Host owns redraw scheduling and callback permission policy.

Acquisition belongs to the private plugin: for the requested in-process recorder,
use `RecordingStream` for the benchmark duration, enable `jdk.CPULoad` and
`jdk.PhysicalMemory`, use `machineTotal * 100` and `usedSize`, respectively. Keep
latest values and append at event timestamps relative to the run origin. Close
the stream when the run stops; no external process or JFR snapshot is necessary.
The public chart has no knowledge of the benchmark or recorder lifetime.

The existing :jfr path and live-recording behavior is unchanged. Both adapters
share the same Braille dual-axis renderer; the general live adapter displays only
system CPU and system used memory, with optional GC demand, not JVM metrics.
# Optional per-JVM heap overlay

`append(name, seconds, cpuPercent, memoryBytes, gcDemand, heapCapacityBytes)`
accepts optional heap capacity (negative means unavailable).
`showHeapCapacity(true)` switches the cyan overlay from GC demand on the left
percentage axis to heap capacity on the right bytes axis; `false` restores demand.
The shared right scale covers both system memory and heap capacity. Existing
append overloads and the default demand rendering are unchanged.
