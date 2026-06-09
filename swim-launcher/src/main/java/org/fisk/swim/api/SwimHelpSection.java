package org.fisk.swim.api;

import java.util.List;

public record SwimHelpSection(String title, List<String> paragraphs, String example) {
    public SwimHelpSection {
        title = title == null ? "" : title.strip();
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        example = example == null ? "" : example;
    }
}
