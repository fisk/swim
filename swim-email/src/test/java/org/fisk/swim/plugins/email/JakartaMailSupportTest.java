package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class JakartaMailSupportTest {
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
