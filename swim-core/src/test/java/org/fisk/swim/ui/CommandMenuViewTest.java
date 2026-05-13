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
            assertEquals(3, menuView.getBounds().getSize().getHeight());
        }
    }

    @Test
    void commandMenuGrowsTallerWhenDetailTextNeedsMoreRowsAndSpaceExists() throws IOException {
        Path path = tempDir.resolve("command-menu-grow.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 28, 10)) {
            var commandView = harness.getWindow().getCommandView();
            var menuView = HeadlessWindowHarness.getField(harness.getWindow(), "_commandMenuView", CommandMenuView.class);
            commandView.activate(":");
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('r'));

            menuView.setState(commandView.getMenuState());

            assertEquals(28, menuView.getBounds().getSize().getWidth());
            assertTrue(menuView.getBounds().getSize().getHeight() > 3);
            assertEquals(6, menuView.getBounds().getSize().getHeight());
        }
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
}
