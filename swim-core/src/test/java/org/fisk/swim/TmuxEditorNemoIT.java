package org.fisk.swim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.SwimHomeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorNemoIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinarySupportsNemoCommandModeWorkflow() throws Exception {
        Path file = tempDir.resolve("nemo.txt");
        Files.writeString(file, "nemo buffer\n");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        home.writeNemoConfig("""
                {
                  "api_key": ""
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("nemo buffer", STARTUP_TIMEOUT);

            session.sendLiteral("!");
            session.waitForText("type a message or :abort", UI_TIMEOUT);

            session.sendLiteral(":help");
            session.sendEnter();
            session.waitForText("Available commands:", UI_TIMEOUT);

            session.sendLiteral(":workers");
            session.sendEnter();
            session.waitForText("No Nemo workers running.", UI_TIMEOUT);

            session.sendLiteral(":new Scratch");
            session.sendEnter();
            session.waitForText("Created session-", UI_TIMEOUT);
            session.waitForText("Scratch", UI_TIMEOUT);

            session.sendLiteral(":rename Renamed");
            session.sendEnter();
            session.waitForText("Renamed session-", UI_TIMEOUT);
            session.waitForText("Renamed", UI_TIMEOUT);

            session.sendLiteral(":sessions");
            session.sendEnter();
            session.waitForText("Sessions:", UI_TIMEOUT);
            session.waitForText("Renamed", UI_TIMEOUT);

            session.sendLiteral(":switch");
            session.sendEnter();
            session.waitForText("Usage: :switch <session-id>", UI_TIMEOUT);

            session.sendLiteral(":delete");
            session.sendEnter();
            session.waitForText("Deleted session-", UI_TIMEOUT);

            session.sendLiteral(":q");
            session.sendEnter();
            session.waitForText("nemo buffer", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryShowsMissingApiKeyMessageForNemoPrompt() throws Exception {
        Path file = tempDir.resolve("nemo-message.txt");
        Files.writeString(file, "nemo message buffer\n");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        home.writeNemoConfig("""
                {
                  "api_key": ""
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("nemo message buffer", STARTUP_TIMEOUT);

            session.sendLiteral("!");
            session.waitForText("type a message or :abort", UI_TIMEOUT);
            session.sendLiteral("hello nemo");
            session.sendEnter();
            session.waitForText("Set api_key in", UI_TIMEOUT);

            session.sendEscape();
            session.waitForText("nemo message buffer", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryShowsNemoUnknownSessionAndUnknownCommandMessages() throws Exception {
        Path file = tempDir.resolve("nemo-errors.txt");
        Files.writeString(file, "nemo errors buffer\n");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        home.writeNemoConfig("""
                {
                  "api_key": ""
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("nemo errors buffer", STARTUP_TIMEOUT);

            session.sendLiteral("!");
            session.waitForText("type a message or :abort", UI_TIMEOUT);
            session.sendLiteral(":switch missing-session");
            session.sendEnter();
            session.waitForText("Unknown session: missing-session", UI_TIMEOUT);
            session.sendLiteral(":bogus");
            session.sendEnter();
            session.waitForText("Unknown command: :bogus", UI_TIMEOUT);
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryShowsNemoAbortMessageWhenNothingIsRunning() throws Exception {
        Path file = tempDir.resolve("nemo-abort.txt");
        Files.writeString(file, "nemo abort buffer\n");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        home.writeNemoConfig("""
                {
                  "api_key": ""
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("nemo abort buffer", STARTUP_TIMEOUT);

            session.sendLiteral("!");
            session.waitForText("type a message or :abort", UI_TIMEOUT);
            session.sendLiteral(":abort");
            session.sendEnter();
            session.waitForText("Nothing to abort.", UI_TIMEOUT);
        }
    }
}
