package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaCompletionSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void sortsPreselectedEntriesAheadOfSortTextAndTracksSelectionWindow() throws IOException {
        var context = createContext("prin");

        var alpha = item("alpha", "002", false, "(int)", "Demo");
        var beta = item("beta", "010", false, "", "Demo");
        var gamma = item("gamma", "999", true, "", "Demo");

        var session = JavaCompletionSession.create(context, "prin", 0, 4, List.of(beta, gamma, alpha), true);

        assertEquals(List.of("gamma", "alpha", "beta"),
                session.getEntries().stream().map(JavaCompletionSession.Entry::getLabel).toList());
        assertEquals(0, session.getSelection());
        assertEquals("gamma", session.getSelectedEntry().getLabel());

        session.moveSelection(2);

        assertEquals(2, session.getSelection());
        assertEquals("beta", session.getSelectedEntry().getLabel());
    }

    private BufferContext createContext(String text) throws IOException {
        Path path = tempDir.resolve("completion-session.txt");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, 40, 8), path);
    }

    private static CompletionItem item(
            String label,
            String sortText,
            boolean preselect,
            String detail,
            String description) {
        var item = new CompletionItem(label);
        item.setSortText(sortText);
        item.setPreselect(preselect);
        var labelDetails = new CompletionItemLabelDetails();
        labelDetails.setDetail(detail);
        labelDetails.setDescription(description);
        item.setLabelDetails(labelDetails);
        return item;
    }
}
