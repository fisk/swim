package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitBlameTest {
    @Test
    void parsesAuthorAndRevisionForEveryBlamedLine() {
        String porcelain = "0123456789abcdef 1 1 1\n"
                + "author Ada Lovelace\n"
                + "author-mail <ada@example.test>\n"
                + "\tfirst\n"
                + "0000000000000000000000000000000000000000 2 2 1\n"
                + "author Not Committed Yet\n"
                + "\tsecond\n";

        assertEquals(List.of("01234567 Ada Lovelace", "Not committed"), GitBlame.parse(porcelain));
    }
}
