package org.fisk.swim.treesitter;

import java.util.List;

/** Read-only assessment used to gate a grammar before the subset runtime is asked to parse it. */
public record TreeSitterSubsetCompatibility(List<Issue> issues) {
    public record Issue(String rule, String feature, String message) {
        public Issue {
            rule = rule == null ? "" : rule;
            feature = feature == null ? "" : feature;
            message = message == null ? "" : message;
        }
    }

    public TreeSitterSubsetCompatibility {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean supported() {
        return issues.isEmpty();
    }
}
