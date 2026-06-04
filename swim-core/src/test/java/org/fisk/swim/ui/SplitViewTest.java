package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitViewTest {
    @TempDir
    Path tempDir;

    @Test
    void horizontalSplitDistributesWidthAcrossChildren() {
        var left = new View(Rect.create(0, 0, 0, 0));
        var right = new View(Rect.create(0, 0, 0, 0));
        var split = new SplitView(Rect.create(0, 0, 20, 7), SplitView.Orientation.HORIZONTAL, left, right);

        assertEquals("{0, 0, 9, 6}", left.getBounds().toString());
        assertEquals("{10, 0, 10, 6}", right.getBounds().toString());

        split.setBounds(Rect.create(0, 0, 21, 7));

        assertEquals("{0, 0, 10, 6}", left.getBounds().toString());
        assertEquals("{11, 0, 10, 6}", right.getBounds().toString());
    }

    @Test
    void verticalSplitDistributesHeightAcrossChildren() {
        var top = new View(Rect.create(0, 0, 0, 0));
        var bottom = new View(Rect.create(0, 0, 0, 0));
        var split = new SplitView(Rect.create(0, 0, 12, 7), SplitView.Orientation.VERTICAL, top, bottom,
                2.0 / 3.0);

        assertEquals("{0, 0, 12, 3}", top.getBounds().toString());
        assertEquals("{0, 5, 12, 1}", bottom.getBounds().toString());
        assertSame(bottom, split.getSibling(top));
    }

    @Test
    void drawRendersVisibleDividerBetweenHorizontalFrames() {
        var terminal = TerminalContextTestSupport.install(20, 7);
        try {
            var left = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 20, 7), SplitView.Orientation.HORIZONTAL, left, right);

            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 9 && "│".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawRendersFrameBarWithBufferName() throws Exception {
        var terminal = TerminalContextTestSupport.install(80, 7);
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve(".swim"));
        Path first = project.resolve("src/a.txt");
        Path second = project.resolve("src/b.txt");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "alpha\n");
        Files.writeString(second, "beta\n");
        var left = new BufferContext(Rect.create(0, 0, 0, 0), first);
        var right = new BufferContext(Rect.create(0, 0, 0, 0), second);
        try {
            var split = new SplitView(Rect.create(0, 0, 80, 7), SplitView.Orientation.HORIZONTAL,
                    left.getBufferView(), right.getBufferView());

            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream().anyMatch(call -> call.text().contains("src/a.txt")));
            assertTrue(terminal.drawCalls().stream().anyMatch(call -> call.text().contains("src/b.txt")));
        } finally {
            left.getBuffer().close();
            right.getBuffer().close();
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawUsesTopTeeWhenNestedVerticalDividerStartsBelowHorizontalSeparator() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var top = new View(Rect.create(0, 0, 0, 0));
            var bottom = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.VERTICAL, top, bottom, 0.5);

            split.split(bottom, right, SplitView.Orientation.HORIZONTAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 11 && call.y() == 3 && "┬".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawUsesBottomTeeWhenNestedVerticalDividerEndsAtHorizontalSeparator() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var top = new View(Rect.create(0, 0, 0, 0));
            var bottom = new View(Rect.create(0, 0, 0, 0));
            var left = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.VERTICAL, top, bottom, 0.5);

            split.split(top, left, SplitView.Orientation.HORIZONTAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 11 && call.y() == 3 && "┴".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawUsesCrossWhenVerticalDividerPassesThroughHorizontalSeparatorsOnBothSides() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var left = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var leftBottom = new View(Rect.create(0, 0, 0, 0));
            var rightBottom = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.HORIZONTAL, left, right, 0.5);

            split.split(left, leftBottom, SplitView.Orientation.VERTICAL, 0.5, true);
            split.split(right, rightBottom, SplitView.Orientation.VERTICAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 11 && call.y() == 3 && "┼".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawDoesNotUseCrossWhereDividerOnlyMeetsLowerSiblingFrameBars() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var top = new View(Rect.create(0, 0, 0, 0));
            var bottom = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.VERTICAL, top, bottom, 0.5);

            split.split(bottom, right, SplitView.Orientation.HORIZONTAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .noneMatch(call -> call.x() == 11 && call.y() == 6 && "┼".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawUsesRightJoinWhenOnlyRightSubtreeHasHorizontalSeparator() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var left = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var rightBottom = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.HORIZONTAL, left, right, 0.5);

            split.split(right, rightBottom, SplitView.Orientation.VERTICAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 11 && call.y() == 3 && "├".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void drawUsesLeftJoinWhenOnlyLeftSubtreeHasHorizontalSeparator() {
        var terminal = TerminalContextTestSupport.install(24, 7);
        try {
            var left = new View(Rect.create(0, 0, 0, 0));
            var leftBottom = new View(Rect.create(0, 0, 0, 0));
            var right = new View(Rect.create(0, 0, 0, 0));
            var split = new SplitView(Rect.create(0, 0, 24, 7), SplitView.Orientation.HORIZONTAL, left, right, 0.5);

            split.split(left, leftBottom, SplitView.Orientation.VERTICAL, 0.5, true);
            split.draw(split.getBounds());

            assertTrue(terminal.drawCalls().stream()
                    .anyMatch(call -> call.x() == 11 && call.y() == 3 && "┤".equals(call.text())));
        } finally {
            TerminalContext.shutdownInstance();
        }
    }
}
