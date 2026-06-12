package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.SwimHomeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorTodoIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(75)
    void installedLauncherBinaryCreatesProjectsTagsAndPersistsTodoItems() throws Exception {
        Path file = tempDir.resolve("todo-fixture.txt");
        Files.writeString(file, "todo fixture\n");
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("todo fixture", STARTUP_TIMEOUT);

            session.sendKey("C-t");
            session.waitForText("Enter add", UI_TIMEOUT);
            session.sendLiteral("Cancelled from overlay");
            session.sendEscape();
            session.sendKey("C-t");
            session.waitForText("Enter add", UI_TIMEOUT);
            session.sendLiteral("Quick captured inbox");
            session.sendEnter();

            session.sendLiteral(" t");
            session.waitForText("Todo", UI_TIMEOUT);
            session.waitForText("Quick captured inbox", UI_TIMEOUT);
            session.sendLiteral("n");
            session.sendLiteral("Ship todo workspace");
            session.sendEnter();
            session.waitForText("Ship todo workspace", UI_TIMEOUT);

            session.sendLiteral("p");
            session.sendLiteral("Swim");
            session.sendEnter();
            session.sendLiteral("a");
            session.waitForText("Ship todo workspace", UI_TIMEOUT);
            session.waitForText("@Swim", UI_TIMEOUT);

            session.sendLiteral("g");
            session.sendLiteral("urgent ui");
            session.sendEnter();
            session.waitForText("#urgent", UI_TIMEOUT);
            session.waitForText("#ui", UI_TIMEOUT);

            session.sendEscape();
            session.waitForText("todo fixture", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        String rows = home.queryTodoH2("""
                select i.title, p.name, t.tag_name
                  from todo_items i
                  join todo_projects p on p.id = i.project_id
                  join todo_item_tags t on t.todo_id = i.id
                 order by t.tag_name
                """);
        assertTrue(rows.contains("Ship todo workspace"), rows);
        assertTrue(rows.contains("Swim"), rows);
        assertTrue(rows.contains("urgent"), rows);
        assertTrue(rows.contains("ui"), rows);

        String inboxRows = home.queryTodoH2("""
                select title
                  from todo_items
                 where title = 'Quick captured inbox'
                   and project_id is null
                """);
        assertTrue(inboxRows.contains("Quick captured inbox"), inboxRows);

        String cancelledRows = home.queryTodoH2("""
                select title
                  from todo_items
                 where title = 'Cancelled from overlay'
                """);
        assertFalse(cancelledRows.contains("Cancelled from overlay"), cancelledRows);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("todo fixture", STARTUP_TIMEOUT);

            session.sendLiteral(" t");
            session.waitForText("Todo", UI_TIMEOUT);
            session.waitForText("Quick captured inbox", UI_TIMEOUT);
            session.sendLiteral("a");
            session.waitForText("Ship todo workspace", UI_TIMEOUT);
            session.waitForText("@Swim", UI_TIMEOUT);
            session.waitForText("#urgent", UI_TIMEOUT);

            session.sendLiteral("q");
            session.waitForText("todo fixture", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }
}
