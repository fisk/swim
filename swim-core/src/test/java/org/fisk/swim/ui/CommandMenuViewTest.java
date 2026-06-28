package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandMenuViewTest {
    @TempDir
    Path tempDir;

    @Test
    void commandMenuUsesMinimumHeightWhenDetailTextFits() throws IOException {
        Path path = tempDir.resolve("command-menu-fit.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 80, 10)) {
            var commandView = harness.getWindow().getCommandView();
            var menuView = HeadlessWindowHarness.getField(harness.getWindow(), "_commandMenuView", CommandMenuView.class);
            commandView.activate(":");
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('r'));

            menuView.setState(commandView.getMenuState());

            assertEquals(80, menuView.getBounds().getSize().getWidth());
            assertTrue(menuView.getBounds().getSize().getHeight() >= 3);
        }
    }

    @Test
    void commandMenuGrowsTallerWhenDetailTextNeedsMoreRowsAndSpaceExists() {
        var menuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        var matches = List.of(
                new CommandView.CommandSpec("wide-detail", List.of(), "",
                        "this detail intentionally needs several rows in a narrow command menu"),
                new CommandView.CommandSpec("wide-detail-two", List.of(), "",
                        "another intentionally long detail keeps the popup taller than the minimum"));

        menuView.setState(new CommandView.CommandMenuState(true, "wide", matches, 0));
        menuView.resize(Size.create(28, 9));

        assertEquals(28, menuView.getBounds().getSize().getWidth());
        assertTrue(menuView.getBounds().getSize().getHeight() > 3);
        assertTrue(menuView.getBounds().getSize().getHeight() <= 9);
    }

    @Test
    void commandMenuGrowthIsCappedByAvailableParentHeight() {
        var menuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        var matches = List.of(
                new CommandView.CommandSpec("reload", List.of(), "", "reload the latest built SWIM core"),
                new CommandView.CommandSpec("rebuild", List.of(), "", "rebuild and reload SWIM"));

        menuView.setState(new CommandView.CommandMenuState(true, "r", matches, 0));
        menuView.resize(Size.create(28, 4));

        assertEquals(28, menuView.getBounds().getSize().getWidth());
        assertEquals(4, menuView.getBounds().getSize().getHeight());
    }

    @Test
    void commandMenuAccountsForMultilineHeader() {
        var menuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        var matches = List.of(
                new CommandView.CommandSpec("approve", List.of(), "approval-1", "Allow once"),
                new CommandView.CommandSpec("deny", List.of(), "approval-1", "Deny"));

        menuView.setState(new CommandView.CommandMenuState(true, "", matches, 0,
                "approval options\nrun blocked command: printf ok; touch approved.txt"));
        menuView.resize(Size.create(40, 8));

        assertEquals(40, menuView.getBounds().getSize().getWidth());
        assertTrue(menuView.getBounds().getSize().getHeight() >= 4);
        assertTrue(menuView.getBounds().getSize().getHeight() <= 8);
    }

    @Test
    void commandMenuStaysAboveReservedBottomRows() {
        var menuView = new CommandMenuView(Rect.create(0, 0, 0, 0));
        var matches = List.of(
                new CommandView.CommandSpec("help", List.of("h"), "", "open the built-in help"),
                new CommandView.CommandSpec("history", List.of(), "", "show command history"));

        menuView.setState(new CommandView.CommandMenuState(true, "h", matches, 0));
        menuView.setBottomInsetRows(3);
        menuView.resize(Size.create(40, 10));

        assertTrue(menuView.getBounds().getSize().getHeight() <= 7);
        assertEquals(7, menuView.getBounds().getPoint().getY() + menuView.getBounds().getSize().getHeight());
    }
}
