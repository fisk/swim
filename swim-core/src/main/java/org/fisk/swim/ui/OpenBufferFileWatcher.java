package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Watches directories containing open buffers and delivers changed contents. */
final class OpenBufferFileWatcher implements AutoCloseable {
    private final WatchService _watchService;
    private final Map<Path, WatchKey> _keysByDirectory = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> _directoriesByKey = new ConcurrentHashMap<>();
    private final BiConsumer<Path, String> _onFileChanged;
    private volatile boolean _closed;

    OpenBufferFileWatcher(BiConsumer<Path, String> onFileChanged) throws IOException {
        _watchService = FileSystems.getDefault().newWatchService();
        _onFileChanged = onFileChanged;
        Thread.ofVirtual().start(this::watchLoop);
    }

    void watch(Path path) {
        if (_closed || path == null) return;
        Path directory = path.toAbsolutePath().normalize().getParent();
        if (directory == null || _keysByDirectory.containsKey(directory)) return;
        try {
            WatchKey key = directory.register(_watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            WatchKey prior = _keysByDirectory.putIfAbsent(directory, key);
            if (prior == null) _directoriesByKey.put(key, directory);
            else key.cancel();
        } catch (IOException ignored) {
        }
    }

    private void watchLoop() {
        while (!_closed) {
            WatchKey key;
            try {
                key = _watchService.take();
            } catch (InterruptedException e) {
                continue;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            Path directory = _directoriesByKey.get(key);
            if (directory != null) for (WatchEvent<?> event : key.pollEvents()) {
                if (!(event.context() instanceof Path name) || event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                Path changed = directory.resolve(name).toAbsolutePath().normalize();
                try {
                    _onFileChanged.accept(changed, Files.isRegularFile(changed) ? Files.readString(changed) : "");
                } catch (IOException ignored) {
                }
            }
            if (!key.reset()) {
                Path removed = _directoriesByKey.remove(key);
                if (removed != null) _keysByDirectory.remove(removed, key);
            }
        }
    }

    @Override public void close() throws IOException {
        _closed = true;
        _watchService.close();
        _keysByDirectory.clear();
        _directoriesByKey.clear();
    }
}
