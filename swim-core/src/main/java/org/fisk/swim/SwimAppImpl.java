package org.fisk.swim;

import java.nio.file.Path;

import org.fisk.swim.api.SwimApp;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.event.KeyStrokeEvent;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.lsp.java.JavaLSPClient;
import org.fisk.swim.ui.Window;

import com.googlecode.lanterna.input.KeyStroke;

public class SwimAppImpl implements SwimApp {
    @Override
    public void start(Path path, SwimHost host) {
        SwimRuntime.setHost(host);
        Window.createInstance(path);
        Window.getInstance().update(true);
        var eventThread = EventThread.getInstance();
        eventThread.addOnEvent(() -> Window.getInstance().update(false));
        eventThread.start();
    }

    @Override
    public void submitKeyStroke(KeyStroke keyStroke) {
        EventThread.getInstance().enqueue(new KeyStrokeEvent(keyStroke));
    }

    @Override
    public void refresh(boolean forced) {
        if (Window.getInstance() != null) {
            Window.getInstance().update(forced);
        }
    }

    @Override
    public Path getCurrentPath() {
        return Window.getInstance().getBufferContext().getBuffer().getPath();
    }

    @Override
    public void showMessage(String message) {
        var eventThread = EventThread.getInstance();
        if (eventThread.isAlive()) {
            eventThread.enqueue(new RunnableEvent(() -> {
                if (Window.getInstance() != null) {
                    Window.getInstance().getCommandView().setMessage(message);
                }
            }));
        } else if (Window.getInstance() != null) {
            Window.getInstance().getCommandView().setMessage(message);
        }
    }

    @Override
    public void close() {
        var window = Window.getInstance();
        if (window != null) {
            window.dispose();
        }
        JavaLSPClient.getInstance().shutdown();
        EventThread.shutdownInstance();
        SwimRuntime.clear();
    }
}
