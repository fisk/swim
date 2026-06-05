package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.fileindex.ProjectSearch;

record SearchLocationList(
        String title,
        List<ProjectSearch.Match> matches,
        int selection) {
    SearchLocationList {
        matches = matches == null ? List.of() : List.copyOf(matches);
        selection = matches.isEmpty() ? 0 : Math.max(0, Math.min(selection, matches.size() - 1));
    }

    static SearchLocationList empty(String title) {
        return new SearchLocationList(title, List.of(), 0);
    }
}
