package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class JakartaMailSupportTest {
    @Test
    void syncStartIndexUsesMostRecentWindowWhenMailboxExceedsCap() {
        assertEquals(1, JakartaMailSupport.syncStartIndex(0, 250));
        assertEquals(1, JakartaMailSupport.syncStartIndex(100, 250));
        assertEquals(251, JakartaMailSupport.syncStartIndex(500, 250));
        assertEquals(1, JakartaMailSupport.syncStartIndex(500, 0));
    }

    @Test
    void syncStatusMentionsSkippedOlderMessagesWhenWindowIsCapped() {
        assertEquals("0 messages", JakartaMailSupport.syncStatus(0, 0, 1));
        assertEquals("25 messages", JakartaMailSupport.syncStatus(25, 25, 1));
        assertEquals("Fetched latest 250 of 500 messages (250 older messages skipped)",
                JakartaMailSupport.syncStatus(250, 500, 251));
    }

    @Test
    void extractTextReadsInputStreamContent() throws Exception {
        var method = JakartaMailSupport.class.getDeclaredMethod("readContentAsString", Object.class);
        method.setAccessible(true);

        String text = (String) method.invoke(null,
                new ByteArrayInputStream("Hello from stream".getBytes(StandardCharsets.UTF_8)));

        assertEquals("Hello from stream", text);
    }

    @Test
    void importedMessageBodyPreservesNewlinesForStorage() throws Exception {
        String rawBody = "line one\nline two\n\nline four";
        String normalizedSnippet = rawBody.replaceAll("\\s+", " ").trim();

        var method = JakartaMailSupport.class.getDeclaredMethod("normalizeWhitespace", String.class);
        method.setAccessible(true);

        assertEquals("line one\nline two\n\nline four", rawBody);
        assertEquals("line one line two line four", method.invoke(null, rawBody));
        assertEquals("line one line two line four", normalizedSnippet);
    }
}
