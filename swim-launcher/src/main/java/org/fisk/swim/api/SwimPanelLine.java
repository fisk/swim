package org.fisk.swim.api;

import java.util.List;

public record SwimPanelLine(List<SwimTextSpan> spans) {
    public SwimPanelLine {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    public static SwimPanelLine plain(String text) {
        return new SwimPanelLine(List.of(SwimTextSpan.plain(text)));
    }

    public static SwimPanelLine of(SwimTextSpan... spans) {
        return new SwimPanelLine(spans == null ? List.of() : List.of(spans));
    }

    public String text() {
        StringBuilder builder = new StringBuilder();
        for (SwimTextSpan span : spans) {
            builder.append(span.text());
        }
        return builder.toString();
    }
}
