package org.fisk.swim.api;

/** Worker ownership boundary for plugin code. Workers are interrupted and drained on unload. */
public interface SwimPluginWorkers extends AutoCloseable {
    Thread start(Runnable task);

    boolean isClosed();

    @Override
    void close();

    static SwimPluginWorkers unmanaged() {
        return new SwimPluginWorkers() {
            @Override public Thread start(Runnable task) {
                Thread thread = new Thread(task);
                thread.setDaemon(true);
                thread.start();
                return thread;
            }
            @Override public boolean isClosed() { return false; }
            @Override public void close() { }
        };
    }
}
