package org.fisk.swim.todo;

import java.io.IOException;
import java.sql.SQLException;

import org.fisk.swim.ui.Window;

public final class TodoUiSupport {
    private static TodoStore _store;

    private TodoUiSupport() {
    }

    public static void open(Window window) {
        if (window == null) {
            return;
        }
        try {
            if (!window.showTodoWorkspace(store())) {
                window.getCommandView().setMessage("Unable to open Todo workspace");
            }
        } catch (RuntimeException e) {
            window.getCommandView().setMessage(e.getMessage() == null ? "Unable to open Todo workspace" : e.getMessage());
        }
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.isShowingTodoWorkspace()) {
            window.hideCurrentWorkspaceWindow();
            return;
        }
        open(window);
    }

    public static void quickCapture(Window window) {
        if (window == null || window.isShowingTodoQuickCapture()) {
            return;
        }
        try {
            if (!window.showTodoQuickCapture(store())) {
                window.getCommandView().setMessage("Unable to open Todo capture");
            }
        } catch (RuntimeException e) {
            window.getCommandView().setMessage(e.getMessage() == null ? "Unable to open Todo capture" : e.getMessage());
        }
    }

    public static synchronized void shutdownInstance() {
        if (_store != null) {
            _store.close();
            _store = null;
        }
    }

    private static synchronized TodoStore store() {
        if (_store != null) {
            return _store;
        }
        try {
            _store = new H2TodoStore();
            return _store;
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Todo database unavailable: " + e.getMessage(), e);
        }
    }
}
