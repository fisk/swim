package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

/** A three-way merge editing request issued by a plugin panel. */
public record SwimMergeResolution(Path path, String ours, String theirs, List<SwimTextRange> oursRanges,
        List<SwimTextRange> theirsRanges) {
    public SwimMergeResolution(Path path, String ours, String theirs) {
        this(path, ours, theirs, List.of(), List.of());
    }
}
