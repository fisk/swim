package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NemoConditionalEditTest {
    @Test
    void replacesOnlyExactRequestedLines() throws Exception {
        var change = NemoConditionalEdit.replaceLines("one\ntwo\nthree\n", 2, 2, "two", "updated");
        assertEquals("one\nupdated\nthree\n", change.after());
        assertThrows(java.io.IOException.class,
                () -> NemoConditionalEdit.replaceLines("one\nchanged\nthree\n", 2, 2, "two", "updated"));
    }

    @Test
    void replacesFunctionBodyOnlyWhenExpectedBodyMatches() throws Exception {
        var change = NemoConditionalEdit.replaceFunctionBody("void f() {\n  old();\n}\n", "f()", "\n  old();\n", "\n  next();\n");
        assertEquals("void f() {\n  next();\n}\n", change.after());
    }
}
