package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SwimLoggingTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(SwimLogging.LOG_LEVEL_PROPERTY);
    }

    @Test
    void configuredLogLevelDefaultsToInfo() {
        assertEquals(Level.INFO, SwimLogging.configuredLogLevel());
    }

    @Test
    void configuredLogLevelReadsKnownValue() {
        System.setProperty(SwimLogging.LOG_LEVEL_PROPERTY, "warn");

        assertEquals(Level.WARN, SwimLogging.configuredLogLevel());
    }

    @Test
    void configuredLogLevelFallsBackToInfoForInvalidValue() {
        System.setProperty(SwimLogging.LOG_LEVEL_PROPERTY, "not-a-level");

        assertEquals(Level.INFO, SwimLogging.configuredLogLevel());
    }
}
