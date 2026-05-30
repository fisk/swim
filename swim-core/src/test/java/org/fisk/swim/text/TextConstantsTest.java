package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class TextConstantsTest {
    @Test
    void indentationUsesFourSpaces() {
        assertEquals("    ", Settings.getIndentationString());
    }

    @Test
    void javaIndentationUsesFourSpacesByDefault() {
        assertEquals("    ", Settings.getIndentationString("java"));
    }

    @Test
    void cppIndentationUsesTwoSpacesByDefault() {
        assertEquals("  ", Settings.getIndentationString("cpp"));
        assertEquals("  ", Settings.getIndentationString("c"));
    }

    @Test
    void indentationSizeCanBeOverriddenPerLanguage() {
        System.setProperty("swim.indent.java.size", "6");
        try {
            assertEquals("      ", Settings.getIndentationString("java"));
        } finally {
            System.clearProperty("swim.indent.java.size");
        }
    }

    @Test
    void powerlineSymbolsRemainStable() throws Exception {
        assertNotNull(new Powerline());
        assertEquals("\uE0A0", fieldValue("SYMBOL_BRANCH"));
        assertEquals("\uE0A1", fieldValue("SYMBOL_LN"));
        assertEquals("\uE0A3", fieldValue("SYMBOL_LOCK"));
        assertEquals("\uE0B0", fieldValue("SYMBOL_FILLED_RIGHT_ARROW"));
        assertEquals("\uE0B1", fieldValue("SYMBOL_RIGHT_ARROW"));
        assertEquals("\uE0B2", fieldValue("SYMBOL_FILLED_LEFT_ARROW"));
        assertEquals("\uE0B3", fieldValue("SYMBOL_LEFT_ARROW"));
    }

    private static String fieldValue(String name) throws Exception {
        Field field = Powerline.class.getDeclaredField(name);
        return (String) field.get(null);
    }
}
