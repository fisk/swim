package org.fisk.swim.api;

import java.util.List;

public record SwimHelpChapter(String id, String title, String summary, List<SwimHelpSection> sections) {
    public SwimHelpChapter {
        id = id == null ? "" : id.strip();
        title = title == null ? "" : title.strip();
        summary = summary == null ? "" : summary.strip();
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
