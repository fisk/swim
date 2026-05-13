package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguageModeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowTest {
    @TempDir
    Path tempDir;

    @Test
    void showAndHideListResizeBufferViewAndResponder() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            int originalHeight = window.getBufferContext().getBufferView().getBounds().getSize().getHeight();

            window.showList(List.of(item("alpha")), "Files");

            assertTrue(window.isShowingList());
            assertTrue(window.isShowingPanel());
            assertEquals(4, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(HeadlessWindowHarness.getField(window, "_panelView"), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));

            window.hideList();

            assertFalse(window.isShowingList());
            assertFalse(window.isShowingPanel());
            assertEquals(originalHeight, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(window.getBufferContext().getBufferView(), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void showAndHideTextPanelResizeBufferViewAndResponder() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("text-panel.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            int originalHeight = window.getBufferContext().getBufferView().getBounds().getSize().getHeight();

            window.showTextPanel("Nemo", "alpha\nbeta");

            assertTrue(window.isShowingPanel());
            assertFalse(window.isShowingList());
            assertEquals(4, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(HeadlessWindowHarness.getField(window, "_panelView"), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));

            window.hidePanel();

            assertFalse(window.isShowingPanel());
            assertEquals(originalHeight, window.getBufferContext().getBufferView().getBounds().getSize().getHeight());
            assertSame(window.getBufferContext().getBufferView(), HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void showChatPanelUsesLargerVerticalShare() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("chat-panel-layout.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var panel = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});

            window.showPanel(panel);

            assertEquals("{0, 2, 24, 2}", absoluteBounds(window.getBufferContext().getBufferView()).toString());
            assertEquals("{0, 4, 24, 5}", absoluteBounds(panel).toString());
        }
    }

    @Test
    void setBufferPathReplacesBufferAndResetsToNormalMode() throws IOException {
        Path first = writeFile("first.txt", "one");
        Path second = writeFile("second.txt", "two");

        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            var window = harness.getWindow();
            window.switchToMode(window.getInputMode());

            window.setBufferPath(second);

            assertSame(window.getNormalMode(), window.getCurrentMode());
            assertEquals("two", window.getBufferContext().getBuffer().getString());
            assertSame(window.getCurrentMode(), HeadlessWindowHarness.getField(window.getBufferContext().getBufferView(), "_firstResponder"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("NORMAL"));
        }
    }

    @Test
    void setBufferPathKeepsCurrentBufferWhenNewBufferInitializationFails() throws Exception {
        Path first = writeFile("first.txt", "one");
        Path second = writeFile("SwimRuntime.java", "class Demo {}\n");
        LanguageModeProvider previous = swapLanguageModeProvider(new LanguageModeProvider() {
            @Override
            public LanguageMode getLanguageMode(Path path) {
                if (path.equals(second.toAbsolutePath())) {
                    throw new IllegalStateException("boom");
                }
                return super.getLanguageMode(path);
            }
        });

        try {
            withLoggerLevel("org.fisk.swim.ui.Window", Level.OFF, () -> {
                try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
                    var window = harness.getWindow();
                    var originalBufferContext = window.getBufferContext();
                    var originalBufferView = originalBufferContext.getBufferView();
                    window.switchToMode(window.getInputMode());

                    assertFalse(window.setBufferPath(second));

                    assertSame(originalBufferContext, window.getBufferContext());
                    assertSame(originalBufferView, window.getBufferContext().getBufferView());
                    assertEquals("one", window.getBufferContext().getBuffer().getString());
                    assertSame(window.getInputMode(), window.getCurrentMode());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            swapLanguageModeProvider(previous);
        }
    }

    @Test
    void splitActiveBufferHorizontallyCreatesIndependentLeafLayout() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();
            var originalContext = window.getBufferContext();

            var splitView = window.splitActiveBufferHorizontally();

            assertEquals("{0, 2, 12, 7}", absoluteBounds(originalView).toString());
            assertEquals("{12, 2, 12, 7}", absoluteBounds(splitView).toString());
            assertSame(splitView, window.getActiveView());
            assertSame(originalContext, window.getBufferContext());
        }
    }

    @Test
    void showPanelOnlySplitsActiveBranch() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var leftView = window.getBufferContext().getBufferView();
            var rightView = window.splitActiveBufferHorizontally();

            window.showTextPanel("Nemo", "alpha\nbeta");

            var panelView = (View) HeadlessWindowHarness.getField(window, "_panelView");
            assertEquals("{0, 2, 12, 7}", absoluteBounds(leftView).toString());
            assertEquals("{12, 2, 12, 4}", absoluteBounds(rightView).toString());
            assertEquals("{12, 6, 12, 3}", absoluteBounds(panelView).toString());
            assertSame(panelView, window.getActiveView());
        }
    }

    @Test
    void closeActiveViewPromotesSiblingAndRestoresLayout() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();
            window.splitActiveBufferHorizontally();

            assertTrue(window.closeActiveView());

            assertEquals("{0, 2, 24, 7}", absoluteBounds(originalView).toString());
            assertSame(originalView, window.getActiveView());
            assertSame(originalView, HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void setBufferPathReplacesOnlyActiveSplit() throws IOException {
        Path first = writeFile("first.txt", "one");
        Path second = writeFile("second.txt", "two");

        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            var window = harness.getWindow();
            var originalContext = window.getBufferContext();
            var originalView = originalContext.getBufferView();
            window.splitActiveBufferHorizontally();

            assertTrue(window.setBufferPath(second));

            assertEquals("one", originalContext.getBuffer().getString());
            assertEquals("two", window.getBufferContext().getBuffer().getString());
            assertEquals("{0, 2, 12, 7}", absoluteBounds(originalView).toString());
            assertEquals("{12, 2, 12, 7}", absoluteBounds((View) window.getActiveView()).toString());
        }
    }

    @Test
    void focusViewMovesAcrossAdjacentSplits() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var leftView = window.getBufferContext().getBufferView();
            var topRightView = window.splitActiveBufferHorizontally();
            var bottomRightView = window.splitActiveBufferVertically();

            assertSame(bottomRightView, window.getActiveView());
            assertTrue(window.focusView(Window.Direction.LEFT));
            assertSame(leftView, window.getActiveView());
            assertTrue(window.focusView(Window.Direction.RIGHT));
            assertSame(topRightView, window.getActiveView());
            assertTrue(window.focusView(Window.Direction.DOWN));
            assertSame(bottomRightView, window.getActiveView());
        }
    }

    @Test
    void closeActiveViewRefusesToCloseLastBuffer() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();

            assertFalse(window.closeActiveView());
            assertSame(originalView, window.getActiveView());
            assertEquals("{0, 2, 24, 7}", absoluteBounds(originalView).toString());
        }
    }

    @Test
    void commandActivationUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate(":");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("command line active"));
            assertTrue(window.getKeyMenuView().bodyText().contains("Tab complete"));
            assertTrue(window.getCommandMenuView().getState().visible());
            assertEquals("q", window.getCommandMenuView().getState().selectedMatch().primaryName());
        }
    }

    @Test
    void narrowWindowExpandsTopMenuHeight() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("narrow-menu.txt", "abc"), 18, 11)) {
            var window = harness.getWindow();

            invoke(window, "applyLayout", new Class<?>[] { Size.class }, Size.create(18, 11));

            assertTrue(window.getKeyMenuView().getBounds().getSize().getHeight() > 2);
            assertEquals(window.getKeyMenuView().getBounds().getSize().getHeight(),
                    window.getBufferContext().getBufferView().getBounds().getPoint().getY());
        }
    }

    @Test
    void typingCommandPrefixUpdatesPopupMatches() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();

            commandView.activate(":");
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('r'));

            var state = window.getCommandMenuView().getState();
            assertTrue(state.visible());
            assertEquals("r", state.prefix());
            assertEquals("reload", state.matches().get(0).primaryName());
            assertEquals("rebuild", state.matches().get(1).primaryName());
        }
    }

    @Test
    void commandPopupUsesAvailableVerticalSpaceForMoreMatches() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate(":");

            assertEquals(9, window.getCommandMenuView().getBounds().getSize().getHeight());
        }
    }

    @Test
    void searchActivationUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate("/");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("forward search"));
            assertTrue(window.getKeyMenuView().bodyText().contains("Enter search forward"));
            assertFalse(window.getCommandMenuView().getState().visible());
        }
    }

    @Test
    void showingListUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.showList(List.of(item("alpha")), "Files");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("list navigation"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("Files"));
            assertTrue(window.getKeyMenuView().bodyText().contains("type to filter"));

            window.hideList();

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("explore key chains"));
        }
    }

    private ListView.ListItem item(String name) {
        return new ListView.ListItem() {
            @Override
            public void onClick() {
                new AtomicInteger().incrementAndGet();
            }

            @Override
            public String displayString() {
                return name;
            }
        };
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static LanguageModeProvider swapLanguageModeProvider(LanguageModeProvider replacement) throws Exception {
        Field field = LanguageModeProvider.class.getDeclaredField("_instance");
        field.setAccessible(true);
        LanguageModeProvider previous = (LanguageModeProvider) field.get(null);
        field.set(null, replacement);
        return previous;
    }

    private static Rect absoluteBounds(View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        View parent = view.getParent();
        while (parent != null) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
            parent = parent.getParent();
        }
        return Rect.create(x, y, view.getBounds().getSize().getWidth(), view.getBounds().getSize().getHeight());
    }

    private static void withLoggerLevel(String loggerName, Level level, Runnable runnable) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var loggerConfig = configuration.getLoggerConfig(loggerName);
        Level previous = loggerConfig.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, level);
        try {
            runnable.run();
        } finally {
            org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, previous);
        }
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
